package com.ipas.assistant.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ipas.assistant.dto.KbDtos;
import com.ipas.assistant.dto.StatusResponse;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.FileService;
import com.ipas.assistant.service.KbService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库（多库）接口。对应 的 {@code APIRouter(prefix="/api/kb")}。
 *
 * <p>前端「知识库」页用它管理多个库、查看文档列表与关系图、调整检索参数。</p>
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

 private final KbService kbService;
 private final FileService fileService;

 public KbController(KbService kbService, FileService fileService) {
 this.kbService = kbService;
 this.fileService = fileService;
 }

 /** GET /api/kb/collections —— 列出当前用户的全部知识库及其文档数 / 片段数。 */
 @GetMapping("/collections")
 public ResponseEntity<List<KbDtos.KbCollectionOut>> listCollections(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(kbService.listCollections(me.id()));
 }

 /**
 * POST /api/kb/collections —— 新建知识库。
 *
 * <p><b>为什么必须写 {@code @Valid}</b>：早期设计 {@code KbCollectionCreate} 是
 * 数据校验框架 模型 {@code name: str = Field(min_length=1, max_length=100)}，
 * 空名称会由框架直接返回 <b>422</b>。Java 侧只有在 {@code @RequestBody} 上
 * 加 {@code @Valid} 时，{@code KbCollectionCreate} 里的 {@code @Size} 才会生效并
 * 由 {@code GlobalExceptionHandler} 转成 422；
 * <b>漏掉它就会静默放行到业务层、变成 400「知识库名称不能为空」</b>，与原契约不符。
 */
 @PostMapping("/collections")
 public ResponseEntity<KbDtos.KbCollectionOut> createCollection(
 @AuthenticationPrincipal AuthUser me,
 @Valid @RequestBody KbDtos.KbCollectionCreate payload) {
 return ResponseEntity.ok(kbService.createCollection(me.id(), payload.name()));
 }

 /**
 * PATCH /api/kb/collections/{cid} —— 改名 / 改 Top-K（都可单独提交）。
 *
 * <p>用 {@code JsonNode} 接收而非强类型 DTO，是为了精确区分"没传 top_k"与"传了 null"：
 * 前者 = 不变，后者 = 清除单独设置回落默认。详见 {@link KbService#updateCollection}。
 */
 @PatchMapping("/collections/{cid}")
 public ResponseEntity<KbDtos.KbCollectionOut> updateCollection(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long cid,
 @RequestBody JsonNode patch) {
 return ResponseEntity.ok(kbService.updateCollection(me.id(), cid, patch));
 }

 /** DELETE /api/kb/collections/{cid} —— 删除知识库（连同文档 + 磁盘文件 + 片段）。 */
 @DeleteMapping("/collections/{cid}")
 public ResponseEntity<StatusResponse> deleteCollection(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long cid) {
 kbService.deleteCollection(me.id(), cid);
 return ResponseEntity.ok(StatusResponse.deleted());
 }

 /**
 * GET /api/kb/documents —— 文档列表；给了 collection_id 只列该库，否则全部。
 */
 @GetMapping("/documents")
 public ResponseEntity<List<KbDtos.KbDocument>> listDocuments(
 @AuthenticationPrincipal AuthUser me,
 @RequestParam(value = "collection_id", required = false) Long collectionId) {
 return ResponseEntity.ok(kbService.listDocuments(me.id(), collectionId));
 }

 /**
 * GET /api/kb/graph —— 某知识库的「库 → 文档 → 片段」关系图（片段只带开头预览）。
 *
 * <p>collection_id 为必填查询参数；缺失由框架返回 422（与早期设计 Query(required) 一致）。
 */
 @GetMapping("/graph")
 public ResponseEntity<KbDtos.KbGraph> graph(
 @AuthenticationPrincipal AuthUser me,
 @RequestParam Long collectionId) {
 return ResponseEntity.ok(kbService.graph(me.id(), collectionId));
 }

 /** POST /api/kb/reindex-all —— 对当前用户所有文档重建索引。 */
 @PostMapping("/reindex-all")
 public ResponseEntity<KbDtos.ReindexAllResult> reindexAll(
 @AuthenticationPrincipal AuthUser me) {
 // 「重建全部」改走 FileService：只有它同时具备"读磁盘 + 抽正文"的能力。
 // 必须从磁盘重抽 —— 不能用库里那份可能已坏掉的 content（否则老文件永远重建不出来），
 // 详见 FileService#reindexAll 的注释。
 return ResponseEntity.ok(fileService.reindexAll(me.id()));
 }

 /** GET /api/kb/rag-config —— 当前检索参数（Top-K / 切片大小 / 重叠 / 批大小）。 */
 @GetMapping("/rag-config")
 public ResponseEntity<KbDtos.RagConfig> getRagConfig(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(kbService.getRagConfig());
 }

 /**
 * POST /api/kb/rag-config —— 调整全局默认检索片段数（Top-K），立即生效并落库。
 *
 * <p>响应只回 {@code {"top_k": k}}（与早期设计 {@code return {"top_k": k}} 一致），
 * 不回传 GET 那四个字段。越界值按早期设计语义<b>夹取到 1~20</b>，不报错。
 */
 @PostMapping("/rag-config")
 public ResponseEntity<KbDtos.TopKResult> setRagConfig(
 @AuthenticationPrincipal AuthUser me,
 @RequestBody KbDtos.RagConfigUpdate payload) {
 return ResponseEntity.ok(kbService.setRagConfig(me.id(), payload.topK()));
 }
}
