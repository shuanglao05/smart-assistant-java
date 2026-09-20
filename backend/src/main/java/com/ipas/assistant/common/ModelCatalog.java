package com.ipas.assistant.common;

import java.util.List;

/**
 * 模型元信息推断。对应 里的
 * {@code supports_thinking()} / {@code classify_thinking()} / {@code classify_platform()} /
 * {@code context_window_of()} / {@code format_context_window()}。
 *
 * <p>这些都只是<b>"按名字和域名猜"</b>的启发式规则，不涉及任何网络调用 ——
 * 平台没有统一接口可以查询「这个模型支持什么、上下文多长」，所以只能靠名字匹配。
 * 猜错的后果也仅限于界面上的一行提示文案不准，不影响功能，因此值得用简单规则代替复杂度。
 *
 * <p>做成静态工具类而不是 Spring Bean：它没有任何状态和依赖，
 * 每次注入一个空壳 Bean 反而啰嗦。
 */
public final class ModelCatalog {

 private ModelCatalog() {
 }

 /**
 * 模型名 → 上下文窗口（token）的参考表。
 *
 * <p>匹配规则是「包含即命中」，<b>顺序有讲究</b>：更具体的 key 必须排在前面。
 * 例如 {@code qwen-flash}（100 万）要排在 {@code qwen}（131072）之前，
 * 否则 {@code qwen-flash} 会先命中 {@code qwen} 拿到错的窗口大小。
 * 早期设计的表也是这个顺序，实现时必须原样保留。
 *
 * <p>同一模型在不同平台/版本可能略有差异，这里的值仅作界面提示。
 */
 private static final List<String[]> MODEL_CONTEXT_WINDOWS = List.of(
 // 阿里云百炼 / Qwen 系
 new String[]{"qwen-flash", "1000000"},
 new String[]{"qwen3-max", "262144"},
 new String[]{"qwen3-coder", "262144"},
 new String[]{"qwen3-vl", "131072"},
 new String[]{"qwen-max", "131072"},
 new String[]{"qwen-plus", "131072"},
 new String[]{"qwen3", "131072"},
 new String[]{"qwen", "131072"},
 // 智谱 GLM
 new String[]{"glm-5", "131072"},
 new String[]{"glm-4.6", "200000"},
 new String[]{"glm-4", "131072"},
 // DeepSeek
 new String[]{"deepseek", "131072"},
 // Kimi / Moonshot
 new String[]{"kimi", "262144"},
 new String[]{"moonshot", "131072"});

 /**
 * 判断该端点是否属于「阿里云百炼（DashScope）」，从而支持
 * {@code enable_thinking} / {@code thinking_budget} 这两个百炼专有参数。
 *
 * <p><b>⚠️ 注意区分两个概念</b>：本方法判断的是「能否透传这两个<b>参数</b>」，
 * 而<b>不是</b>「这个模型有没有深度思考能力」。很多平台都支持推理，
 * 但开启方式不同（智谱走 thinking 模型、DeepSeek 走 reasoner 模型）。
 * 界面提示要用 {@link #classifyThinking}，别用本方法。
 *
 * <p><b>必须按域名主体判断，不能只看有没有 "dashscope" 字样</b>：
 * 百炼的应用专属域名形如
 * {@code https://ws-xxxx.cn-beijing.maas.aliyuncs.com/compatible-mode/v1}
 * —— 里面根本没有 "dashscope"。早期设计早期用
 * {@code "dashscope" in base_url} 判断，导致这个参数一次都没发出去，
 * 界面上的"深度思考"开关形同虚设。
 */
 public static boolean supportsThinking(String baseUrl) {
 String u = baseUrl == null ? "" : baseUrl.toLowerCase();
 return u.contains("aliyuncs.com") || u.contains("dashscope");
 }

 /**
 * 归一化 OpenAI 兼容端点的前缀：去掉结尾多余的 {@code /}，并统一剥掉结尾的 {@code /v1}。
 *
 * <h2>为什么必须有它（否则"能保存、一聊天就报错"）</h2>
 *
 * <p>各家 OpenAI 兼容端点有两种写法，用户两种都会填：
 * <pre>
 * https://dashscope.aliyuncs.com/compatible-mode/v1 ← 带 /v1（官方文档常见、界面占位符也是它）
 * https://api.deepseek.com ← 不带 /v1
 * </pre>
 *
 * <p>而两侧的代码对"base_url 是否含 /v1"的预期本来不一致：
 * <ul>
 * <li>测连（{@code LlmConnectivityService}）自己拼 {@code /chat/completions}，
 * 等于<b>要求带 /v1</b>；</li>
 * <li>聊天（{@code ChatModelFactory} 走 Spring AI）用的是 OpenAiApi 的默认路径
 * {@code /v1/chat/completions}，等于<b>要求不带 /v1</b>。</li>
 * </ul>
 * 于是用户填了带 /v1 的地址后：<b>测连通过（所以能保存成功），一聊天 URL 就变成
 * {@code .../v1/v1/chat/completions}，直接 404 报错。</b>
 *
 * <p>解法：把"前缀"统一成<b>不含 /v1</b> 的形态，谁要 /v1 谁自己拼。
 * 两种写法归一化后完全等价，测连与聊天不再打架。
 */
 public static String normalizeOpenAiPrefix(String baseUrl) {
 String b = baseUrl == null ? "" : baseUrl.trim();
 while (b.endsWith("/")) {
 b = b.substring(0, b.length() - 1);
 }
 if (b.endsWith("/v1")) {
 b = b.substring(0, b.length() - 3);
 }
 while (b.endsWith("/")) {
 b = b.substring(0, b.length() - 1);
 }
 return b;
 }

 /**
 * 推断该端点/模型是否「可能支持深度思考」，返回 {@code [是否支持, 说明文案]}。
 *
 * <p>比 {@link #supportsThinking} 只看域名更准确：
 * <ol>
 * <li>模型名里带 thinking / reasoner / r1 / o1 / o3 → 推理型模型，支持；</li>
 * <li>按已知平台域名判断（百炼 qwen3 / 智谱 GLM-5 / DeepSeek）；</li>
 * <li>未知平台 → <b>不武断说"不支持"</b>，而是提示可实测确认。</li>
 * </ol>
 * 第 3 条很重要：直接说"不支持"会让用户放弃尝试，而实际上可能是支持的。
 */
 public static ThinkingInfo classifyThinking(String baseUrl, String model) {
 String u = baseUrl == null ? "" : baseUrl.toLowerCase();
 String m = model == null ? "" : model.toLowerCase();

 if (m.contains("thinking") || m.contains("reasoner") || m.contains("r1")
 || m.contains("-o1") || m.contains("-o3")
 || m.contains("o1-") || m.contains("o3-")) {
 return new ThinkingInfo(true, "该模型为推理型，支持深度思考");
 }
 if (u.contains("aliyuncs.com") || u.contains("dashscope")) {
 return new ThinkingInfo(true, "阿里云百炼：qwen3 系列可开启深度思考（enable_thinking）");
 }
 if (u.contains("bigmodel.cn")) {
 return new ThinkingInfo(true, "智谱：GLM-5.x 支持 thinking 推理模式");
 }
 if (u.contains("deepseek.com")) {
 return new ThinkingInfo(true, "DeepSeek：deepseek-reasoner 支持思考");
 }
 return new ThinkingInfo(false, "该端点深度思考能力未知，可实测确认是否有 reasoning_content");
 }

 /**
 * 按 base_url 判定平台分组名，供前端的模型下拉分组展示。
 *
 * @param provider 会话的 provider（{@code ollama} / {@code cloud}）；
 * 非 cloud 一律归为「本地」
 */
 public static String classifyPlatform(String baseUrl, String provider) {
 if (provider != null && !provider.trim().isEmpty()
 && !"cloud".equalsIgnoreCase(provider.trim())) {
 return "本地";
 }
 String u = baseUrl == null ? "" : baseUrl.toLowerCase();
 if (u.contains("aliyuncs.com") || u.contains("dashscope")) {
 return "阿里云百炼";
 }
 if (u.contains("bigmodel.cn") || u.contains("zhipu")) {
 return "智谱";
 }
 if (u.contains("api.openai.com")) {
 return "OpenAI";
 }
 if (u.contains("deepseek.com")) {
 return "DeepSeek";
 }
 if (u.contains("moonshot.cn")) {
 return "月之暗面";
 }
 if (u.contains("volces.com") || u.contains("ark.cn")) {
 return "豆包";
 }
 return "其他平台";
 }

 /**
 * 返回模型的上下文窗口大小（token）；未知返回 {@code null}。
 *
 * <p>本地 Ollama 直接返回我们显式设置的 {@code numCtx}（这个值是准确的，
 * 因为它就是我们传给模型的值）；云端只能按名字模糊匹配参考表。
 */
 public static Integer contextWindowOf(String model, String provider, int ollamaNumCtx) {
 if ("ollama".equalsIgnoreCase(provider == null ? "" : provider.trim())) {
 return ollamaNumCtx;
 }
 String m = model == null ? "" : model.trim().toLowerCase();
 if (m.isEmpty()) {
 return null;
 }
 for (String[] entry : MODEL_CONTEXT_WINDOWS) {
 if (m.contains(entry[0])) {
 return Integer.valueOf(entry[1]);
 }
 }
 return null;
 }

 /**
 * 把 token 数格式化成易读文本：8192 → {@code 8K}；131072 → {@code 128K}；1000000 → {@code 1M}。
 *
 * <p>注意 1000 以上除以 1024 而不是 1000 —— 这是行业惯例
 * （上下文窗口按 2 的幂计算，128K 实际是 131072），除以 1000 会得到 131.072K 这种怪数字。
 */
 public static String formatContextWindow(Integer n) {
 if (n == null || n <= 0) {
 return "—";
 }
 if (n >= 1_000_000) {
 // 去掉多余的小数位：1.0M → 1M
 double v = n / 1_000_000.0;
 return trimZero(v) + "M";
 }
 if (n >= 1_000) {
 return (n / 1024) + "K";
 }
 return String.valueOf(n);
 }

 private static String trimZero(double v) {
 if (v == Math.floor(v)) {
 return String.valueOf((long) v);
 }
 return String.valueOf(v);
 }

 /**
 * 深度思考能力的推断结论。
 *
 * @param supported 是否（可能）支持
 * @param hint 给界面看的说明文案
 */
 public record ThinkingInfo(boolean supported, String hint) {
 }
}
