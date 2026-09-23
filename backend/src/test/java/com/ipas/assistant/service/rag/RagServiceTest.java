package com.ipas.assistant.service.rag;

import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.entity.FileItem;
import com.ipas.assistant.entity.KbChunk;
import com.ipas.assistant.entity.KbCollection;
import com.ipas.assistant.repository.FileItemRepository;
import com.ipas.assistant.repository.KbChunkRepository;
import com.ipas.assistant.repository.KbCollectionRepository;
import com.ipas.assistant.service.RuntimeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索服务（{@code RagService.retrieve}）的单元测试。
 *
 * <h2>为什么这组测试最值钱</h2>
 *
 * <p>检索是整个知识问答的"地基"：召回错了，后面模型再强也答不对。而它<b>全是纯逻辑</b>
 * （分组、算余弦、按库取 Top-K、闸门、预算），完全可以在没有数据库、没有模型、
 * 没有 Ollama 的环境下确定性验证 —— 这类逻辑一旦被改动却跑偏（例如过滤条件写反、
 * 预算累加用错边界），只有断言能发现，人工点几下很难覆盖。
 *
 * <p>同时它也是"相似度闸门 / 证据预算"这两个新特性的回归护栏：
 * 以后无论谁再动检索，这些行为都必须保持。
 *
 * <h2>怎么做到"确定性"</h2>
 * <ul>
 * <li>用<b>二维向量</b>精确控制余弦分数：查询向量固定为 {@code [1,0]}，
 * 于是片段向量 {@code [cos, sin]} 的余弦分恰好等于 {@code cos}；</li>
 * <li>嵌入服务、三个仓储、运行设置全部用 Mockito 替身，不触碰任何外部依赖。</li>
 * </ul>
 */
class RagServiceTest {

    private static final Long USER = 1L;

    private KbChunkRepository chunkRepository;
    private KbCollectionRepository collectionRepository;
    private FileItemRepository fileRepository;
    private EmbeddingService embeddingService;
    private RuntimeSettingsService settingsService;

    @BeforeEach
    void setUp() {
        chunkRepository = mock(KbChunkRepository.class);
        collectionRepository = mock(KbCollectionRepository.class);
        fileRepository = mock(FileItemRepository.class);
        embeddingService = mock(EmbeddingService.class);
        settingsService = mock(RuntimeSettingsService.class);

        // 查询向量固定为 [1,0]：片段向量 [c, s] 的余弦分就等于 c
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1f, 0f});
        // 全局默认 Top-K（各库未单独设置时使用）。必须给正值，
        // 否则 Mockito 返回 int 默认值 0 → 每个库都会取 0 条，测试失去意义。
        when(settingsService.getRagTopK(USER)).thenReturn(10);
        // 关键词通道默认"什么都没命中"：等价于纯向量检索，
        // 于是除混合检索专项用例外，其余用例的行为与加混合检索之前完全一致。
        when(chunkRepository.keywordSearch(anyString(), anyLong(), anyInt())).thenReturn(List.of());
    }

    // ==================================================================
    // 相似度闸门（新增特性）
    // ==================================================================

    @Test
    @DisplayName("闸门：低于相似度阈值的片段被丢弃，够格的保留")
    void gateDropsLowSimilarityChunks() {
        collectionRepositoryStub(collection(10L, 10));
        // A 分 = 1.0（[1,0]）；B 分 = 0.0（[0,1]）
        chunksStub(
                chunk(1L, 10L, 100L, "A", "1.0", "0.0"),
                chunk(2L, 10L, 100L, "B", "0.0", "1.0"));
        fileStub(100L, "doc.txt");

        // 阈值 0.35：只应留下 A
        RagService.Result r = service(0.35, 6000, 10, 30).retrieve(USER, "问题", null);

        assertEquals(1, r.hits().size(), "应只剩 1 条通过闸门");
        assertEquals("A", r.hits().get(0).text());
        assertEquals(List.of("doc.txt"), r.sourceNames());
    }

    @Test
    @DisplayName("闸门：全部低于阈值时按“无证据”返回空（避免拿噪声喂模型编答案）")
    void gateReturnsEmptyWhenNothingPasses() {
        collectionRepositoryStub(collection(10L, 10));
        chunksStub(chunk(1L, 10L, 100L, "A", "0.6", "0.8")); // 分 = 0.6
        fileStub(100L, "doc.txt");

        RagService.Result r = service(0.9, 6000, 10, 30).retrieve(USER, "问题", null);

        assertTrue(r.hits().isEmpty(), "全部低于 0.9 阈值，应返回空");
        assertTrue(r.sourceNames().isEmpty());
    }

    @Test
    @DisplayName("闸门：阈值 <= 0 表示关闭（不过滤）")
    void gateIsDisabledWhenThresholdIsZero() {
        collectionRepositoryStub(collection(10L, 10));
        chunksStub(
                chunk(1L, 10L, 100L, "A", "1.0", "0.0"),
                chunk(2L, 10L, 100L, "B", "0.0", "1.0"));
        fileStub(100L, "doc.txt");

        RagService.Result r = service(0.0, 6000, 10, 30).retrieve(USER, "问题", null);

        assertEquals(2, r.hits().size(), "关闭闸门后两条都应保留");
    }

    // ==================================================================
    // 证据字符预算（新增特性）
    // ==================================================================

    @Test
    @DisplayName("预算：按相似度降序累加，超出字符预算即停止收录")
    void budgetStopsAtCharLimit() {
        collectionRepositoryStub(collection(10L, 10));
        // 三条各 60 字，分数 1.0 / 0.9 / 0.8
        chunksStub(
                chunk(1L, 10L, 100L, sixty('A'), "1.0", "0.0"),
                chunk(2L, 10L, 100L, sixty('B'), cos(0.9), sin(0.9)),
                chunk(3L, 10L, 100L, sixty('C'), cos(0.8), sin(0.8)));
        fileStub(100L, "doc.txt");

        // 预算 150：60 + 60 = 120 放得下，再加 60 = 180 超了 → 应只留 2 条
        RagService.Result r = service(0.0, 150, 10, 30).retrieve(USER, "问题", null);

        assertEquals(2, r.hits().size(), "超出预算的第三条不应被收录");
        int total = r.hits().stream().mapToInt(h -> h.text().length()).sum();
        assertTrue(total <= 150, "收录的字符总数不应超过预算，实际：" + total);
    }

    @Test
    @DisplayName("预算：单条就超预算时也要保留它（否则会出现“明明检索到却说没证据”）")
    void budgetAlwaysKeepsAtLeastOneHit() {
        collectionRepositoryStub(collection(10L, 10));
        chunksStub(chunk(1L, 10L, 100L, sixty('A'), "1.0", "0.0"));
        fileStub(100L, "doc.txt");

        RagService.Result r = service(0.0, 10, 10, 30).retrieve(USER, "问题", null);

        assertEquals(1, r.hits().size(), "至少要保留一条证据");
    }

    // ==================================================================
    // 既有行为的回归护栏
    // ==================================================================

    @Test
    @DisplayName("回归：每个知识库各自取自己的 Top-K（不是全局 Top-K）")
    void perCollectionTopKIsRespected() {
        // 库 10 只要 1 条，库 20 要 2 条
        collectionRepositoryStub(collection(10L, 1), collection(20L, 2));
        chunksStub(
                chunk(1L, 10L, 100L, "A1", "1.0", "0.0"),
                chunk(2L, 10L, 100L, "A2", cos(0.9), sin(0.9)),
                chunk(3L, 10L, 100L, "A3", cos(0.8), sin(0.8)),
                chunk(4L, 20L, 200L, "B1", cos(0.7), sin(0.7)),
                chunk(5L, 20L, 200L, "B2", cos(0.6), sin(0.6)),
                chunk(6L, 20L, 200L, "B3", cos(0.5), sin(0.5)));
        fileStub(100L, "a.txt");
        fileStub(200L, "b.txt");

        RagService.Result r = service(0.0, 100000, 10, 30).retrieve(USER, "问题", null);

        // 库10 取 1 条（最高的 A1），库20 取 2 条（B1、B2）→ 共 3 条
        assertEquals(3, r.hits().size());
        List<String> texts = r.hits().stream().map(RagService.Hit::text).toList();
        assertTrue(texts.contains("A1"), "库10 应取到最高分的 A1");
        assertTrue(texts.contains("B1") && texts.contains("B2"), "库20 应取到 B1、B2");
        assertTrue(!texts.contains("A2") && !texts.contains("A3") && !texts.contains("B3"),
                "被 Top-K 截掉的片段不应出现，实际：" + texts);
    }

    @Test
    @DisplayName("回归：向量维度与查询不一致的脏数据被跳过，而不是抛异常")
    void mismatchedDimensionChunksAreSkipped() {
        collectionRepositoryStub(collection(10L, 10));
        // 三维向量 vs 二维查询 → 维度不符，应被跳过
        chunksStub(chunk(1L, 10L, 100L, "BAD", "1.0", "0.0", "0.0"));
        fileStub(100L, "doc.txt");

        RagService.Result r = service(0.0, 6000, 10, 30).retrieve(USER, "问题", null);

        assertTrue(r.hits().isEmpty(), "维度不符的片段应被跳过，结果为“没检索到”");
    }

    @Test
    @DisplayName("回归：空问题或空白问题直接返回空（不去连嵌入服务）")
    void blankQueryReturnsEmptyWithoutEmbedding() {
        RagService.Result r = service(0.35, 6000, 10, 30).retrieve(USER, "   ", null);

        assertTrue(r.hits().isEmpty());
        assertTrue(r.sourceNames().isEmpty());
    }

    // ==================================================================
    // 向量改存二进制（新增特性）：双读兼容 + 二进制优先
    // ==================================================================

    @Test
    @DisplayName("双读：只有旧 JSON 向量时也能检索到（历史数据不会因改版而消失）")
    void fallsBackToJsonWhenBinaryIsMissing() {
        collectionRepositoryStub(collection(10L, 10));
        chunksStub(chunk(1L, 10L, 100L, "A", "1.0", "0.0")); // 仅 JSON 列有值
        fileStub(100L, "doc.txt");

        RagService.Result r = service(0.35, 6000, 10, 30).retrieve(USER, "问题", null);

        assertEquals(1, r.hits().size(), "回退解析 JSON 后应能命中");
    }

    @Test
    @DisplayName("二进制优先：两列都有时用二进制列，不解析旧 JSON")
    void binaryEmbeddingTakesPrecedenceOverJson() {
        collectionRepositoryStub(collection(10L, 10));
        // 二进制列放真正该用的向量（与查询同向 → 分 1.0）
        KbChunk c = binaryChunk(1L, 10L, 100L, "A", new float[]{1f, 0f});
        // 旧 JSON 列故意放一个方向相反的向量（若被误用，分会是 -1.0）
        c.setEmbedding("[-1.0,0.0]");
        chunksStub(c);
        fileStub(100L, "doc.txt");

        RagService.Result r = service(0.35, 6000, 10, 30).retrieve(USER, "问题", null);

        assertEquals(1, r.hits().size());
        assertEquals(1.0, r.hits().get(0).score(), 1e-9,
                "应使用二进制列（分 1.0），而不是旧 JSON 列（分 -1.0）");
    }

    // ==================================================================
    // 混合检索（新增特性）：关键词通道 + RRF 融合
    // ==================================================================

    @Test
    @DisplayName("混合检索：字面命中但语义分低的片段，因关键词通道过门槛而被保留")
    void keywordHitSurvivesEvenWithLowVectorScore() {
        collectionRepositoryStub(collection(10L, 10));
        chunksStub(
                chunk(1L, 10L, 100L, "A", "1.0", "0.0"),   // 语义分 1.0
                chunk(2L, 10L, 100L, "B", "0.0", "1.0"));  // 语义分 0.0（单靠向量会被阈值丢掉）
        fileStub(100L, "doc.txt");
        // 关键词通道只命中 B，且是这批里的最高分
        when(chunkRepository.keywordSearch(anyString(), anyLong(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, 0.9}));

        RagService.Result r = service(0.35, 6000, 10, 30).retrieve(USER, "报销标准", null);

        List<String> texts = r.hits().stream().map(RagService.Hit::text).toList();
        assertTrue(texts.contains("B"),
                "字面命中的片段不应因语义分低而被丢弃，实际：" + texts);
    }

    @Test
    @DisplayName("混合检索：两路都命中的片段排在只被一路命中的前面（RRF 的效果）")
    void doubleHitRanksFirstAfterFusion() {
        collectionRepositoryStub(collection(10L, 10));
        chunksStub(
                chunk(1L, 10L, 100L, "A", "1.0", "0.0"),           // 向量第 1
                chunk(2L, 10L, 100L, "B", cos(0.9), sin(0.9)));    // 向量第 2
        fileStub(100L, "doc.txt");
        // 关键词通道里 B 排第 1（A 未被字面命中）
        when(chunkRepository.keywordSearch(anyString(), anyLong(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, 0.9}));

        RagService.Result r = service(0.0, 6000, 10, 30).retrieve(USER, "问题", null);

        assertEquals("B", r.hits().get(0).text(),
                "B 在向量排第 2、关键词排第 1，融合分应超过只在向量排第 1 的 A");
    }

    @Test
    @DisplayName("混合检索：关闭开关后只走向量通道（关键词命中不参与）")
    void hybridDisabledFallsBackToVectorOnly() {
        collectionRepositoryStub(collection(10L, 10));
        chunksStub(chunk(1L, 10L, 100L, "A", "1.0", "0.0"));
        fileStub(100L, "doc.txt");
        when(chunkRepository.keywordSearch(anyString(), anyLong(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 0.9}));

        RagService.Result r = service(0.0, 6000, 10, 30, false, 0.35).retrieve(USER, "问题", null);

        assertEquals(1, r.hits().size(), "关掉混合检索后结果应只来自向量通道");
    }

    // ==================================================================
    // 上下文扩窗（新增特性）
    // ==================================================================

    @Test
    @DisplayName("扩窗：命中片段会带上同文件的相邻片段（回答上下文更完整）")
    void neighborWindowExpandsHitText() {
        collectionRepositoryStub(collection(10L, 10));
        // 同一文件 3 个片段，只有中间那段（index=1）语义命中
        chunksStub(
                chunkAt(1L, 10L, 100L, 0, "前一段", "0.0", "1.0"),
                chunkAt(2L, 10L, 100L, 1, "命中的这一段", "1.0", "0.0"),
                chunkAt(3L, 10L, 100L, 2, "后一段", "0.0", "1.0"));
        fileStub(100L, "doc.txt");

        // 窗口 0：只拿到命中那段
        RagService.Result plain = service(0.35, 6000, 10, 30).retrieve(USER, "问题", null);
        assertEquals(1, plain.hits().size());
        assertEquals("命中的这一段", plain.hits().get(0).text());

        // 窗口 1：前后各带一段
        RagService.Result expanded = service(0.35, 6000, 10, 30, true, 0.35, 1)
                .retrieve(USER, "问题", null);
        String text = expanded.hits().get(0).text();
        assertTrue(text.contains("前一段"), "应带上前一段，实际：" + text);
        assertTrue(text.contains("命中的这一段"), "命中段自身必须在，实际：" + text);
        assertTrue(text.contains("后一段"), "应带上后一段，实际：" + text);
    }

    @Test
    @DisplayName("扩窗：没有邻居时退回片段自身正文（绝不能因为扩窗失败变成空）")
    void neighborWindowHandlesMissingNeighbors() {
        collectionRepositoryStub(collection(10L, 10));
        // 序号 5 是孤立的：前后都没有片段
        chunksStub(chunkAt(1L, 10L, 100L, 5, "孤立片段", "1.0", "0.0"));
        fileStub(100L, "doc.txt");

        RagService.Result r = service(0.0, 6000, 10, 30, true, 0.35, 1)
                .retrieve(USER, "问题", null);

        assertEquals(1, r.hits().size());
        assertEquals("孤立片段", r.hits().get(0).text(), "没有邻居时必须原样返回自身正文");
    }

    @Test
    @DisplayName("扩窗：扩出来的字数也计入证据预算（不会因为扩窗而撑爆上下文）")
    void neighborWindowRespectsEvidenceBudget() {
        collectionRepositoryStub(collection(10L, 10));
        // 三段各 60 字；扩窗后第一段变成 180 字，预算 200 → 只装得下第一段
        chunksStub(
                chunkAt(1L, 10L, 100L, 0, sixty('A'), cos(1.0), sin(1.0)),
                chunkAt(2L, 10L, 100L, 1, sixty('B'), cos(0.9), sin(0.9)),
                chunkAt(3L, 10L, 100L, 2, sixty('C'), cos(0.8), sin(0.8)));
        fileStub(100L, "doc.txt");

        RagService.Result r = service(0.0, 200, 10, 30, true, 0.35, 1)
                .retrieve(USER, "问题", null);

        int total = r.hits().stream().mapToInt(h -> h.text().length()).sum();
        assertTrue(total <= 200, "扩窗后的字数也必须受预算约束，实际：" + total);
    }

    // ==================================================================
    // 测试脚手架
    // ==================================================================

    private RagService service(double minSimilarity, int evidenceMaxChars, int topK, int maxTotalChunks) {
        return service(minSimilarity, evidenceMaxChars, topK, maxTotalChunks, true, 0.35, 0);
    }

    /** 带"混合检索开关 + 关键词相对门槛"的重载（混合检索专项用例用）。 */
    private RagService service(double minSimilarity, int evidenceMaxChars, int topK, int maxTotalChunks,
                               boolean hybridEnabled, double keywordRelativeScoreFloor) {
        return service(minSimilarity, evidenceMaxChars, topK, maxTotalChunks,
                hybridEnabled, keywordRelativeScoreFloor, 0);
    }

    /**
     * 完整重载：多一个"上下文扩窗窗口"。
     *
     * <p>既有用例一律走窗口 0（= 不扩窗），这样它们的断言（条数、文本长度）与扩窗特性加入前
     * 完全一致 —— 新增能力不该悄悄改变既有行为。
     */
    private RagService service(double minSimilarity, int evidenceMaxChars, int topK, int maxTotalChunks,
                               boolean hybridEnabled, double keywordRelativeScoreFloor, int neighborWindow) {
        AppProperties.Rag rag = new AppProperties.Rag(
                "http://localhost:11434/v1", "bge-m3",
                500, 80, topK, 32, maxTotalChunks, minSimilarity, evidenceMaxChars,
                hybridEnabled, keywordRelativeScoreFloor, neighborWindow);
        return new RagService(chunkRepository, collectionRepository, fileRepository,
                embeddingService, propertiesWith(rag), settingsService, noVectorIndex());
    }

    /**
     * 向量索引端口的"空提供者"。
     *
     * <p>本测试类守的是 <b>MySQL 回落路径</b>的语义（引入外部向量库之前的那套行为），
     * 所以让它永远拿不到端口 —— mock 的 {@code getIfAvailable()} 默认返回 null，
     * 正好等价于"未启用外部向量库 / PG 不可用"。
     *
     * <p>pgvector 那条路径由真机集成测试 {@code PgVectorIndexTest} 覆盖。
     */
    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<VectorIndexPort> noVectorIndex() {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }

    /** 只关心 rag 段，其余用最小可用的空壳值（record 必须补全所有组件）。 */
    private static AppProperties propertiesWith(AppProperties.Rag rag) {
        return new AppProperties(
                new AppProperties.Security("k", "HS256", 60),
                "./data",
                new AppProperties.Llm("ollama",
                        new AppProperties.Llm.Ollama("http://localhost:11434", "m", false, 8192),
                        new AppProperties.Llm.Cloud("", "", "", false, 0),
                        "vlm"),
                rag,
                new AppProperties.History(12, 8, 6, 6000, 300, 3000),
                new AppProperties.Upload(50, 500000),
                new AppProperties.Tools("", ""),
                new AppProperties.Cors(List.of()),
                new AppProperties.Chat(true, true));
    }

    private static KbCollection collection(Long id, Integer topK) {
        KbCollection c = new KbCollection();
        c.setId(id);
        c.setTopK(topK);
        return c;
    }

    private void collectionRepositoryStub(KbCollection... collections) {
        when(collectionRepository.findByUserIdOrderByIdAsc(USER)).thenReturn(List.of(collections));
    }

    private void chunksStub(KbChunk... chunks) {
        when(chunkRepository.findByUserId(USER)).thenReturn(List.of(chunks));
    }

    private void fileStub(Long fileId, String filename) {
        FileItem f = new FileItem();
        f.setId(fileId);
        f.setFilename(filename);
        when(fileRepository.findByIdAndUserId(fileId, USER)).thenReturn(Optional.of(f));
    }

    /** 构造一个片段；embedding 用维度字符串拼接，长度即维度。 */
    private static KbChunk chunk(Long id, Long collectionId, Long fileId, String text, String... vec) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setUserId(USER);
        c.setCollectionId(collectionId);
        c.setFileId(fileId);
        c.setText(text);
        c.setEmbedding("[" + String.join(",", vec) + "]");
        return c;
    }

    /** 构造一个"向量存二进制列"的片段（对应改版后的新数据）。 */
    private static KbChunk binaryChunk(Long id, Long collectionId, Long fileId, String text, float[] vec) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setUserId(USER);
        c.setCollectionId(collectionId);
        c.setFileId(fileId);
        c.setText(text);
        c.setEmbeddingBin(Vectors.toBytes(vec));
        return c;
    }

    /**
     * 构造带指定序号（chunkIndex）的片段。
     *
     * <p>上下文扩窗是按"同文件 + 序号 ±N"找邻居的，所以用例必须能指定序号；
     * 既有的 {@link #chunk} 辅助方法不设序号（默认 0），在"不扩窗"的用例里没有影响。
     */
    private static KbChunk chunkAt(Long id, Long collectionId, Long fileId, int chunkIndex,
                                   String text, String... vec) {
        KbChunk c = chunk(id, collectionId, fileId, text, vec);
        c.setChunkIndex(chunkIndex);
        return c;
    }

    /** 长度为 n 的文本（用同一字母填充，便于识别是第几条）。 */
    private static String sixty(char ch) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 单位向量分量（保证 [cos,sin] 的模为 1，余弦分恰好等于 cos）。 */
    private static String cos(double score) {
        return String.valueOf(score);
    }

    private static String sin(double score) {
        return String.valueOf(Math.sqrt(1 - score * score));
    }
}
