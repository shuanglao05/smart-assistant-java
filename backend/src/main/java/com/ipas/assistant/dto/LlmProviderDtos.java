package com.ipas.assistant.dto;

import com.ipas.assistant.entity.LlmProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 云端模型接入（llm_providers）的请求 / 响应体。
 * 对应 的
 * {@code LlmProviderCreate} / {@code LlmProviderUpdate} / {@code LlmProviderOut}。
 */
public final class LlmProviderDtos {

 private LlmProviderDtos() {
 }

 /**
 * 接入新平台。对应 {@code LlmProviderCreate}，各字段长度上限与原文一致。
 *
 * <p><b>注意 {@code models} 在请求里是数组、在库里是逗号分隔的字符串</b> ——
 * 这个转换在 Service 层做（{@code RuntimeSettingsService.joinModelList}）。
 * 之所以库里不存 JSON 数组：与早期设计的数据形态保持一致，
 * 迁移时已有数据可以直接搬，不需要清洗。
 */
 public record Create(
 @NotBlank(message = "名称不能为空")
 @Size(max = 50, message = "名称最长 50 个字符")
 String name,

 @NotBlank(message = "API 地址不能为空")
 @Size(max = 300, message = "API 地址最长 300 个字符")
 String baseUrl,

 @NotBlank(message = "API Key 不能为空")
 @Size(max = 300, message = "API Key 最长 300 个字符")
 String apiKey,

 @NotBlank(message = "默认模型不能为空")
 @Size(max = 120, message = "模型名最长 120 个字符")
 String model,

 /** 可选模型清单；允许为空（此时前端只用默认模型）。 */
 List<String> models
 ) {
 }

 /**
 * 编辑已接入的平台。
 *
 * <p><b>{@code apiKey} 留空表示"保持原 Key 不变"</b> —— 这是早期设计的既定语义，
 * 前端编辑时不回显明文 Key（只显示掩码），所以用户不动它就提交空串。
 * Service 里必须实现这个回落，否则一编辑就把 Key 清空了。
 */
 public record Update(
 @Size(max = 50, message = "名称最长 50 个字符")
 String name,

 @Size(max = 300, message = "API 地址最长 300 个字符")
 String baseUrl,

 String apiKey,

 @Size(max = 120, message = "模型名最长 120 个字符")
 String model,

 List<String> models
 ) {
 }

 /**
 * 已接入平台的响应（Key 脱敏）。
 *
 * <p><b>绝不返回明文 Key</b>：只回传 {@code maskedKey}（形如 {@code abcd...wxyz}）。
 * 这是安全边界，不是可选项 —— 明文 Key 一旦进了响应体，
 * 就会出现在浏览器开发者工具、日志、以及任何抓包里。
 */
 public record Out(
 Long id,
 String name,
 String baseUrl,
 String model,
 List<String> models,
 String maskedKey,
 LocalDateTime createdAt
 ) {
 /** 从实体转换；{@code maskedKey} 由调用方传入（脱敏逻辑在 Service 里）。 */
 public static Out from(LlmProvider e, List<String> models, String maskedKey) {
 return new Out(e.getId(), e.getName(), e.getBaseUrl(), e.getModel(),
 models, maskedKey, e.getCreatedAt());
 }
 }

 /**
 * 连通性测试结果。
 *
 * <p>对应 {@code /api/llm-providers/{id}/test} 返回的 {@code {ok, message}}。
 * 注意这个接口<b>恒返回 200</b>，失败信息放在 {@code message} 里 ——
 * 这样前端可以直接把 message 显示在卡片上，而不用区分"请求失败"和"测试失败"。
 */
 public record TestResult(boolean ok, String message) {
 }
}
