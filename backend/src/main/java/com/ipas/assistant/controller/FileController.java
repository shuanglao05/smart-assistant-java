package com.ipas.assistant.controller;

import com.ipas.assistant.dto.FileDtos;
import com.ipas.assistant.dto.StatusResponse;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.FileService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件上传 / 管理接口。对应 的 {@code APIRouter(prefix="/api/files")}。
 *
 * <p>上传成功后即建立 RAG 索引（见 {@link FileService}），所以文件一旦传上来，
 * 对话时勾选对应知识库就能检索到。</p>
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

 private final FileService fileService;

 public FileController(FileService fileService) {
 this.fileService = fileService;
 }

 /** GET /api/files/limits —— 上传限制说明，供前端展示（避免前后端各写一份导致不一致）。 */
 @GetMapping("/limits")
 public ResponseEntity<FileDtos.FileLimits> getLimits(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(fileService.getLimits());
 }

 /**
 * POST /api/files —— 上传文件（multipart，表单字段 file + 可选 collection_id）。
 *
 * <p>图片不抽正文不进库；文本/代码/markdown/.docx 上传即建索引；PDF 当前无解析库不进索引。
 * 索引失败不影响上传本身（详见 {@link FileService}）。
 */
 @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
 public ResponseEntity<FileDtos.FileOut> upload(
 @AuthenticationPrincipal AuthUser me,
 @RequestParam("file") MultipartFile file,
 @RequestParam(value = "collection_id", required = false) Long collectionId) {
 return ResponseEntity.ok(FileDtos.FileOut.from(
 fileService.upload(me.id(), file, collectionId)));
 }

 /** GET /api/files —— 当前用户的文件列表（按时间倒序，最多 50 条）。 */
 @GetMapping
 public ResponseEntity<List<FileDtos.FileOut>> list(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(fileService.list(me.id()));
 }

 /** GET /api/files/{id} —— 文档详情（含抽取正文），供前端右侧面板查看。 */
 @GetMapping("/{id}")
 public ResponseEntity<FileDtos.FileDetail> get(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long id) {
 return ResponseEntity.ok(fileService.get(me.id(), id));
 }

 /** POST /api/files/{id}/reindex —— 对已上传文档重建知识库索引。 */
 @PostMapping("/{id}/reindex")
 public ResponseEntity<FileDtos.FileOut> reindex(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long id) {
 return ResponseEntity.ok(FileDtos.FileOut.from(fileService.reindex(me.id(), id)));
 }

 /** DELETE /api/files/{id} —— 删除文档（清理片段 + 磁盘文件）。 */
 @DeleteMapping("/{id}")
 public ResponseEntity<StatusResponse> delete(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long id) {
 fileService.delete(me.id(), id);
 return ResponseEntity.ok(StatusResponse.deleted());
 }
}
