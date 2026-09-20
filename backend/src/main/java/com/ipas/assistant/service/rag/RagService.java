package com.ipas.assistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 private final ObjectMapper objectMapper = new ObjectMapper();

 public RagService(KbChunkRepository chunkRepository,
 KbCollectionRepository collectionRepository,
 FileItemRepository fileRepository,
 EmbeddingService embeddingService,
 AppProperties properties,
 RuntimeSettingsService settingsService) {
 this.chunkRepository = chunkRepository;
 this.collectionRepository = collectionRepository;
 this.fileRepository = fileRepository;
 this.embeddingService = embeddingService;
 this.properties = properties;
 this.settingsService = settingsService;
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

 // ① 捞候选片段（不过滤 Top-K，分组排序在内存做）
 List<KbChunk> rows = (collectionIds == null || collectionIds.isEmpty())
 ? chunkRepository.findByUserId(userId)
 : chunkRepository.findByUserIdAndCollectionIdIn(userId, collectionIds);
 if (rows.isEmpty()) {
 return new Result(List.of(), List.of());
 }

 // ② 各知识库的 Top-K（NULL → 全局默认）
 Map<Long, Integer> perCollection = new HashMap<>();
 for (KbCollection c : collectionRepository.findByUserIdOrderByIdAsc(userId)) {
 // Top-K 回落：该库单独设了就用它；否则用"当前生效的全局默认"（可能已被设置页改过并落库）
 perCollection.put(c.getId(), c.getTopK() != null ? c.getTopK() : settingsService.getRagTopK(userId));
 }

 // ③ 把问题向量化（失败 → 直接返回空，工具会提示"知识库未建立索引/嵌入服务不可用"）
 float[] qvec = embeddingService.embed(q);
 if (qvec == null || qvec.length == 0) {
 return new Result(List.of(), List.of());
 }

 // ④ 逐片段算余弦，按 collection_id 分组（file_id → 文件名 顺带解析）
 Map<Long, List<Hit>> groups = new LinkedHashMap<>();
 Map<Long, String> fileNames = new HashMap<>();
 for (KbChunk r : rows) {
 float[] vec = parseEmbedding(r.getEmbedding());
 if (vec == null || vec.length != qvec.length) {
 continue; // 维度不符的脏数据跳过（见类注释）
 }
 double score = cosine(qvec, vec);
 String name = fileNames.computeIfAbsent(r.getFileId(), fid -> fileNameOf(userId, fid));
 groups.computeIfAbsent(r.getCollectionId(), k -> new ArrayList<>())
 .add(new Hit(r.getText(), name, score));
 }
 if (groups.isEmpty()) {
 return new Result(List.of(), List.of());
 }

 // ⑤ 每个库各取自己的前 N 名，再合并按相似度排序
 int globalTopK = settingsService.getRagTopK(userId);
 List<Hit> merged = new ArrayList<>();
 for (Map.Entry<Long, List<Hit>> e : groups.entrySet()) {
 int k = perCollection.getOrDefault(e.getKey(), globalTopK);
 List<Hit> items = e.getValue();
 items.sort((a, b) -> Double.compare(b.score(), a.score()));
 int take = Math.min(k, items.size());
 merged.addAll(items.subList(0, take));
 }
 merged.sort((a, b) -> Double.compare(b.score(), a.score()));

 // ⑥ 总上限保护：库多时避免片段总数过大撑爆上下文
 int maxTotal = properties.rag().maxTotalChunks();
 if (merged.size() > maxTotal) {
 merged = new ArrayList<>(merged.subList(0, maxTotal));
 }

 // ⑦ 去重保序的来源文件名（前端据此在回答下标注"引用来源"）
 List<String> names = new ArrayList<>();
 Set<String> seen = new LinkedHashSet<>();
 for (Hit h : merged) {
 if (seen.add(h.fileName())) {
 names.add(h.fileName());
 }
 }
 return new Result(merged, names);
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

 /** 把库中存的 JSON 数组文本解析成 float[]。格式异常返回 null（上层跳过该片段）。 */
 private float[] parseEmbedding(String embeddingJson) {
 if (embeddingJson == null || embeddingJson.isBlank()) {
 return null;
 }
 try {
 var arr = objectMapper.readTree(embeddingJson);
 if (!arr.isArray()) {
 return null;
 }
 float[] out = new float[arr.size()];
 for (int i = 0; i < arr.size(); i++) {
 var n = arr.get(i);
 out[i] = n.isNumber() ? n.floatValue() : 0f;
 }
 return out;
 } catch (Exception e) {
 log.warn("片段 embedding 解析失败（数据可能损坏）：{}", e.getMessage());
 return null;
 }
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
