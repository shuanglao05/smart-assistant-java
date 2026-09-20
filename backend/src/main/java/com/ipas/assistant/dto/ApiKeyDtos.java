package com.ipas.assistant.dto;

/**
 * 「API 管理」面板的展示数据。对应 的 {@code ApiKeyInfo}
 * 与 。
 *
 * <p>这块在早期设计里是<b>只读展示</b>：显示当前云端配置（Key 脱敏）+
 * 一个跳转到厂商官网用量页的链接。原注释说明「为避免 agent_manager 大重构，
 * 这里只做展示 + 跳转 + 修改入口」—— 修改入口走的是 {@code /api/llm-config}。
 *
 * <p><b>本 本实现把它从"全局共享"改成了"按用户"</b>：早期设计注释里
 * 明确写着「当前云端 API 是全局共享配置（.env + config 模块），所有用户共用」，
 * 而落库方案天然按 {@code user_id} 隔离，这个接口也就自然变成读当前用户自己的配置。
 */
public final class ApiKeyDtos {

 private ApiKeyDtos() {
 }

 /**
 * 当前云端 API 配置（Key 脱敏）。
 *
 * @param provider 厂商显示名，如「阿里云百炼 (DashScope)」
 * @param baseUrl 接入地址
 * @param model 当前模型名
 * @param maskedKey 脱敏后的 Key，形如 {@code sk-abc...wxyz}
 * @param usageUrl 该厂商的用量监控页（前端做「查看用量」跳转）
 * @param configured 是否已配置（Key 与 Base URL 都非空）
 */
 public record ApiKeyInfo(
 String provider,
 String baseUrl,
 String model,
 String maskedKey,
 String usageUrl,
 boolean configured
 ) {
 }
}
