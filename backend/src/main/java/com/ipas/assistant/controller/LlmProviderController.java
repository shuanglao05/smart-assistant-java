package com.ipas.assistant.controller;

import com.ipas.assistant.dto.LlmProviderDtos;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.LlmProviderService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 多平台模型接入接口。对应 的
 * {@code APIRouter(prefix="/api/llm-providers")}。
 */
@RestController
@RequestMapping("/api/llm-providers")
public class LlmProviderController {

 private final LlmProviderService providerService;

 public LlmProviderController(LlmProviderService providerService) {
 this.providerService = providerService;
 }

 /** GET /api/llm-providers —— 已接入平台列表（Key 脱敏）。 */
 @GetMapping
 public ResponseEntity<List<LlmProviderDtos.Out>> list(@AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(providerService.list(me.id()));
 }

 /**
 * POST /api/llm-providers —— 接入新平台。
 *
 * <p>会先真实测连，不通过返回 400 且<b>不入库</b> ——
 * 避免"保存成功、对话才报错"。
 */
 @PostMapping
 public ResponseEntity<LlmProviderDtos.Out> create(
 @AuthenticationPrincipal AuthUser me,
 @Valid @RequestBody LlmProviderDtos.Create payload) {
 return ResponseEntity.ok(providerService.create(me.id(), payload));
 }

 /**
 * PATCH /api/llm-providers/{pid} —— 编辑接入。
 *
 * <p>{@code api_key} 留空 = 保持原 Key 不变（前端不回显明文 Key，
 * 用户不动它就提交空串）。
 */
 @PatchMapping("/{pid}")
 public ResponseEntity<LlmProviderDtos.Out> update(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long pid,
 @RequestBody LlmProviderDtos.Update payload) {
 return ResponseEntity.ok(providerService.update(me.id(), pid, payload));
 }

 /**
 * DELETE /api/llm-providers/{pid} —— 删除接入。
 *
 * <p>返回 {@code {"ok": true}}（注意与其它模块的
 * {@code {"status": "deleted"}} 形状不同 —— 这是原文的既有差异，
 * 前端也是按 {@code ok} 判断的，不要"顺手统一"）。
 */
 @DeleteMapping("/{pid}")
 public ResponseEntity<Map<String, Object>> delete(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long pid) {
 providerService.delete(me.id(), pid);
 return ResponseEntity.ok(Map.of("ok", true));
 }

 /**
 * POST /api/llm-providers/{pid}/test —— 测试单个接入的连通性。
 *
 * <p>恒返回 200，结果在 {@code ok}/{@code message} 里。
 */
 @PostMapping("/{pid}/test")
 public ResponseEntity<LlmProviderDtos.TestResult> test(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long pid) {
 return ResponseEntity.ok(providerService.test(me.id(), pid));
 }
}
