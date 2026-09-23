package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.common.FileIndexRequested;
import com.ipas.assistant.common.IndexStatus;
import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.dto.FileDtos;
import com.ipas.assistant.dto.KbDtos;
import com.ipas.assistant.entity.FileItem;
import com.ipas.assistant.repository.FileItemRepository;
import com.ipas.assistant.repository.KbChunkRepository;
import com.ipas.assistant.service.storage.FileStoragePort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文件上传与知识库素材入库服务。对应 的「附件存盘 + 上传即建索引」。
 *
 * <h2>一张表两种用途（来自 FileItem 的设计）</h2>
 * 上传的文件可能是「聊天附件」（只把 content 拼在问题前发给模型），也可能是「知识库素材」
 * （归属某知识库，被切分 + 向量化进 kb_chunks 供 RAG 检索）。{@code collectionId} 为 null
 * 表示仅聊天附件。本服务同时服务这两条路径。</p>
 *
 * <h2>正文抽取（零新增依赖原则）</h2>
 * <ul>
 * <li>文本 / 代码 / markdown：直接 UTF-8 解码（含 BOM 剥离）；</li>
 * <li>Word(.docx)：用 JDK 自带的 {@code java.util.zip} + DOM 解析 {@code word/document.xml}，
 * 零依赖，与 早期实现思路一致；</li>
 * <li>PDF：用 Apache PDFBox 3.x 抽取（{@code Loader.loadPDF} + {@code PDFTextStripper}）。
 * 扫描版 PDF（内容其实是图片）抽不到文字，返回空正文、自然不入索引；</li>
 * <li>图片：不抽正文，交给多模态模型直接看图，content 置空，索引状态标 SKIPPED。</li>
 * </ul>
 *
 * <h2>索引异步执行（本类最重要的行为约定）</h2>
 * 上传<b>不再</b>在本方法里同步建索引，而是「落盘 + 落库 + 发布事件」后立即返回，
 * 真正的切分 / 向量化由 {@code KnowledgeIndexWorker} 在事务提交后的后台线程池执行，
 * 并把进度写进 {@code files.index_status}。这样上传接口的响应时间与文档大小无关，
 * 不会因大文档同步索引而超时。
 *
 * <p>失败也不再影响上传本身：索引异常只会让该文件停在 FAILED（含原因，可重试），
 * 上传记录依然有效 —— 否则"嵌入服务没起"会让用户连文件都传不上去。
 * 而「重建索引」是用户显式发起的操作，走另一条同步路径，会如实把失败抛给用户。
 */
@Service
public class FileService {

 private static final Logger log = LoggerFactory.getLogger(FileService.class);

 /** 列表上限，与早期设计 list_files 的 limit(50) 对齐（避免一次拉回几千条拖慢界面）。 */
 private static final int LIST_LIMIT = 50;

 private static final Set<String> TEXT_EXTS = Set.of(
 ".txt", ".md", ".markdown", ".csv", ".json", ".log", ".py", ".js", ".ts", ".html");
 private static final Set<String> PDF_EXTS = Set.of(".pdf");
 private static final Set<String> DOCX_EXTS = Set.of(".docx");
 private static final Set<String> IMAGE_EXTS = Set.of(
 ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp");
 private static final Set<String> ALLOWED_EXTS = new TreeSet<>();

 static {
 ALLOWED_EXTS.addAll(TEXT_EXTS);
 ALLOWED_EXTS.addAll(PDF_EXTS);
 ALLOWED_EXTS.addAll(DOCX_EXTS);
 ALLOWED_EXTS.addAll(IMAGE_EXTS);
 }

 private final FileItemRepository fileRepository;
 private final KbChunkRepository chunkRepository;
 private final KbService kbService;
 private final AppProperties properties;
 /**
 * 文件存储端口：本类只负责"存进去 / 取出来 / 删掉"，
 * <b>不认识磁盘、也不认识任何对象存储</b>。默认实现落在本地磁盘（见 LocalFileStorage），
 * 以后要接对象存储只需换实现，本类一行都不用改。
 *
 * <p>注意：具体实现内部仍然通过 {@code DataDirProvider} 解析数据目录 ——
 * 数据目录是运行时可改的，任何地方都不能绕过它去直读配置。
 */
 private final FileStoragePort fileStorage;

 /**
 * 事件发布器：上传后并不在本方法里同步索引，而是发布一个
 * {@link FileIndexRequested}，由 {@code KnowledgeIndexWorker} 在
 * 「本事务提交之后」于后台线程池里执行。原因见 {@link #upload} 的注释。
 */
 private final ApplicationEventPublisher eventPublisher;

 public FileService(FileItemRepository fileRepository,
 KbChunkRepository chunkRepository,
 KbService kbService,
 AppProperties properties,
 FileStoragePort fileStorage,
 ApplicationEventPublisher eventPublisher) {
 this.fileRepository = fileRepository;
 this.chunkRepository = chunkRepository;
 this.kbService = kbService;
 this.properties = properties;
 this.fileStorage = fileStorage;
 this.eventPublisher = eventPublisher;
 }

 // ==================================================================
 // 上传限制说明（供前端展示）
 // ==================================================================

 /** GET /api/files/limits：返回上传限制说明，供前端展示（避免前后端各写一份导致不一致）。 */
 public FileDtos.FileLimits getLimits() {
 List<FileDtos.TypeGroup> groups = List.of(
 new FileDtos.TypeGroup("PDF 文档", sorted(PDF_EXTS)),
 new FileDtos.TypeGroup("Word 文档", sorted(DOCX_EXTS)),
 new FileDtos.TypeGroup("文本 / 代码", sorted(TEXT_EXTS)),
 new FileDtos.TypeGroup("图片（交给视觉模型）", sorted(IMAGE_EXTS)));
 AppProperties.Rag r = properties.rag();
 return new FileDtos.FileLimits(
 properties.upload().maxMb(),
 properties.upload().maxContentChars(),
 groups,
 sorted(ALLOWED_EXTS),
 r.topK(),
 r.chunkSize(),
 r.chunkOverlap());
 }

 // ==================================================================
 // 上传
 // ==================================================================

 /**
 * 上传文件：存盘 + 抽正文 + （可索引的文件）投递后台索引任务。
 *
 * <p>本方法<b>不做</b>向量化，因此响应时间与文档大小无关：落库后立即返回，
 * 可索引的文件初始状态为 {@code PENDING}，由后台线程池接管（见类注释）。
 *
 * @param collectionId 归属知识库；null = 仅聊天附件
 */
 @Transactional
 public FileItem upload(Long userId, MultipartFile file, Long collectionId) {
 String name = (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank())
 ? "unnamed" : file.getOriginalFilename();
 String ext = lowerExt(name);

 if (!ALLOWED_EXTS.contains(ext)) {
 if (".doc".equals(ext)) {
 throw ApiException.badRequest("暂不支持旧版 .doc，请在 Word 中另存为 .docx 后再上传");
 }
 throw ApiException.badRequest(
 "不支持的文件类型 " + (ext.isEmpty() ? "(无扩展名)" : ext)
 + "。支持：PDF、Word(.docx)、txt/md/csv/json/log/py/js/ts/html、图片(png/jpg/jpeg/webp/gif/bmp)");
 }

 // 先看声明的大小：超限直接拒绝，避免把大文件整个读进内存
 long maxBytes = (long) properties.upload().maxMb() * 1024 * 1024;
 if (file.getSize() > maxBytes) {
 throw ApiException.badRequest(
 String.format("文件 %.1fMB 超过 %dMB 限制", file.getSize() / 1024.0 / 1024.0,
 properties.upload().maxMb()));
 }

 byte[] data;
 try {
 data = file.getBytes();
 } catch (Exception e) {
 throw ApiException.badRequest("读取上传文件失败：" + e.getMessage());
 }
 if (data.length > maxBytes) {
 throw ApiException.badRequest(
 String.format("文件 %.1fMB 超过 %dMB 限制", data.length / 1024.0 / 1024.0,
 properties.upload().maxMb()));
 }

 // 落盘：交给存储端口（本地实现落在 dataDir/uploads/u{userId}/ 下）。
 // 它返回的"相对路径"会原样存进 files.stored_path —— 语义与历史数据完全一致，
 // 保证整个数据目录搬走之后记录依然有效。
 String relativeStored;
 try {
 relativeStored = fileStorage.put(userId, name, data);
 } catch (Exception e) {
 throw ApiException.badRequest("保存上传文件失败：" + e.getMessage());
 }

 // 抽正文（图片不抽；PDF 用 PDFBox；docx 用 zip+XML；其余按文本读取），并按上限截断
 String content = extractBody(data, ext);

 boolean indexable = isIndexable(ext);

 FileItem item = new FileItem();
 item.setUserId(userId);
 item.setCollectionId(collectionId);
 item.setFilename(name);
 item.setStoredPath(relativeStored);
 item.setSize((long) data.length);
 item.setContent(content);
 // 索引状态在这里定好：可索引 = 等后台处理（PENDING）；图片等没有正文的文件 = SKIPPED。
 // 不给图片留 PENDING，是为了避免它永远停在"等待索引"，让前端一直显示"索引中"。
 item.setIndexStatus(indexable ? IndexStatus.PENDING : IndexStatus.SKIPPED);
 item.setChunkCount(0);
 FileItem saved = fileRepository.save(item);
 fileRepository.flush();

 // ★ 关键改动：索引不再在本方法里同步执行。
 // 同步跑的问题：一份上限 50 万字符的文档会切成约 1000 个片段、分 32 批调用嵌入服务，
 // 耗时几十秒到几分钟；上传请求会一直挂着，浏览器 / 网关的短超时先一步把它掐断 ——
 // 用户看到"上传失败"，可文件其实已经存进去了，非常容易误解。
 // 所以本方法只负责「落盘 + 落库 + 立即返回」，索引交给事务提交后的后台线程池：
 //   · 用事件而不是直接调用，是为了让框架保证"事务已提交"这个前提（见 FileIndexRequested）；
 //   · 后台线程会自行回查文件、更新 index_status，前端据此显示进度。
 if (indexable) {
 eventPublisher.publishEvent(new FileIndexRequested(userId, saved.getId(), collectionId));
 }
 return saved;
 }

 // ==================================================================
 // 列表 / 详情 / 重索引 / 删除
 // ==================================================================

 /** GET /api/files：当前用户的文件列表（按时间倒序，最多 {@value #LIST_LIMIT} 条）。 */
 @Transactional(readOnly = true)
 public List<FileDtos.FileOut> list(Long userId) {
 return fileRepository.findByUserIdOrderByIdDesc(userId).stream()
 .limit(LIST_LIMIT)
 .map(FileDtos.FileOut::from)
 .toList();
 }

 /** GET /api/files/{id}：文档详情（含抽取正文），供前端右侧面板查看。 */
 @Transactional(readOnly = true)
 public FileDtos.FileDetail get(Long userId, Long id) {
 return FileDtos.FileDetail.from(requireOwned(userId, id));
 }

 /**
 * POST /api/files/{id}/reindex：对已上传文档重建知识库索引。
 *
 * <p>会从磁盘重新读原文抽取，而不是用 DB 里可能已过时的 content ——
 * 所以解析逻辑修好后，对老文件点一次"重建索引"就能把正文补回来
 * （例如：docx 抽取修好、或 PDF 接入 PDFBox 之后）。
 * 没有可索引正文（图片 / 扫描版 PDF / 空文件）或嵌入失败都如实报错，不静默成功。
 */
 @Transactional
 public FileItem reindex(Long userId, Long id) {
 FileItem item = requireOwned(userId, id);
 String ext = lowerExt(item.getFilename());
 if (!isIndexable(ext)) {
 throw ApiException.badRequest("图片没有可索引的正文（图片交给多模态模型处理）");
 }
 byte[] data = readDisk(item);
 String content = extractBody(data, ext);
 int n = kbService.indexFile(userId, item.getId(), content, item.getCollectionId());
 if (n == 0) {
 throw ApiException.badRequest("该文档没有可索引的正文（可能是扫描版 PDF 或空文件）");
 }
 // 把重新抽到的正文写回：否则 files.content 会一直停留在旧值
 // （例如历史上 docx 抽取失败时存下的兜底文本），与刚重建好的 kb_chunks 对不上。
 item.setContent(content);
 // 同步重建成功后一并刷新索引状态：片段数与状态必须和 kb_chunks 的实际内容一致，
 // 否则界面会出现"显示已索引 0 段，实际能检索到内容"这类对不上的情况。
 item.setIndexStatus(IndexStatus.READY);
 item.setIndexError(null);
 item.setChunkCount(n);
 fileRepository.save(item);
 return item;
 }

 /**
 * 对当前用户<b>所有</b>文档重建索引（知识库页面的「重建全部」）。
 *
 * <h2>★ 为什么必须"从磁盘重抽"，不能用 DB 里存的 content</h2>
 *
 * <p>原实现（沿用早期设计 {@code reindex_all}）直接拿 {@code files.content} 去切分 ——
 * 而那份 content 是<b>上传那一刻一次性抽好的</b>。一旦那次抽取是坏的
 * （例如历史上 docx 抽取整体失败、存下来的其实是"（Word 文档未提取到文本）"这句兜底提示），
 * 用它重建就只是<b>把同一份错内容再切一遍</b>，片段数一点不会变。
 *
 * <p>用户的真实反馈正是如此：<i>"点了重建全部依旧没有任何改动，还是一整个文档分为一个片段"</i>。
 * 所以这里改成和单文件 {@link #reindex} 完全一致的做法：<b>读磁盘 → 重新抽正文 → 重建索引 → 回写 content</b>。
 * 这样"解析逻辑修好了之后再重建"才能真正把老文件救回来。
 *
 * <p>单个文件失败不影响其它文件（逐个 try/catch），返回值里如实体现成功/失败数。
 */
 @Transactional
 public KbDtos.ReindexAllResult reindexAll(Long userId) {
 List<FileItem> files = fileRepository.findByUserIdOrderByIdDesc(userId);
 int indexed = 0;
 int failed = 0;
 for (FileItem f : files) {
 String ext = lowerExt(f.getFilename());
 if (!isIndexable(ext)) {
 continue; // 图片不参与索引，不算失败
 }
 try {
 byte[] data = readDisk(f);
 String content = extractBody(data, ext);
 int n = kbService.indexFile(userId, f.getId(), content, f.getCollectionId());
 if (n > 0) {
 indexed++;
 // 与单文件重建一致：把新抽到的正文写回，避免 content 与 chunks 长期不一致；
 // 同时刷新索引状态与片段数，保证状态列始终反映真实索引结果。
 f.setContent(content);
 f.setIndexStatus(IndexStatus.READY);
 f.setIndexError(null);
 f.setChunkCount(n);
 fileRepository.save(f);
 } else {
 failed++;
 }
 } catch (Exception e) {
 failed++;
 log.warn("重建索引失败（文件 {}）：{}", f.getId(), e.getMessage());
 }
 }
 return new KbDtos.ReindexAllResult(indexed, failed, files.size());
 }

 /** DELETE /api/files/{id}：删除文档，同时清理知识库片段与磁盘原始文件。 */
 @Transactional
 public void delete(Long userId, Long id) {
 FileItem item = requireOwned(userId, id);
 // 先清片段（失败不阻塞删除）
 try {
 chunkRepository.deleteByUserIdAndFileId(userId, id);
 } catch (Exception e) {
 log.warn("删除文件 {} 时清理片段失败（已忽略）：{}", id, e.getMessage());
 }
 // 删磁盘文件
 deleteDiskFile(item);
 fileRepository.delete(item);
 }

 // ==================================================================
 // 正文抽取
 // ==================================================================

 /**
 * 抽取正文并按上传上限截断。upload 与 reindex <b>共用</b>它，
 * 保证"上传时"与"重建索引时"抽出来、截断后的正文完全一致（避免两处逻辑分叉）。
 */
 private String extractBody(byte[] data, String ext) {
 if (IMAGE_EXTS.contains(ext)) {
 return ""; // 图片不抽正文（交给多模态视觉模型）
 }
 String content = extractText(data, ext);
 int maxChars = properties.upload().maxContentChars();
 if (content.length() > maxChars) {
 content = content.substring(0, maxChars) + "\n…（内容过长已截断）";
 }
 return content;
 }

 /**
 * 抽取正文：pdf 用 PDFBox、docx 用 zip+XML、其余按文本读取。
 *
 * @return 抽取到的正文；抽取不到返回空串（不会返回报错文本，避免把报错当正文索引进库）
 */
 private String extractText(byte[] data, String ext) {
 if (PDF_EXTS.contains(ext)) {
 return extractPdf(data);
 }
 if (DOCX_EXTS.contains(ext)) {
 return extractDocx(data);
 }
 // 文本 / 代码 / markdown / csv / json / log / html：UTF-8 解码并剥离 BOM
 String text = new String(data, StandardCharsets.UTF_8);
 if (!text.isEmpty() && text.charAt(0) == '﻿') {
 text = text.substring(1);
 }
 return text;
 }

 /**
 * 从 PDF 提取正文（Apache PDFBox 3.x）。
 *
 * <p>⚠️ API 版本差异：PDFBox 3.x 用静态方法 {@link Loader#loadPDF(byte[])} 加载，
 * 2.x 的 {@code PDDocument.load(...)} 在 3.x 已被移除（照抄旧示例会编译不过）。
 *
 * <p>扫描版 PDF（内容其实是图片）提不出文字，会返回空串 —— 上层把"空正文"
 * 视为"没有可索引内容"，不入库（reindex 时还会提示"可能是扫描版 PDF"）。
 *
 * <p>任何异常都被吞掉并返回空串：<b>抽正文失败绝不能让"上传"失败</b>。
 * 这是与早期设计一致的降级策略——宁可这个文件没进索引，也不能让用户传不上文件。
 */
 private String extractPdf(byte[] data) {
 try (PDDocument doc = Loader.loadPDF(data)) {
 String text = new PDFTextStripper().getText(doc);
 return text == null ? "" : text.trim();
 } catch (Exception e) {
 log.warn("PDF 正文抽取失败（不影响上传，仅不入索引）：{}", e.getMessage());
 return "";
 }
 }

 /** 从 .docx 提取正文（零依赖：zip 解包 + 读 word/document.xml 的 w:p/w:t）。 */
 private String extractDocx(byte[] data) {
 try {
 byte[] xml = null;
 try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
 ZipEntry e;
 while ((e = zis.getNextEntry()) != null) {
 if ("word/document.xml".equals(e.getName())) {
 xml = zis.readAllBytes();
 break;
 }
 }
 }
 if (xml == null) {
 // 典型情况：这是把老 .doc（二进制）改名成 .docx 上传的
 log.warn("docx 抽取失败：压缩包里找不到 word/document.xml（可能是被改名的 .doc）");
 return "";
 }
 // ★★★ 必须开启"命名空间感知"，否则下面的 NS 查询一个节点都匹配不到 ★★★
 // DocumentBuilderFactory 默认 namespaceAware=false：此时解析器不把 "w:p"
 // 拆成 命名空间URI + 本地名(localName)，元素的 localName 为空 ——
 // 而 getElementsByTagNameNS("*","p") 是【按 localName 匹配】的，于是返回空集。
 // 结果就是：任何正常的 docx 都抽不出一个字，全部落进"未提取到文本"的兜底。
 DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
 factory.setNamespaceAware(true);
 var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
 // 取所有段落（w:p），段落内拼接其全部 w:t 文本节点
 var ps = doc.getElementsByTagNameNS("*", "p");
 StringBuilder sb = new StringBuilder();
 for (int i = 0; i < ps.getLength(); i++) {
 var ts = ((org.w3c.dom.Element) ps.item(i)).getElementsByTagNameNS("*", "t");
 StringBuilder para = new StringBuilder();
 for (int j = 0; j < ts.getLength(); j++) {
 para.append(ts.item(j).getTextContent());
 }
 if (para.length() > 0) {
 sb.append(para).append("\n");
 }
 }
 // 抽不到就返回空串 —— 遵守 extractText 的契约（不返回报错文本，
 // 否则这句提示会被当成正文切分、向量化、入库，污染 RAG 检索结果）。
 return sb.toString().strip();
 } catch (Exception e) {
 log.warn("docx 抽取失败（不影响上传，仅不入索引）：{}", e.getMessage());
 return "";
 }
 }

 // ==================================================================
 // 存储 / 工具
 // ==================================================================

 /** 从存储读回原文（重索引用）。文件缺失直接报错提示用户。 */
 private byte[] readDisk(FileItem f) {
 try {
 if (f.getStoredPath() == null) {
 throw new IllegalStateException("存储路径缺失");
 }
 if (!fileStorage.exists(f.getStoredPath())) {
 throw new IllegalStateException("磁盘文件不存在");
 }
 return fileStorage.get(f.getStoredPath());
 } catch (Exception e) {
 throw ApiException.badRequest("读取磁盘文件失败：" + e.getMessage());
 }
 }

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

 /** 取小写扩展名（含点），无扩展名返回空串。 */
 private static String lowerExt(String filename) {
 int dot = filename.lastIndexOf('.');
 if (dot < 0) {
 return "";
 }
 return filename.substring(dot).toLowerCase();
 }

 /**
 * 该扩展名是否可进 RAG 索引。
 *
 * <p><b>只有图片不参与</b>（图片没有正文，交给多模态视觉模型）。
 * PDF 自接入 PDFBox 后<b>可以</b>抽正文，因此也参与索引 —— 扫描版 PDF
 * 抽不到文字时切不出片段、自然不入库，不需要在这里特判。
 */
 private static boolean isIndexable(String ext) {
 return !IMAGE_EXTS.contains(ext);
 }

 private static List<String> sorted(Set<String> exts) {
 return new ArrayList<>(new TreeSet<>(exts));
 }

 /** 归属校验：不属于该用户就 404（而不是 403，避免泄露"这个文件存在"）。 */
 private FileItem requireOwned(Long userId, Long id) {
 return fileRepository.findByIdAndUserId(id, userId)
 .orElseThrow(() -> ApiException.notFound("文档不存在或无权访问"));
 }
}
