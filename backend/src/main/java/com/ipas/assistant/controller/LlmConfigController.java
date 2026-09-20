package com.ipas.assistant.controller;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.dto.LlmConfigDtos;
import com.ipas.assistant.dto.LlmOptionDtos;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.LlmConfigService;
import com.ipas.assistant.service.RuntimeSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 云端模型配置接口。对应 的
 * {@code APIRouter(prefix="/api/llm-config")}。
 *
 * <p><b>路由声明顺序说明</b>：{@code /models/fetch}、{@code /local-models}、
 * {@code /context-window} 都是字面量路径，与其它接口不冲突。
 * Spring 的路径匹配会优先选择更具体的字面量路径，
 * 不需要像某些框架那样靠声明顺序来避免误匹配。
 */
@RestController
@RequestMapping("/api/llm-config")
public class LlmConfigController {

 private final LlmConfigService configService;
 private final RuntimeSettingsService settingsService;

 public LlmConfigController(LlmConfigService configService,
 RuntimeSettingsService settingsService) {
 this.configService = configService;
 this.settingsService = settingsService;
 }

 /** GET /api/llm-config —— 当前云端配置（不含 Key），供设置页预填表单。 */
 @GetMapping
 public ResponseEntity<LlmConfigDtos.ConfigResponse> getConfig(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(configService.getConfig(me.id()));
 }

 /**
 * POST /api/llm-config —— 保存云端配置（先测连、通过才落库）。
 *
 * <p>返回更新后的完整模型清单，让前端保存后立刻刷新下拉框。
 */
 @PostMapping
 public ResponseEntity<List<LlmOptionDtos.LlmOption>> configure(
 @AuthenticationPrincipal AuthUser me,
 @RequestBody LlmConfigDtos.ConfigRequest payload) {
 return ResponseEntity.ok(configService.saveConfig(me.id(), payload));
 }

 /**
 * POST /api/llm-config/test —— 连通性检查。
 *
 * <p><b>恒返回 200</b>：失败原因放在响应体的 {@code message} 里，
 * 前端直接展示在提示条上。
 */
 @PostMapping("/test")
 public ResponseEntity<LlmConfigDtos.TestResponse> test(
 @AuthenticationPrincipal AuthUser me,
 @RequestBody LlmConfigDtos.TestRequest payload) {
 return ResponseEntity.ok(configService.test(me.id(), payload));
 }

 /**
 * POST /api/llm-config/detect-proxy —— 扫描本机可用代理。
 *
 * <p>可能耗时几秒（串行探测监听端口），前端按钮上要有加载态。
 */
 @PostMapping("/detect-proxy")
 public ResponseEntity<LlmConfigDtos.DetectProxyResponse> detectProxy(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(configService.detectProxy(me.id()));
 }

 /**
 * GET /api/llm-config/models —— 【已废弃，保留兼容】按已保存配置拉模型列表。
 *
 * <p>早期设计注释说明了它为什么废弃：多 API 接入后，拉取模型列表必须传
 * 「当前表单的 base_url + key」，否则永远拉到已保存那个平台的模型。
 * 保留这个接口是为了不让老版本前端直接 404。
 */
 @GetMapping("/models")
 public ResponseEntity<LlmConfigDtos.ModelsResponse> listRemoteModels(
 @AuthenticationPrincipal AuthUser me) {
 RuntimeSettingsService.LlmSettings s = settingsService.load(me.id());
 if (s.apiKey().isBlank() || s.baseUrl().isBlank()) {
 throw ApiException.badRequest("请先配置云端 Key 与 Base URL");
 }
 return ResponseEntity.ok(configService.fetchModels(
 me.id(), new LlmConfigDtos.ModelsFetchRequest(s.baseUrl(), s.apiKey())));
 }

 /** POST /api/llm-config/models/fetch —— 按表单里填的 base_url + key 拉模型列表。 */
 @PostMapping("/models/fetch")
 public ResponseEntity<LlmConfigDtos.ModelsResponse> fetchRemoteModels(
 @AuthenticationPrincipal AuthUser me,
 @RequestBody LlmConfigDtos.ModelsFetchRequest payload) {
 return ResponseEntity.ok(configService.fetchModels(me.id(), payload));
 }

 /**
 * POST /api/llm-config/models —— 仅更新可选模型清单。
 *
 * <p>独立于保存配置：整理下拉列表时不该被要求再填一次 Key 并重测连。
 */
 @PostMapping("/models")
 public ResponseEntity<List<LlmOptionDtos.LlmOption>> saveModels(
 @AuthenticationPrincipal AuthUser me,
 @RequestBody LlmConfigDtos.ModelsUpdateRequest payload) {
 return ResponseEntity.ok(configService.saveModels(me.id(), payload));
 }

 /** GET /api/llm-config/local-models —— 列出本机 Ollama 已安装的聊天模型。 */
 @GetMapping("/local-models")
 public ResponseEntity<LlmConfigDtos.LocalModelsResponse> localModels(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(configService.localModels(me.id()));
 }

 /**
 * DELETE /api/llm-config/local-models —— 删除本机某个 Ollama 模型。
 *
 * <p>⚠️ <b>破坏性且不可撤销</b>：Ollama 删除即物理删除模型文件，
 * 用户要重新下载（几 GB）。前端有二次确认，后端只做校验与转发。
 *
 * <p>注意这个 DELETE 需要请求体（模型名），前端用
 * axios 的 {@code delete(url, { data })} 提交 —— 这里用
 * {@code @RequestBody} 接收即可，Spring 对此没有限制。
 */
 @DeleteMapping("/local-models")
 public ResponseEntity<Map<String, Object>> deleteLocalModel(
 @AuthenticationPrincipal AuthUser me,
 @RequestBody LlmConfigDtos.LocalModelDeleteRequest payload) {
 return ResponseEntity.ok(configService.deleteLocalModel(me.id(), payload));
 }

 /** GET /api/llm-config/context-window —— 读取本地 Ollama 的 num_ctx。 */
 @GetMapping("/context-window")
 public ResponseEntity<LlmConfigDtos.ContextWindowResponse> getContextWindow(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(configService.getContextWindow(me.id()));
 }

 /** POST /api/llm-config/context-window —— 修改 num_ctx（下次对话即生效）。 */
 @PostMapping("/context-window")
 public ResponseEntity<LlmConfigDtos.ContextWindowUpdateResponse> setContextWindow(
 @AuthenticationPrincipal AuthUser me,
 @RequestBody LlmConfigDtos.ContextWindowUpdateRequest payload) {
 return ResponseEntity.ok(configService.setContextWindow(me.id(), payload));
 }
}
