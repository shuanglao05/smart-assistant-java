package com.ipas.assistant.dto;

import java.util.List;

/**
 * llm-config 模块的请求 / 响应体。
 *
 * <p>接口对应 。注意这些 DTO 在原文里是直接定义在
 * 路由文件里的内联 {@code BaseModel}（{@code LlmConfigRequest} / {@code TestRequest} /
 * {@code ModelsFetchRequest} / {@code ModelsUpdate} / {@code LocalModelDelete} /
 * {@code ContextWindowUpdate}），这里统一归到本文件。
 */
public final class LlmConfigDtos {

 private LlmConfigDtos() {
 }

 /** 上下文窗口的预设档位（原 {@code CONTEXT_WINDOW_PRESETS}，供前端做快捷选择）。 */
 public static final List<Integer> CONTEXT_WINDOW_PRESETS =
 List.of(2048, 4096, 8192, 16384, 32768, 65536, 131072);

 /** 上下文窗口最小值（原 {@code CONTEXT_WINDOW_MIN}）。 */
 public static final int CONTEXT_WINDOW_MIN = 512;

 /** 上下文窗口最大值（原 {@code CONTEXT_WINDOW_MAX}）。 */
 public static final int CONTEXT_WINDOW_MAX = 131072;

 /**
 * 保存云端配置的请求。对应 {@code LlmConfigRequest}。
 *
 * <p><b>所有字段都可选，语义各不相同，必须逐个对齐</b>：
 * <ul>
 * <li>{@code cloudApiKey} —— 空串表示<b>沿用已保存的 Key</b>（此时仍会做真实测连）；</li>
 * <li>{@code cloudBaseUrl} —— 空/null 表示<b>保持当前不变</b>
 * （仅当当前也没配过时才回落到 OpenAI 官方地址）；</li>
 * <li>{@code cloudModel} —— 空表示不变；</li>
 * <li>{@code cloudModels} —— <b>null 表示不改</b>，非 null 才更新；
 * 传空数组等于"不改"（原文里 {@code if models:} 才赋值）；</li>
 * <li>{@code enableThinking} / {@code thinkingBudget} / {@code trustEnv} /
 * {@code proxyUrl} —— null 表示不改（三态语义，所以必须用包装类型）。</li>
 * </ul>
 */
 public record ConfigRequest(
 String cloudApiKey,
 String cloudBaseUrl,
 String cloudModel,
 List<String> cloudModels,
 Boolean enableThinking,
 Integer thinkingBudget,
 Boolean trustEnv,
 String proxyUrl
 ) {
 }

 /**
 * 当前云端配置（不含 Key）。对应 {@code GET /api/llm-config}。
 *
 * <p>回传它主要是为了设置页能预填表单 —— 否则用户只改一项时，
 * 没被预填的项会以空值提交，把已有配置冲掉。
 *
 * <p>{@code supportsThinking} / {@code thinkingHint} 由
 * {@code ModelCatalog.classifyThinking} 推断，用于界面提示
 * "这个模型开深度思考有没有意义"。
 */
 public record ConfigResponse(
 String cloudBaseUrl,
 String cloudModel,
 List<String> cloudModels,
 boolean enableThinking,
 int thinkingBudget,
 boolean supportsThinking,
 String thinkingHint,
 boolean trustEnv,
 String proxyUrl,
 String envProxy,
 String effectiveProxy
 ) {
 }

 /** 连通性测试请求。对应 {@code TestRequest}；缺省项回落到已保存配置。 */
 public record TestRequest(
 String cloudApiKey,
 String cloudBaseUrl,
 String cloudModel
 ) {
 }

 /**
 * 连通性测试结果。对应 {@code POST /api/llm-config/test} 的返回。
 *
 * <p><b>这个接口恒返回 200</b>，用 {@code ok} 字段表达成败 ——
 * 与 {@code /api/llm-providers/{id}/test} 一致。理由：前端把失败原因
 * 直接显示在设置页的提示条上，不该再额外区分"请求出错"和"测试不通过"。
 */
 public record TestResponse(
 boolean ok,
 String message,
 boolean thinkingSupported,
 String thinkingHint
 ) {
 }

 /** 按表单值拉取模型列表。对应 {@code ModelsFetchRequest}。 */
 public record ModelsFetchRequest(String baseUrl, String apiKey) {
 }

 /** 模型列表响应：{@code {"models": [...]}}。 */
 public record ModelsResponse(List<String> models) {
 }

 /** 仅更新可选模型清单（不重测 Key）。对应 {@code ModelsUpdate}。 */
 public record ModelsUpdateRequest(List<String> cloudModels) {
 }

 /**
 * 代理探测结果。对应 {@code POST /api/llm-config/detect-proxy}。
 *
 * @param candidates 探测到的可用代理地址候选（形如 http://127.0.0.1:7890）
 * @param target 探测目标（host:port，从云端 base_url 解析）
 * @param current 设置里<TT>显式填写</TT>的代理（不是生效值）
 * @param envProxy 环境变量里的代理（进程启动时继承，改系统代理不会更新）
 * @param effective 当前实际生效的代理（空串 = 直连）
 */
 public record DetectProxyResponse(
 List<String> candidates,
 String target,
 String current,
 String envProxy,
 String effective
 ) {
 }

 /** 本机 Ollama 模型列表。对应 {@code GET /api/llm-config/local-models}。 */
 public record LocalModelsResponse(
 List<String> models,
 String baseUrl,
 /** 当前选中的本地模型名。 */
 String current
 ) {
 }

 /**
 * 删除本机 Ollama 模型的请求体。
 *
 * <p>注意这是 <b>DELETE 请求携带 JSON body</b> —— 不符合 HTTP 的常见习惯，
 * 但前端就是这么调的（axios 的 {@code delete(url, { data })}），
 * 且 Ollama 的删除接口本身也要求 body 传模型名。
 * Spring 的 {@code @DeleteMapping} 默认支持带 {@code @RequestBody}，无需特殊处理。
 */
 public record LocalModelDeleteRequest(String name) {
 }

 /**
 * 上下文窗口读取结果。
 *
 * <p><b>「上下文窗口」在这个系统里只指本地 Ollama 的 num_ctx</b>，
 * 这一点很容易混淆，所以响应里带一句 {@code note} 说明（原文也这么做）：
 * <ul>
 * <li>本地 Ollama —— {@code num_ctx} 是唯一真正能控制的窗口，
 * 它决定"模型能看到多长的对话历史 + 提示词"；</li>
 * <li>云端 —— 上下文长度由<b>平台 + 具体模型</b>决定，请求里没有对应参数可调。
 * 云端唯一能控的是输出上限 max_tokens，本项目未开放。</li>
 * </ul>
 */
 public record ContextWindowResponse(
 int ollamaNumCtx,
 List<Integer> presets,
 int min,
 int max,
 String note
 ) {
 public static ContextWindowResponse of(int current) {
 return new ContextWindowResponse(
 current,
 CONTEXT_WINDOW_PRESETS,
 CONTEXT_WINDOW_MIN,
 CONTEXT_WINDOW_MAX,
 "仅对【本地 Ollama】生效（num_ctx）。云端模型的上下文长度由平台决定，不可调。");
 }
 }

 /** 修改上下文窗口。对应 {@code ContextWindowUpdate}。 */
 public record ContextWindowUpdateRequest(Integer ollamaNumCtx) {
 }

 /** 修改上下文窗口的结果。 */
 public record ContextWindowUpdateResponse(boolean ok, int ollamaNumCtx) {
 }
}
