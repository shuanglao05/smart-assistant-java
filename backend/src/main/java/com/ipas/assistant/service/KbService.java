package com.ipas.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.dto.KbDtos;
import com.ipas.assistant.entity.FileItem;
import com.ipas.assistant.entity.KbChunk;
import com.ipas.assistant.entity.KbCollection;
import com.ipas.assistant.repository.FileItemRepository;
import com.ipas.assistant.repository.KbChunkRepository;
import com.ipas.assistant.repository.KbCollectionRepository;
import com.ipas.assistant.service.rag.EmbeddingService;
import com.ipas.assistant.service.rag.TextSplitter;
import com.ipas.assistant.service.rag.Vectors;
import com.ipas.assistant.service.rag.VectorIndexPort;
import com.ipas.assistant.service.storage.FileStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库（多库）业务逻辑。对应 的「集合管理 + 文档列表 + 图谱 + 重索引 + rag-config」。
 *
 * <p><b>和 RagService 的分工</b>：{@code RagService} 只负责"检索"那一半
 * （把问题向量化 → 在 {@code kb_chunks} 里算余弦 → 按库取 Top-K）；本服务负责
 * "写入/管理"那一半（集合 CRUD、文档列表、图谱、重索引、rag-config 读写）。
 * 两者共用 {@link EmbeddingService} 与 {@link KbChunkRepository}，避免"界面看到的来源名"
 * 和"Agent 引用的来源名"不一致。</p>
 *
 * <h2>★ 索引幂等</h2>
 * {@link #indexFile} 每次都先 {@code deleteByUserIdAndFileId} 清掉该文档旧片段，
 * 再写新的 —— 所以重复索引同一文件不会产生重复片段（对应 的
 * {@code index_file}「先删旧再写」）。</p>
 *
 * <h2>★ Top-K 回落链</h2>
 * 每个库的 {@code top_k} 字段可以是 null（= 跟随全局默认）。全局默认来自两处，
 * 优先级：app_settings 里用户改过的 {@code rag.top-k} ＞ application.yml 的 {@code app.rag.top-k}。
 * 这套读取走 {@link RuntimeSettingsService#getRagTopK}，与检索侧 {@code RagService} 完全一致。</p>
 */
@Service
public class KbService {

 private static final Logger log = LoggerFactory.getLogger(KbService.class);

 /** 图谱预览每个文档最多带多少片段（避免一次拉爆前端）。对应 max_preview=40。 */
 private static final int GRAPH_MAX_PREVIEW = 40;
 /** 片段预览开头截取多少字。 */
 private static final int CHUNK_PREVIEW_LEN = 70;
 /** 图谱接口要求 collection_id 必传；缺失时给的中文提示。 */
 private static final String MSG_COLLECTION_REQUIRED = "请提供知识库 id（collection_id）";

 private final KbCollectionRepository collectionRepository;
 private final KbChunkRepository chunkRepository;
 private final FileItemRepository fileRepository;
 private final EmbeddingService embeddingService;
 private final AppProperties properties;
 private final RuntimeSettingsService settingsService;
 /**
 * 文件存储端口（见 {@code FileStoragePort}）。
 *
 * <p>本类需要删文件（删除知识库要把它下面的文档一起删掉），
 * 但<b>不该因此依赖磁盘</b>：换成对象存储后，"删文件"仍是同一个调用。
 * 解耦之后本类也不再碰 {@code java.nio.file}，可以在纯内存里单测。
 */
 private final FileStoragePort fileStorage;

 /**
 * 外部向量索引（可选）。
 *
 * <p>用 {@link ObjectProvider} 而不是直接注入：该端口<b>只在
 * {@code app.rag.vector-store=pgvector} 时才存在</b>，直接注入会让默认（mysql）模式下的
 * 应用启动失败 —— "可选依赖"必须在类型层面就是可选的。
 *
 * <p>拿到 null（未启用）或 {@code available()=false}（PG 没起）都直接跳过：
 * 向量索引是可重建的派生物，MySQL 里的片段才是真相源。
 */
 private final ObjectProvider<VectorIndexPort> vectorIndexProvider;

 public KbService(KbCollectionRepository collectionRepository,
 KbChunkRepository chunkRepository,
 FileItemRepository fileRepository,
 EmbeddingService embeddingService,
 AppProperties properties,
 RuntimeSettingsService settingsService,
 FileStoragePort fileStorage,
 ObjectProvider<VectorIndexPort> vectorIndexProvider) {
 this.collectionRepository = collectionRepository;
 this.chunkRepository = chunkRepository;
 this.fileRepository = fileRepository;
 this.embeddingService = embeddingService;
 this.properties = properties;
 this.settingsService = settingsService;
 this.fileStorage = fileStorage;
 this.vectorIndexProvider = vectorIndexProvider;
 }

 // ==================================================================
 // 集合（collection）CRUD
 // ==================================================================

 /** 列出当前用户的全部知识库及其文档数 / 片段数。 */
 @Transactional(readOnly = true)
 public List<KbDtos.KbCollectionOut> listCollections(Long userId) {
 List<KbCollection> cols = collectionRepository.findByUserIdOrderByIdAsc(userId);
 List<KbDtos.KbCollectionOut> out = new ArrayList<>(cols.size());
 for (KbCollection c : cols) {
 int files = (int) fileRepository.countByUserIdAndCollectionId(userId, c.getId());
 int chunks = (int) chunkRepository.countByUserIdAndCollectionId(userId, c.getId());
 out.add(KbDtos.KbCollectionOut.from(c, files, chunks));
 }
 return out;
 }

 /**
 * 新建知识库。
 *
 * <p>与早期设计 {@code create_collection} 一致：<b>不检查重名</b>（早期设计也没有），
 * 允许同名库存在。名称只做 trim + 非空校验。
 */
 @Transactional
 public KbDtos.KbCollectionOut createCollection(Long userId, String name) {
 String n = (name == null ? "" : name).strip();
 if (n.isEmpty()) {
 throw ApiException.badRequest("知识库名称不能为空");
 }
 KbCollection c = new KbCollection();
 c.setUserId(userId);
 c.setName(n);
 KbCollection saved = collectionRepository.save(c);
 return KbDtos.KbCollectionOut.from(saved, 0, 0);
 }

 /**
 * 更新知识库：改名（name）与检索 Top-K（top_k）均可单独提交。
 *
 * <p><b>top_k 的三种语义必须精确还原</b>（对应 数据校验框架 的 model_fields_set）：
 * <ul>
 * <li>JSON 里<b>没有</b> {@code top_k} 字段 → 保持不变；</li>
 * <li>{@code "top_k": null} → 清除单独设置（置 null），回落跟随全局默认；</li>
 * <li>{@code "top_k": 数字} → 设定该库专属值（夹到 1~20）。</li>
 * </ul>
 * 因为 Java 的 {@code Integer} 对"没传"和"传 null"都得到 null，<b>无法区分</b>，
 * 所以本方法直接吃 {@code JsonNode}，用 {@code has("top_k")} 判断字段是否真的出现。
 */
 @Transactional
 public KbDtos.KbCollectionOut updateCollection(Long userId, Long cid, JsonNode patch) {
 KbCollection c = requireOwned(cid, userId);

 if (patch != null && patch.has("name")) {
 String n = patch.get("name").asText("").strip();
 if (n.isEmpty()) {
 throw ApiException.badRequest("知识库名称不能为空");
 }
 c.setName(n);
 }

 if (patch != null && patch.has("top_k")) {
 JsonNode tk = patch.get("top_k");
 if (tk.isNull()) {
 // 显式传 null = 清除单独设置，回落跟随全局默认
 c.setTopK(null);
 } else {
 int k = Math.max(1, Math.min(20, tk.asInt()));
 c.setTopK(k);
 }
 }

 // ⚠️ 先 flush 再构造响应：updated_at 由 @PreUpdate 在刷写时触发，
 // 不 flush 拿到的会是旧时间戳（见 SessionService 的同款说明）。
 collectionRepository.flush();

 int files = (int) fileRepository.countByUserIdAndCollectionId(userId, c.getId());
 int chunks = (int) chunkRepository.countByUserIdAndCollectionId(userId, c.getId());
 return KbDtos.KbCollectionOut.from(c, files, chunks);
 }

 /**
 * 删除知识库：连同其文档（DB 记录 + 磁盘文件）与索引片段一起删除。
 *
 * <p>删除磁盘文件用 try/catch 包住——单文件删失败绝不能阻塞整个删除流程
 * （否则库被删了、磁盘文件却残留，比反过来更糟）。
 */
 @Transactional
 public void deleteCollection(Long userId, Long cid) {
 KbCollection c = requireOwned(cid, userId);

 // 逐个删该库下的文档（DB + 磁盘）
 List<FileItem> files = fileRepository.findByUserIdAndCollectionIdOrderByIdDesc(userId, cid);
 for (FileItem f : files) {
 deleteDiskFile(f);
 fileRepository.delete(f);
 }

 // 清理索引片段（失败不阻塞删除）
 try {
 chunkRepository.deleteByUserIdAndCollectionId(userId, cid);
 } catch (Exception e) {
 log.warn("删除知识库 {} 时清理片段失败（已忽略）：{}", cid, e.getMessage());
 }

 collectionRepository.delete(c);
 }

 // ==================================================================
 // 文档列表 / 图谱
 // ==================================================================

 /**
 * 列出当前用户的文档；给了 collectionId 则只列该库，否则全部。
 * 对应 {@code list_documents}。
 */
 @Transactional(readOnly = true)
 public List<KbDtos.KbDocument> listDocuments(Long userId, Long collectionId) {
 List<FileItem> files = (collectionId == null)
 ? fileRepository.findByUserIdOrderByIdDesc(userId)
 : fileRepository.findByUserIdAndCollectionIdOrderByIdDesc(userId, collectionId);
 List<KbDtos.KbDocument> out = new ArrayList<>(files.size());
 for (FileItem f : files) {
 int chunks = (int) chunkRepository.countByUserIdAndFileId(userId, f.getId());
 out.add(KbDtos.KbDocument.from(f, chunks));
 }
 return out;
 }

 /**
 * 返回某个知识库的「库 → 文档 → 片段」关系图数据。
 * 片段只带开头预览（每篇最多 {@value #GRAPH_MAX_PREVIEW} 条），避免大数据量一次拉爆前端。
 */
 @Transactional(readOnly = true)
 public KbDtos.KbGraph graph(Long userId, Long collectionId) {
 if (collectionId == null) {
 throw ApiException.badRequest(MSG_COLLECTION_REQUIRED);
 }
 KbCollection c = requireOwned(collectionId, userId);

 List<FileItem> files = fileRepository.findByUserIdAndCollectionIdOrderByIdDesc(userId, collectionId);
 int totalChunks = 0;
 List<KbDtos.KbGraphDoc> documents = new ArrayList<>(files.size());
 for (FileItem f : files) {
 int total = (int) chunkRepository.countByUserIdAndFileId(userId, f.getId());
 List<KbChunk> rows = chunkRepository.findByUserIdAndFileIdOrderByChunkIndexAsc(userId, f.getId());
 List<KbDtos.KbChunkPreview> previews = new ArrayList<>();
 int limit = Math.min(rows.size(), GRAPH_MAX_PREVIEW);
 for (int i = 0; i < limit; i++) {
 KbChunk r = rows.get(i);
 String text = (r.getText() == null ? "" : r.getText());
 previews.add(new KbDtos.KbChunkPreview(r.getChunkIndex(),
 text.length() > CHUNK_PREVIEW_LEN ? text.substring(0, CHUNK_PREVIEW_LEN) : text));
 }
 documents.add(new KbDtos.KbGraphDoc(f.getId(), f.getFilename(), total, previews));
 totalChunks += total;
 }

 int collFiles = (int) fileRepository.countByUserIdAndCollectionId(userId, c.getId());
 KbDtos.KbCollectionOut collOut = KbDtos.KbCollectionOut.from(c, collFiles, totalChunks);
 return new KbDtos.KbGraph(collOut, documents);
 }

 // ==================================================================
 // 索引（写入侧核心）
 // ==================================================================

 /**
 * 把一份文档切分 + 向量化后写入知识库，返回片段数量。
 *
 * <p>对应 的 {@code index_file}。流程：清旧片段 → 语义切分 →
 * 批量向量化（{@link EmbeddingService}）→ 写 {@code kb_chunks}。
 *
 * <p><b>嵌入服务不可用时的处理</b>：{@link EmbeddingService#embed} 失败返回 null
 * （不抛异常，避免炸掉调用链）。这里选择向上抛 500，让"重索引"这类显式操作
 * 能明确告诉用户"索引失败"；而"上传即索引"的调用方会 catch 住、不影响上传本身
 * （见 {@code FileService.upload}）。</p>
 *
 * @return 写入的片段数；正文切不出片段时返回 0（不算错误）
 */
 @Transactional
 public int indexFile(Long userId, Long fileId, String text, Long collectionId) {
 // ① 幂等：先清掉该文档的旧片段（重复索引不会产生重复片段）
 chunkRepository.deleteByUserIdAndFileId(userId, fileId);

 List<String> chunks = TextSplitter.splitText(text,
 properties.rag().chunkSize(), properties.rag().chunkOverlap());
 if (chunks.isEmpty()) {
 return 0;
 }

 // ② 批量向量化（EmbeddingService 内部已按 embed_batch 自动分批）
 float[][] vectors = embeddingService.embed(chunks);
 if (vectors == null) {
 throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
 "嵌入服务不可用（Ollama 未启动或未加载 bge-m3），索引失败");
 }

 // ③ 逐片段落库（向量存 float32 二进制：比 JSON 文本小约 4.5 倍，
 // 且读取时是一次内存拷贝而非文本解析 —— 后者才是检索慢的主因）。
 // 旧版 embedding(JSON) 列不再写入；历史数据的读取兼容由 RagService 负责。
 // 顺带收集"待同步到外部向量库"的点（只有启用 pgvector 时才真正用到）
 List<VectorIndexPort.VectorPoint> points = new ArrayList<>(chunks.size());
 for (int i = 0; i < chunks.size(); i++) {
 KbChunk ch = new KbChunk();
 ch.setUserId(userId);
 ch.setCollectionId(collectionId);
 ch.setFileId(fileId);
 ch.setChunkIndex(i);
 ch.setText(chunks.get(i));
 ch.setEmbeddingBin(Vectors.toBytes(vectors[i]));
 KbChunk saved = chunkRepository.save(ch);
 // id 必须取自保存后的实体：自增主键在 save 之后才有值，
 // 而外部向量库正是按这个 id 关联 MySQL 里的片段。
 points.add(new VectorIndexPort.VectorPoint(saved.getId(), userId, collectionId, fileId, vectors[i]));
 }
 syncVectorIndex(userId, fileId, points);
 return chunks.size();
 }

 /**
 * 把片段向量同步到外部向量库（尽力而为，绝不影响索引流程本身）。
 *
 * <p>为什么"失败也不报错"：向量库是可重建的派生物（真相源是 MySQL 的 kb_chunks，
 * 更源头是磁盘上的原始文件）。写不进去最多让这次检索退化成 MySQL 路径，
 * 不该反过来让"上传 / 重建索引"失败。
 */
 private void syncVectorIndex(Long userId, Long fileId, List<VectorIndexPort.VectorPoint> points) {
 VectorIndexPort index = vectorIndexProvider.getIfAvailable();
 if (index == null || !index.available()) {
 return;
 }
 // 先删该文档的旧向量再写：重新索引会换掉全部片段 id，
 // 不先删就会留下永远查不到的孤儿向量（读取时会跳过，但白占空间）。
 index.deleteByFile(userId, fileId);
 index.upsert(points);
 }

 // 注意：「重建全部」**不在本类实现** —— 已移到 {@code FileService#reindexAll}。
 // 原因：它必须"从磁盘重新抽正文"，而"读磁盘 + 抽正文"的能力在 FileService。
 // 早期设计放在这里、且直接用 DB 里存的那份 content（上传时一次性抽好的），
 // 于是当那次抽取是坏的（例如旧版 docx 抽取整体失败）时，重建只是把同一份错内容
 // 又切一遍 —— 用户看到"点了重建全部，片段数一点没变"（真实 bug）。

 // ==================================================================
 // rag-config（检索参数读写）
 // ==================================================================

 /** GET /rag-config：返回当前检索参数（Top-K / 切片大小 / 重叠 / 批大小）。 */
 @Transactional(readOnly = true)
 public KbDtos.RagConfig getRagConfig() {
 AppProperties.Rag r = properties.rag();
 return new KbDtos.RagConfig(r.topK(), r.chunkSize(), r.chunkOverlap(), r.embedBatch());
 }

 /**
 * POST /rag-config：调整全局默认检索片段数（Top-K），立即生效并落库持久化。
 *
 * <p>对应"改 {@code RAG_TOP_K} 并回写 .env"。Java 侧不写文件，
 * 而是写进 app_settings（{@code rag.top-k}），读取时由 {@link RuntimeSettingsService#getRagTopK}
 * 回落默认值——这正是"写 .env 立即生效"的原语意等价物。
 *
 * <p><b>返回值刻意只带 top_k 一个字段</b>：早期设计是 {@code return {"top_k": k}}，
 * 而不是把四个参数一起回传。所以这里返回 {@link KbDtos.TopKResult} 而非
 * GET 用的 {@link KbDtos.RagConfig}，避免多吐字段造成契约走样。
 *
 * <p><b>越界值夹取而非报错</b>：与早期设计 {@code k = max(1, min(20, int(top_k)))} 一致。
 * 数据校验框架 侧 {@code top_k: int} 没有范围约束，所以 99 不会 422，而是被夹成 20。
 */
 @Transactional
 public KbDtos.TopKResult setRagConfig(Long userId, int topK) {
 int k = Math.max(1, Math.min(20, topK));
 settingsService.put(userId, RuntimeSettingsService.KEY_RAG_TOP_K, String.valueOf(k));
 return new KbDtos.TopKResult(k);
 }

 // ==================================================================
 // 内部辅助
 // ==================================================================

 /** 删除原始文件（交给存储端口；失败不影响删除流程本身）。 */
 private void deleteDiskFile(FileItem f) {
 try {
 if (f.getStoredPath() == null) {
 return;
 }
 fileStorage.delete(f.getStoredPath());
 } catch (Exception e) {
 log.warn("删除磁盘文件失败（已忽略）：{}", e.getMessage());
 }
 }

 /** 归属校验：不属于该用户就 404（而不是 403，避免泄露"这个库存在"）。 */
 private KbCollection requireOwned(Long cid, Long userId) {
 return collectionRepository.findByIdAndUserId(cid, userId)
 .orElseThrow(() -> ApiException.notFound("知识库不存在或无权访问"));
 }
}
