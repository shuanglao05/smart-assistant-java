package com.ipas.assistant.controller;

import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.config.AppVersion;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口。对应 里的 {@code @app.get("/api/health")}。
 *
 * <p>这个接口<b>不需要登录</b>（早期设计该路由没有声明 {@code Depends(get_current_user)}），
 * 用途是「部署后快速确认服务活着、版本对不对、当前连的哪个模型」。
 * 排查问题时第一句话通常是「先看看 /api/health」。
 *
 * <p>保留这个接口的另一个意义：早期实现与 本实现可以<b>用同一套检查方式</b>
 * 验证迁移是否成功 —— 两个版本并排跑在不同端口，各自访问 /api/health，
 * 对比 version 与 llm_model 字段是否一致。
 *
 * @param status 固定 "ok"
 * @param version 应用版本号（来自 VERSION 文件）
 * @param llmProvider 当前大模型供应方：ollama / cloud
 * @param llmModel 当前使用的具体模型名
 */
record HealthResponse(
 String status,
 String version,
 String llmProvider,
 String llmModel
) {
}

@RestController
@RequestMapping("/api")
public class HealthController {

 private final AppVersion appVersion;
 private final AppProperties properties;

 public HealthController(AppVersion appVersion, AppProperties properties) {
 this.appVersion = appVersion;
 this.properties = properties;
 }

 /**
 * GET /api/health —— 健康检查。
 *
 * <p>对应：
 * <pre>
 * return {
 * "status": "ok",
 * "version": VERSION,
 * "llm_provider": config.LLM_PROVIDER,
 * "llm_model": config.OLLAMA_MODEL if config.LLM_PROVIDER == "ollama"
 * else config.CLOUD_MODEL,
 * }
 * </pre>
 * 字段名经 Jackson 的 SNAKE_CASE 策略转成 {@code llm_provider} / {@code llm_model}，
 * 与早期设计输出完全一致。
 */
 @GetMapping("/health")
 public ResponseEntity<HealthResponse> health() {
 String provider = properties.llm().provider();
 // 与早期设计同样的三元判断：本地取 OLLAMA_MODEL，云端取 CLOUD_MODEL
 String model = "ollama".equalsIgnoreCase(provider)
 ? properties.llm().ollama().model()
 : properties.llm().cloud().model();

 return ResponseEntity.ok(new HealthResponse("ok", appVersion.get(), provider, model));
 }
}
