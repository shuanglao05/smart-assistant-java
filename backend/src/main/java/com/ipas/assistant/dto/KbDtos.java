package com.ipas.assistant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ipas.assistant.entity.FileItem;
import com.ipas.assistant.entity.KbCollection;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库（多库）模块请求 / 响应体。对应 的
 * {@code KbCollectionOut} / {@code KbCollectionCreate} / {@code KbCollectionUpdate} /
 * {@code KbDocument} / {@code KbChunkPreview} / {@code KbGraphDoc} / {@code KbGraph}，
 * 以及 的 {@code RagConfigUpdate}。
 *
 * <p>字段名用 camelCase，由 {@code application.yml} 的
 * {@code jackson.property-naming-strategy: SNAKE_CASE} 统一序列化成 snake_case
 * （{@code collection_id} / {@code created_at} / {@code top_k} …），与原 接口框架 输出一致。
 */
public final class KbDtos {

 private KbDtos() {
 }

 /** 知识库（集合）及其汇总统计。对应 {@code KbCollectionOut}。 */
 public record KbCollectionOut(
 Long id,
 String name,
 /** 历史数据可能为空（早期用原生 SQL 插入，没写这一列），故允许 null。 */
 LocalDateTime createdAt,
 /** 文档数。 */
 int files,
 /** 片段数。 */
 int chunks,
 /** 该库单独的检索 Top-K；null = 跟随全局默认。 */
 Integer topK
 ) {
 public static KbCollectionOut from(KbCollection c, int files, int chunks) {
 return new KbCollectionOut(c.getId(), c.getName(), c.getCreatedAt(),
 files, chunks, c.getTopK());
 }
 }

 /** 新建知识库请求。对应 {@code KbCollectionCreate}（name 必填、1~100 字）。 */
 public record KbCollectionCreate(
 @Size(min = 1, max = 100, message = "知识库名称长度需在 1~100 之间")
 String name
 ) {
 }

 /**
 * 更新知识库请求。对应 {@code KbCollectionUpdate}（name 与 top_k 都可单独提交）。
 *
 * <p><b>为什么用 JsonNode 而不是这个 record 来接收 PATCH 体</b>：
 * 早期设计用 数据校验框架 的 {@code model_fields_set} 区分"没传"与"显式传了 null"。
 * 本模块的契约是：{@code top_k} 不传 = 不变、传 {@code null} = 清除回落默认、传数字 = 设定。
 * 但 Java record 的 {@code Integer topK} 对"没传"和"传 null"都得到 null，无法区分。
 * 所以在 {@code KbController} 里直接用 {@code @RequestBody JsonNode} 接收，
 * 由 {@code KbService.updateCollection} 判断 {@code has("top_k")} 来精确还原这套语义。
 * 这里保留一个 record 仅作文档/潜在用法参考，控制器实际不依赖它。
 */
 public record KbCollectionUpdate(
 @Size(max = 100, message = "知识库名称最长 100 个字符")
 String name,
 /** null = 清除单独设置、回退到跟随全局默认；数字为 1~20。 */
 Integer topK
 ) {
 }

 /** 某个知识库中的一份文档及其索引状态。对应 {@code KbDocument}。 */
 public record KbDocument(
 Long id,
 String filename,
 Long size,
 /** 容错：历史数据可能为空。 */
 LocalDateTime createdAt,
 /** 已建立的索引片段数（0 = 尚未索引）。 */
 int chunks,
 /** 所属知识库；null = 仅聊天附件，未归档。与 {@code FileItem.collectionId}(Long) 对齐。 */
 Long collectionId,
 /**
 * 索引状态：PENDING / INDEXING / READY / FAILED / SKIPPED。
 * <p>前端据此在文档列表里显示"索引中 / 已就绪 / 失败可重试"——
 * 上传已改为后台异步索引，没有这个字段前端就无从得知进度。
 */
 String indexStatus,
 /** 索引失败原因；未失败为 null。与 indexStatus=FAILED 配套展示。 */
 String indexError
 ) {
 public static KbDocument from(FileItem f, int chunkCount) {
 return new KbDocument(f.getId(), f.getFilename(), f.getSize(), f.getCreatedAt(),
 chunkCount, f.getCollectionId(), f.getIndexStatus(), f.getIndexError());
 }
 }

 /** 知识库图谱用：单个片段的序号与开头预览。对应 {@code KbChunkPreview}。 */
 public record KbChunkPreview(
 int index,
 String preview
 ) {
 }

 /** 图谱中的一个文档节点（含若干片段预览）。对应 {@code KbGraphDoc}。 */
 public record KbGraphDoc(
 Long id,
 String filename,
 /** 该文档的片段总数。 */
 int chunks,
 /** 预览片段（最多 40 条，避免一次拉爆前端）。 */
 List<KbChunkPreview> previews
 ) {
 }

 /** 知识库关系图数据：库 → 文档 → 片段。对应 {@code KbGraph}。 */
 public record KbGraph(
 KbCollectionOut collection,
 List<KbGraphDoc> documents
 ) {
 }

 /**
 * 知识库检索参数（GET 返回 / POST 接收）。对应 的
 * {@code get_rag_config} / {@code RagConfigUpdate}。
 *
 * <p>GET 返回 4 个字段（与早期设计完全一致）：{@code top_k} / {@code chunk_size} /
 * {@code chunk_overlap} / {@code embed_batch}。POST 只接收 {@code top_k}（1~20）。
 */
 public record RagConfig(
 int topK,
 int chunkSize,
 int chunkOverlap,
 int embedBatch
 ) {
 }

 /**
 * POST /rag-config 的请求体：只含 top_k。
 *
 * <p><b>刻意不加 {@code @Min/@Max} 范围注解</b>：早期设计是 {@code top_k: int}，
 * 数据校验框架 <b>没有任何范围约束</b>，越界值不会被框架拒绝（不会 422），
 * 而是在处理函数里 {@code k = max(1, min(20, int(top_k)))} <b>夹取</b>到 1~20。
 * 这里若写上范围注解却没有 {@code @Valid} 触发，就会变成"看起来有校验、实际不生效"
 * 的陷阱代码 —— 所以干脆不写，改由 {@code KbService.setRagConfig} 明确夹取。
 */
 public record RagConfigUpdate(
 int topK
 ) {
 }

 /**
 * POST /rag-config 的响应体：<b>只有 top_k 一个字段</b>。
 *
 * <p>早期设计是 {@code return {"top_k": k}}（不是四个字段），所以响应类型单独定义，
 * 不能复用 GET 用的 {@link RagConfig}——混用会多返回三个字段，属于契约走样。
 */
 public record TopKResult(
 int topK
 ) {
 }

 /** reindex-all 的结果：{@code {"indexed":n,"failed":n,"total":n}}。 */
 public record ReindexAllResult(
 int indexed,
 int failed,
 int total
 ) {
 }
}
