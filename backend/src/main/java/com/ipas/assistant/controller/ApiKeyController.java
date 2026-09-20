package com.ipas.assistant.controller;

import com.ipas.assistant.dto.ApiKeyDtos;
import com.ipas.assistant.dto.LlmOptionDtos;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.ApiKeyService;
import com.ipas.assistant.service.LlmOptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「API 管理」与「可选模型清单」两个小接口。
 *
 * <p>它们路径不共享前缀（{@code /api/api-keys} 与 {@code /api/llm-options}），
 * 所以不能挂在同一个类级 {@code @RequestMapping} 下，只能各自写完整路径。
 *
 * <p>早期设计里 {@code /api/llm-options} 是直接写在 里的，
 * 这里单独成控制器 —— 它的逻辑（拼装三部分模型清单）已经被抽到
 * {@link LlmOptionService}，控制器只负责转发。
 */
@RestController
public class ApiKeyController {

 private final ApiKeyService apiKeyService;
 private final LlmOptionService optionService;

 public ApiKeyController(ApiKeyService apiKeyService, LlmOptionService optionService) {
 this.apiKeyService = apiKeyService;
 this.optionService = optionService;
 }

 /**
 * GET /api/api-keys —— 当前云端 API 配置（Key 脱敏）+ 厂商用量页链接。
 *
 * <p>只读：修改入口在 {@code POST /api/llm-config}（与早期设计一致）。
 */
 @GetMapping("/api/api-keys")
 public ResponseEntity<ApiKeyDtos.ApiKeyInfo> apiKeys(@AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(apiKeyService.getInfo(me.id()));
 }

 /**
 * GET /api/llm-options —— 可选模型清单，供前端「切换模型」下拉使用。
 *
 * <p>由三部分拼成：本地 Ollama 全部模型 + 默认云端配置的模型 +
 * 每个已接入平台的模型。拼装规则见 {@link LlmOptionService}。
 *
 * <p>注意本接口会尝试连本机 Ollama（5 秒超时）来枚举已装模型；
 * Ollama 没启动会静默回落到配置里的单个模型，不会报错 ——
 * 因为前端打开模型下拉就会调它，不能因为它没开就整个坏掉。
 */
 @GetMapping("/api/llm-options")
 public ResponseEntity<List<LlmOptionDtos.LlmOption>> llmOptions(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(optionService.buildOptions(me.id()));
 }
}
