package com.ipas.assistant.dto;

import com.ipas.assistant.entity.FileItem;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件模块请求 / 响应体。对应 的
 * {@code FileOut} / {@code FileDetail} 与 的 {@code get_limits} 返回结构。
 *
 * <p>字段名用 camelCase，由 Jackson 的 SNAKE_CASE 策略序列化成 snake_case
 * （{@code max_mb} / {@code max_content_chars} / {@code all_exts} / {@code top_k} …）。
 */
public final class FileDtos {

 private FileDtos() {
 }

 /**
 * 文件列表项 / 上传成功返回。对应 {@code FileOut}。
 *
 * <p>带上了索引状态三件套：因为上传已改为"落库即返回、后台异步索引"，
 * 上传成功的响应里状态通常是 {@code PENDING}，前端需要据此显示"索引中 / 已就绪 / 失败"
 * 并决定是否轮询。多出字段对既有前端是无害的（多余字段会被忽略）。
 *
 * @param indexStatus PENDING / INDEXING / READY / FAILED / SKIPPED
 * @param chunkCount  已生成的索引片段数
 * @param indexError  索引失败原因；未失败为 null
 */
 public record FileOut(
 Long id,
 String filename,
 Long size,
 LocalDateTime createdAt,
 String indexStatus,
 Integer chunkCount,
 String indexError
 ) {
 public static FileOut from(FileItem f) {
 return new FileOut(f.getId(), f.getFilename(), f.getSize(), f.getCreatedAt(),
 f.getIndexStatus(), f.getChunkCount(), f.getIndexError());
 }
 }

 /**
 * 文档详情：在 {@link FileOut} 基础上带上抽取的正文，供前端在右侧面板中查看。
 * 对应 {@code FileDetail}（content 可能为 null，用空串兜底避免前端 JSON 里出现 null）。
 */
 public record FileDetail(
 Long id,
 String filename,
 Long size,
 LocalDateTime createdAt,
 String content
 ) {
 public static FileDetail from(FileItem f) {
 return new FileDetail(f.getId(), f.getFilename(), f.getSize(), f.getCreatedAt(),
 f.getContent() == null ? "" : f.getContent());
 }
 }

 /** 前端展示用文件类型分组（label + 扩展名清单）。对应 的 _TYPE_GROUPS。 */
 public record TypeGroup(
 String label,
 List<String> exts
 ) {
 }

 /**
 * GET /api/files/limits 的返回：上传限制说明，供前端展示（避免前后端各写一份导致不一致）。
 * 对应 的 {@code get_limits}。
 */
 public record FileLimits(
 int maxMb,
 int maxContentChars,
 List<TypeGroup> groups,
 List<String> allExts,
 int topK,
 int chunkSize,
 int chunkOverlap
 ) {
 }
}
