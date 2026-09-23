package com.ipas.assistant.service.rag;

import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.entity.FileItem;
import com.ipas.assistant.entity.KbChunk;
import com.ipas.assistant.entity.KbCollection;
import com.ipas.assistant.repository.FileItemRepository;
import com.ipas.assistant.repository.KbChunkRepository;
import com.ipas.assistant.repository.KbCollectionRepository;
import com.ipas.assistant.service.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 检索服务。对应 的 {@code search()}（含余弦打分与按库 Top-K）。
 *
 * <h2>设计定位</h2>
 *
 * <p>本服务只负责"<b>检索</b>"这一半：把问题向量化 → 在候选片段里算余弦 → 每个知识库各取自己
 * 的 Top-K → 合并封顶。它<b>不负责"入库"（切分 + 向量化 + 写库）</b>—— 那一半
 * 属于 {@code files} / {@code kb} 模块（文档上传与重索引），会调用本服务的
 * {@link EmbeddingService} 与 {@code KbChunkRepository} 完成。</p>
 *
 * <p>之所以把检索单独抽出来，是因为它是 Agent 工具 {@code search_knowledge_base} 的核心，
 * 而入库侧还要等文件上传模块落地。本服务让两侧共用同一套余弦/Top-K/文件名映射逻辑，
 * 避免"界面看到的来源名"和"Agent 引用的来源名"不一致。</p>
 *
 * <h2>★ 为什么 Top-K 按"知识库"各自生效（与早期设计一致）</h2>
 *
 * <p>原 注释里的关键设计：每个知识库可在 {@code kb_collections.top_k}
 * 单独设检索条数（NULL = 跟随全局 {@code app.rag.top-k}）。一次检索若涉及多个库，
 * <b>每个库各取自己配置的条数，再合并按相似度排序</b>。这样"规范库要精准（取 2）"
 * 和"资料库要广撒网（取 8）"可以并存。所以这里先捞该用户的<b>全部</b>候选片段
 * （不在 SQL 里 LIMIT），在内存里分组、各自排序取 Top-K，再合并封顶
 * {@code app.rag.max-total-chunks}。</p>
 *
 * <h2>维度不一致的片段直接跳过</h2>
 *
 * <p>理论上所有向量都是 1024 维（bge-m3）。但万一某条是旧模型遗留、或存坏了，
 * 维度对不上余弦没意义，跳过它而不是抛异常 —— 检索宁可少召回也不该崩。</p>
 */
@Service
public class RagService {

 private static final Logger log = LoggerFactory.getLogger(RagService.class);

 private final KbChunkRepository chunkRepository;
 private final KbCollectionRepository collectionRepository;
 private final FileItemRepository fileRepository;
 private final EmbeddingService embeddingService;
 private final AppProperties properties;
 private final RuntimeSettingsService settingsService;

 /**
 * 外部向量索引（可选）。
 *
 * <p>用 {@link ObjectProvider} 而不是直接注入：该端口<b>只在
 * {@code app.rag.vector-store=pgvector} 时才存在</b>，直接注入会让默认（mysql）模式的
 * 应用启动失败 —— "可选依赖"必须在类型层面就是可选的。
 *
 * <p>拿不到、或拿到了但 {@code available()=false}（PG 没起）→ 走 MySQL 内存算余弦那条路。
 * <b>两条路产出的候选结构完全一致</b>，所以后面的融合 / 门控 / 扩窗 / 预算一行都不用改。
 */
 private final ObjectProvider<VectorIndexPort> vectorIndexProvider;

 public RagService(KbChunkRepository chunkRepository,
 KbCollectionRepository collectionRepository,
 FileItemRepository fileRepository,
 EmbeddingService embeddingService,
 AppProperties properties,
 RuntimeSettingsService settingsService,
 ObjectProvider<VectorIndexPort> vectorIndexProvider) {
 this.chunkRepository = chunkRepository;
 this.collectionRepository = collectionRepository;
 this.fileRepository = fileRepository;
 this.embeddingService = embeddingService;
 this.properties = properties;
 this.settingsService = settingsService;
 this.vectorIndexProvider = vectorIndexProvider;
 }

 /** 单条命中：片段正文 + 来源文件名 + 相似度。文件名已经解析好，方便工具拼 {@code 【文件名】片段}。 */
 public record Hit(String text, String fileName, double score) {
 }

 /** 检索结果：命中的片段列表 + 去重保序的来源文件名（用于 SSE 的 {@code sources} 事件）。 */
 public record Result(List<Hit> hits, List<String> sourceNames) {
 }

 /**
 * 检索与 query 最相关的片段。
 *
 * @param userId 归属用户（多用户隔离）
 * @param query 检索问题
 * @param collectionIds 只在指定知识库里检索；为空/Null 则检索该用户全部知识库
 * @return 命中片段与来源文件名；空列表表示"没找到"或"嵌入服务不可用"
 */
 public Result retrieve(Long userId, String query, List<Long> collectionIds) {
 String q = (query == null ? "" : query).strip();
 if (q.isBlank()) {
 return new Result(List.of(), List.of());
 }

 // ① 各知识库的 Top-K（NULL → 全局默认）
 Map<Long, Integer> perCollection = new HashMap<>();
 for (KbCollection c : collectionRepository.findByUserIdOrderByIdAsc(userId)) {
 // Top-K 回落：该库单独设了就用它；否则用"当前生效的全局默认"（可能已被设置页改过并落库）
 perCollection.put(c.getId(), c.getTopK() != null ? c.getTopK() : settingsService.getRagTopK(userId));
 }

 // ② 把问题向量化（失败 → 直接返回空，工具会提示"知识库未建立索引/嵌入服务不可用"）
 float[] qvec = embeddingService.embed(q);
 if (qvec == null || qvec.length == 0) {
 return new Result(List.of(), List.of());
 }

 // ③④ 候选与两路分数：优先外部向量索引，不可用则回落到 MySQL 内存检索。
 // 两条路产出的结构完全一致，所以下游的融合 / 门控 / 扩窗 / 预算无需区分来源。
 Candidates cand = buildCandidates(userId, q, qvec, perCollection, collectionIds);
 if (cand.chunkById().isEmpty()) {
 return new Result(List.of(), List.of());
 }
 Map<Long, KbChunk> chunkById = cand.chunkById();
 Map<Long, Double> vecScores = cand.vecScores();
 Map<Long, String> fileNames = cand.fileNames();
 Map<String, KbChunk> byFileIndex = cand.byFileIndex();
 Map<Long, Double> kwScores = cand.kwScores();
 Map<Long, List<Long>> vecRankedByCollection = cand.rankedByCollection();

 // ⑥ 逐库融合两路，并保留该库自己的 Top-K 上限
 // 融合只使用名次（见 Rrf），所以余弦分与关键词分不需要任何归一化就能放一起。
 int globalTopK = settingsService.getRagTopK(userId);
 List<Scored> merged = new ArrayList<>();
 for (Map.Entry<Long, List<Long>> e : vecRankedByCollection.entrySet()) {
 Long collId = e.getKey();
 int k = perCollection.getOrDefault(collId, globalTopK);
 List<Long> vecList = e.getValue();
 List<Long> kwList = keywordRankedIn(collId, chunkById, kwScores);
 // 关闭混合检索时只融合向量一路 —— 融合逻辑不变，只是通道少一个
 List<List<Long>> channels = properties.rag().hybridEnabled()
 ? List.of(vecList, kwList)
 : List.of(vecList);
 Map<Long, Double> fused = Rrf.fuseScores(channels);
 for (Long id : Rrf.topByScore(fused, k)) {
 merged.add(new Scored(id, fused.getOrDefault(id, 0.0),
 vecScores.getOrDefault(id, -1.0), kwScores.getOrDefault(id, 0.0)));
 }
 }
 if (merged.isEmpty()) {
 return new Result(List.of(), List.of());
 }

 // ⑦ 合并所有库的结果并按融合分降序；同分时用向量分、再用 id 兜底，保证顺序确定
 merged.sort((x, y) -> {
 int c = Double.compare(y.rrf(), x.rrf());
 if (c != 0) {
 return c;
 }
 c = Double.compare(y.vec(), x.vec());
 return c != 0 ? c : Long.compare(x.id(), y.id());
 });

 // ⑧ 闸门：向量分达标 <b>或</b> 关键词分达标，任一通过即保留
 // 两路各自设门槛（相对阈值），因为"语义像"和"字面命中"是两种不同的相关性，
 // 用同一个阈值衡量必然误伤其中一边 —— 关键词命中的片段语义分常常并不高。
 double floor = properties.rag().minSimilarity();
 double kwFloor = properties.rag().keywordRelativeScoreFloor();
 double maxKw = merged.stream().mapToDouble(Scored::kw).max().orElse(0.0);
 List<Scored> eligible = new ArrayList<>();
 for (Scored s : merged) {
 boolean vecOk = floor <= 0 || s.vec() >= floor;
 boolean kwOk = kwFloor > 0 && maxKw > 0 && s.kw() >= kwFloor * maxKw;
 if (vecOk || kwOk) {
 eligible.add(s);
 }
 }
 if (eligible.isEmpty()) {
 // 全部未过门槛 → 当作"没检索到"，让上层走无证据短路，而不是把噪声喂给模型
 log.debug("检索结果全部未通过门槛（向量阈值 {}，关键词相对门槛 {}），按无证据处理", floor, kwFloor);
 return new Result(List.of(), List.of());
 }

 // ⑧b 上下文扩窗：把命中片段的相邻片段一并纳入证据
 // 目的：模型只看到"一小段"时容易答偏（命中的可能只是某个列表的第 3 项，
 // 看不到它属于哪一节、前后还有什么）。扩窗后回答更完整，代价只是多几百字证据。
 int window = properties.rag().neighborWindow();
 Map<Long, String> textById = new HashMap<>();
 for (Scored s : eligible) {
 textById.put(s.id(), expandWithNeighbors(chunkById.get(s.id()), window, byFileIndex));
 }

 // ⑨ 条数上限保护：库多时避免片段总数过大撑爆上下文
 int maxTotal = properties.rag().maxTotalChunks();
 if (maxTotal > 0 && eligible.size() > maxTotal) {
 eligible = new ArrayList<>(eligible.subList(0, maxTotal));
 }

 // ⑩ 证据字符预算：按融合分降序累加，超出预算即停止收录
 // eligible 已按分数降序，所以"一直加到装不下"就等于"取最相关的一批"。
 // 与条数上限互补：条数控规模，字符控真实的上下文占用（长短片段差异很大）。
 int budget = properties.rag().evidenceMaxChars();
 List<Scored> kept = eligible;
 if (budget > 0) {
 kept = new ArrayList<>();
 int used = 0;
 for (Scored s : eligible) {
 // 用扩窗后的文本算长度：预算控的是"真正要发给模型的字数"，扩窗多出来的也得算进去
 String text = textById.get(s.id());
 int len = text == null ? 0 : text.length();
 if (used + len > budget) {
 break;
 }
 kept.add(s);
 used += len;
 }
 // 兜底：若连第一条都装不下（单个片段就超过整个预算），也至少保留它 ——
 // 否则会出现"明明检索到了有效片段，却对模型说没有证据"这种反常结果。
 if (kept.isEmpty() && !eligible.isEmpty()) {
 kept.add(eligible.get(0));
 }
 }

 // ⑪ 拼装命中 + 去重保序的来源文件名（前端据此在回答下标注"引用来源"）
 List<Hit> hits = new ArrayList<>(kept.size());
 List<String> names = new ArrayList<>();
 Set<String> seen = new LinkedHashSet<>();
 for (Scored s : kept) {
 KbChunk c = chunkById.get(s.id());
 String name = fileNames.get(c.getFileId());
 // Hit 里带的 score 仍是<b>向量相似度</b>：它是可解释的"语义接近程度"，
 // 而融合分只用于决定顺序，对外展示没有意义。
 // 正文用扩窗后的版本（可能含相邻片段），回答的上下文更完整。
 hits.add(new Hit(textById.get(s.id()), name, s.vec()));
 if (seen.add(name)) {
 names.add(name);
 }
 }
 return new Result(hits, names);
 }

 /**
 * 候选与两路分数（向量通道 + 关键词通道）的计算结果。
 *
 * <p>把它打成一个包返回，是为了让"候选从哪来"（MySQL 内存算 / 外部向量库）对下游完全透明：
 * 融合、门控、上下文扩窗、证据预算都只认这几个映射。
 */
 private record Candidates(Map<Long, KbChunk> chunkById,
 Map<Long, Double> vecScores,
 Map<Long, List<Long>> rankedByCollection,
 Map<Long, Double> kwScores,
 Map<Long, String> fileNames,
 Map<String, KbChunk> byFileIndex) {

 /** 空结果（没有候选）：对应"没检索到"，上层会走无证据短路。 */
 static Candidates empty() {
 return new Candidates(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
 }
 }

 /**
 * 选一条路去算候选：优先外部向量索引，不可用则回落 MySQL。
 *
 * <p>回落是硬要求：外部依赖（PG）缺席时检索必须照常工作，只是慢一点 —— 见 {@link VectorIndexPort}。
 */
 private Candidates buildCandidates(Long userId, String q, float[] qvec,
 Map<Long, Integer> perCollection, List<Long> collectionIds) {
 VectorIndexPort index = vectorIndexProvider.getIfAvailable();
 if (index != null && index.available()) {
 return candidatesFromVectorIndex(index, userId, q, qvec, perCollection, collectionIds);
 }
 return candidatesFromMysql(userId, q, qvec, collectionIds);
 }

 /**
 * 回落路径：把该用户的候选片段整批捞出来，在内存里逐条算余弦。
 *
 * <p>这也是引入外部向量库之前的老实现，原样保留 —— 它是默认路径，也是向量库出问题时的兜底。
 */
 private Candidates candidatesFromMysql(Long userId, String q, float[] qvec, List<Long> collectionIds) {
 List<KbChunk> rows = (collectionIds == null || collectionIds.isEmpty())
 ? chunkRepository.findByUserId(userId)
 : chunkRepository.findByUserIdAndCollectionIdIn(userId, collectionIds);
 if (rows.isEmpty()) {
 return Candidates.empty();
 }

 // 顺带建立三张索引表，供后面的融合、门控与拼装复用：
 // chunkById（id → 片段）、vecScores（id → 余弦分）、fileNames（fileId → 文件名）
 Map<Long, KbChunk> chunkById = new LinkedHashMap<>();
 Map<Long, Double> vecScores = new HashMap<>();
 Map<Long, String> fileNames = new HashMap<>();
 Map<Long, List<Long>> pendingByCollection = new LinkedHashMap<>();
 // 「文件 + 序号」→ 片段：上下文扩窗时用它找邻居，避免为每个命中再查一次库
 Map<String, KbChunk> byFileIndex = new HashMap<>();
 for (KbChunk r : rows) {
 float[] vec = resolveVector(r);
 if (vec == null || vec.length != qvec.length) {
 continue; // 维度不符的脏数据跳过（见类注释）
 }
 chunkById.put(r.getId(), r);
 vecScores.put(r.getId(), cosine(qvec, vec));
 fileNames.computeIfAbsent(r.getFileId(), fid -> fileNameOf(userId, fid));
 byFileIndex.put(fileIndexKey(r), r);
 pendingByCollection.computeIfAbsent(r.getCollectionId(), k -> new ArrayList<>()).add(r.getId());
 }
 if (chunkById.isEmpty()) {
 return Candidates.empty();
 }
 Map<Long, List<Long>> rankedByCollection = new LinkedHashMap<>();
 for (Map.Entry<Long, List<Long>> e : pendingByCollection.entrySet()) {
 List<Long> ids = e.getValue();
 ids.sort((a, b) -> byScoreDesc(a, b, vecScores));
 rankedByCollection.put(e.getKey(), ids);
 }
 Map<Long, Double> kwScores = keywordScores(q, userId, chunkById.keySet());
 return new Candidates(chunkById, vecScores, rankedByCollection, kwScores, fileNames, byFileIndex);
 }

 /**
 * 外部向量索引路径：只捞回命中的片段，正文回 MySQL 取。
 *
 * <p>与回落路径的关键差别是<b>不加载全量片段</b>（那正是引入向量库要省掉的开销）。
 * 代价是要多做三件事：
 * <ol>
 * <li><b>关键词通道的候选集要单独给</b>：它不再等于"已加载的全部片段"，
 * 所以先用 null（不限）取回该用户的关键词命中，再按 id 回表；</li>
 * <li><b>检索范围要按会话勾选过滤</b>：{@code perCollection} 是该用户的<b>全部</b>知识库，
 * 而 {@code collectionIds} 才是"本次会话选了哪几个"。若直接遍历前者，
 * 就会出现"用户在会话里只勾了一个库、却检索了全部库"的越权行为；</li>
 * <li><b>扩窗的邻居要补查</b>：邻居不在内存里，得把"命中涉及的文件"的片段补进来。</li>
 * </ol>
 */
 private Candidates candidatesFromVectorIndex(VectorIndexPort index, Long userId, String q,
 float[] qvec, Map<Long, Integer> perCollection, List<Long> collectionIds) {
 // 关键词通道先算（候选传 null = 不限制，之后按"确实存在且属于本次范围"筛）
 Map<Long, Double> kwScores = keywordScores(q, userId, null);

 // 本次要检索的集合：会话指定了就用它，否则用该用户的全部知识库
 Set<Long> scope = (collectionIds == null || collectionIds.isEmpty())
 ? new LinkedHashSet<>(perCollection.keySet())
 : new LinkedHashSet<>(collectionIds);

 Map<Long, Double> vecScores = new HashMap<>();
 Map<Long, List<Long>> rankedByCollection = new LinkedHashMap<>();
 Set<Long> needIds = new LinkedHashSet<>(kwScores.keySet());
 for (Long collId : scope) {
 Integer topK = perCollection.get(collId);
 if (topK == null) {
 continue; // 不属于该用户的集合：忽略（防御越权传入的 id）
 }
 List<VectorIndexPort.Neighbor> hits = index.search(userId, collId, qvec, Math.max(1, topK));
 List<Long> ids = new ArrayList<>(hits.size());
 for (VectorIndexPort.Neighbor n : hits) {
 ids.add(n.chunkId());
 vecScores.put(n.chunkId(), n.similarity());
 }
 rankedByCollection.put(collId, ids);
 needIds.addAll(ids);
 }
 if (needIds.isEmpty()) {
 return Candidates.empty();
 }

 // 回 MySQL 取正文。查不到的直接丢弃：向量库是可重建的派生物，
 // 可能残留"片段已被删除、向量还在"的孤儿记录，那种 id 不该参与后续排名。
 Map<Long, KbChunk> chunkById = new LinkedHashMap<>();
 for (KbChunk c : chunkRepository.findAllById(needIds)) {
 chunkById.put(c.getId(), c);
 }
 if (chunkById.isEmpty()) {
 return Candidates.empty();
 }
 vecScores.keySet().retainAll(chunkById.keySet());
 if (!kwScores.isEmpty()) {
 kwScores = new LinkedHashMap<>(kwScores); // keywordScores 可能返回不可变 Map.of()，先复制再筛
 kwScores.keySet().retainAll(chunkById.keySet());
 }
 for (List<Long> ids : rankedByCollection.values()) {
 ids.retainAll(chunkById.keySet());
 }

 Map<Long, String> fileNames = new HashMap<>();
 for (KbChunk c : chunkById.values()) {
 fileNames.computeIfAbsent(c.getFileId(), fid -> fileNameOf(userId, fid));
 }

 // 上下文扩窗要在内存里找邻居，所以把"命中涉及的文件"的全部片段补进来。
 // 只在真的开了扩窗时才查，避免白白多一次查询。
 Map<String, KbChunk> byFileIndex = new HashMap<>();
 if (properties.rag().neighborWindow() > 0 && !fileNames.isEmpty()) {
 for (KbChunk c : chunkRepository.findByUserIdAndFileIdIn(userId, new ArrayList<>(fileNames.keySet()))) {
 byFileIndex.put(fileIndexKey(c), c);
 }
 }

 return new Candidates(chunkById, vecScores, rankedByCollection, kwScores, fileNames, byFileIndex);
 }

 /**
 * 融合后的一条候选：id + 融合分 + 两路原始分。
 *
 * <p>把两路原始分一起带上是刻意的：后面的闸门需要按<b>各自</b>的口径判断，
 * 只留一个融合分就没法区分"语义命中"与"字面命中"。
 */
 private record Scored(long id, double rrf, double vec, double kw) {
 }

 /** 按分数降序、同分按 id 升序比较两个 id（顺序必须确定，否则同样输入两次结果不同）。 */
 private static int byScoreDesc(Long a, Long b, Map<Long, Double> scores) {
 int c = Double.compare(scores.getOrDefault(b, Double.NEGATIVE_INFINITY),
 scores.getOrDefault(a, Double.NEGATIVE_INFINITY));
 return c != 0 ? c : Long.compare(a, b);
 }

 /** 「文件 + 序号」的复合键：上下文扩窗据此在内存里找相邻片段，不必再查一次库。 */
 private static String fileIndexKey(KbChunk c) {
 return c.getFileId() + ":" + (c.getChunkIndex() == null ? 0 : c.getChunkIndex());
 }

 /**
 * 把命中片段扩展成"含邻居"的正文（见 {@code AppProperties.Rag#neighborWindow}）。
 *
 * <p>邻居 = 同一文件里序号 ±window 的片段。靠内存索引 {@code byFileIndex} 找，
 * 不产生额外查询；找不到邻居时退回片段自身正文 —— <b>绝不会因此返回空</b>
 * （返回空会让上层以为"没检索到"，那是最糟的结果）。
 *
 * @param window 0 表示不扩窗，直接返回原正文
 */
 private static String expandWithNeighbors(KbChunk center, int window,
 Map<String, KbChunk> byFileIndex) {
 String own = center.getText() == null ? "" : center.getText();
 if (window <= 0) {
 return own;
 }
 int index = center.getChunkIndex() == null ? 0 : center.getChunkIndex();
 StringBuilder sb = new StringBuilder();
 for (int i = index - window; i <= index + window; i++) {
 KbChunk neighbor = byFileIndex.get(center.getFileId() + ":" + i);
 if (neighbor == null || neighbor.getText() == null) {
 continue;
 }
 if (sb.length() > 0) {
 sb.append('\n');
 }
 sb.append(neighbor.getText());
 }
 return sb.length() == 0 ? own : sb.toString();
 }

 /**
 * 关键词通道：查全文索引，返回 {@code chunkId → 关键词分}。
 *
 * <p><b>只保留本次候选范围内的 id</b>：全文索引是对整张表检索的，
 * 当会话只勾选了部分知识库时，返回结果里会混进范围外的片段，必须过滤掉，
 * 否则会出现"没勾这个库却引用了它的内容"。
 *
 * <p><b>失败一律降级</b>：老库可能还没跑建索引的迁移，或数据库不支持该语法。
 * 关键词通道是"锦上添花"的一路，绝不能因为它不可用就让整个检索失败 ——
 * 所以这里吞掉异常、返回空表，检索自动退化为"只用向量通道"。
 */
 /**
 * 关键词通道：走数据库全文索引拿 id → 关键词分。
 *
 * @param candidates 允许的片段 id 集合；<b>null 表示不限制</b>
 * （外部向量索引路径先算关键词、拿到 id 才回表，那时还没有"已加载片段"可作候选）
 */
 private Map<Long, Double> keywordScores(String query, Long userId, Set<Long> candidates) {
 if (!properties.rag().hybridEnabled()) {
 return Map.of();
 }
 // 取比最终结果更宽的一批候选：融合需要"名次"，只取几条会让名次信息太稀薄
 int limit = Math.max(50, properties.rag().maxTotalChunks() * 5);
 try {
 List<Object[]> rows = chunkRepository.keywordSearch(query, userId, limit);
 Map<Long, Double> out = new LinkedHashMap<>();
 for (Object[] row : rows) {
 if (row == null || row.length < 2 || row[0] == null) {
 continue;
 }
 long id = ((Number) row[0]).longValue();
 if (candidates != null && !candidates.contains(id)) {
 continue; // 不在本次检索范围（例如会话只勾选了部分知识库）
 }
 double score = row[1] == null ? 0.0 : ((Number) row[1]).doubleValue();
 if (score > 0) {
 out.put(id, score);
 }
 }
 return out;
 } catch (Exception e) {
 log.warn("关键词通道不可用，本次检索降级为仅向量通道：{}", e.getMessage());
 return Map.of();
 }
 }

 /** 关键词通道里属于指定知识库、且仍在候选集合内的片段，按关键词分降序。 */
 private List<Long> keywordRankedIn(Long collectionId, Map<Long, KbChunk> candidates,
 Map<Long, Double> kwScores) {
 List<Long> ids = new ArrayList<>();
 for (Long id : kwScores.keySet()) {
 KbChunk c = candidates.get(id);
 if (c != null && java.util.Objects.equals(c.getCollectionId(), collectionId)) {
 ids.add(id);
 }
 }
 ids.sort((a, b) -> byScoreDesc(a, b, kwScores));
 return ids;
 }

 /** file_id → 文件名；查不到时用"文件{id}"兜底（至少给用户一个可辨识的标识）。 */
 private String fileNameOf(Long userId, Long fileId) {
 if (fileId == null) {
 return "未知文件";
 }
 return fileRepository.findByIdAndUserId(fileId, userId)
 .map(FileItem::getFilename)
 .orElse("文件" + fileId);
 }

 /**
 * 取一条片段的向量：<b>优先二进制列</b>，为空才回退解析旧的 JSON 文本列。
 *
 * <p>为什么要有这个"双读"：向量改存二进制后，库里仍可能留着改版前写入的 JSON 行。
 * 一次性回填（{@code EmbeddingBackfillRunner}）完成前，读取侧必须两种格式都认识，
 * 否则历史文档会突然从检索结果里消失 —— 那是比"慢一点"严重得多的问题。
 *
 * <p>返回 null 表示这条没有可用向量（两种情况：两列都空，或格式损坏）——
 * 上层对 null 的处理是"跳过该片段"，宁可少召回也不让一条脏数据把整次检索搞崩。
 */
 private float[] resolveVector(KbChunk c) {
 float[] v = Vectors.fromBytes(c.getEmbeddingBin());
 if (v != null) {
 return v;
 }
 // 回退：改版前写入的 JSON 数组文本
 return Vectors.fromJson(c.getEmbedding());
 }

 /** 余弦相似度：值越大越相似（-1~1）。任一为空或维度不一致返回 -1（视为不相似）。 */
 static double cosine(float[] a, float[] b) {
 if (a == null || b == null || a.length == 0 || a.length != b.length) {
 return -1.0;
 }
 double dot = 0.0;
 double na = 0.0;
 double nb = 0.0;
 for (int i = 0; i < a.length; i++) {
 dot += a[i] * b[i];
 na += a[i] * a[i];
 nb += b[i] * b[i];
 }
 if (na == 0.0 || nb == 0.0) {
 return -1.0;
 }
 return dot / (Math.sqrt(na) * Math.sqrt(nb));
 }
}
