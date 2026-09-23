package com.ipas.assistant.service.llm;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.common.ModelCatalog;
import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.service.RuntimeSettingsService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

/**
 * 模型工厂 —— 按会话的配置在运行时构造 ChatModel。
 * 对应 的 {@code 模型工厂()}。
 *
 * <h2>为什么是"工厂"而不是"Bean"</h2>
 *
 * <p>这是本项目与常见 Spring AI 用法最大的不同点，也是最容易踩的地方：
 *
 * <p>常见用法是在 {@code application.yml} 里配一套模型，让 Spring 自动配置出一个
 * 单例 {@code ChatModel} Bean，然后到处注入。但本项目的模型是<b>每个会话各不相同</b>的：
 * 用户 A 的会话用本地 qwen3:8b、用户 B 的会话用智谱 glm-5.2、用户 C 的会话用
 * 他在设置页自己接入的某个平台 —— 凭据存在数据库里，运行时可改。
 *
 * <p>所以模型必须<b>按需构造</b>。这也正是我在 {@code pom.xml} 里选择依赖
 * "模型实现类"（spring-ai-openai / spring-ai-ollama）而不是 starter 的原因：
 * starter 会顺带引入自动配置，产出我们根本用不到、还会带来启动校验问题的 Bean。
 *
 * <h2>两个必须照抄早期设计的细节</h2>
 *
 * <ol>
 * <li><b>本地走 Ollama 原生接口，不走 OpenAI 兼容端点</b>
 * （{@code OllamaChatModel} 而非 {@code OpenAiChatModel} + Ollama 的 /v1）。
 * 原因：只有在原生端点上 {@code think=false} 才生效，
 * 关掉 qwen3 的思考能提速 3~5 倍（早期设计注释里的实测结论）。
 * OpenAI 兼容端点没有这个开关。</li>
 *
 * <li><b>{@code enable_thinking} 只对阿里云百炼类域名透传</b>。
 * 这是百炼的专有参数，往其它平台（DeepSeek 官方 / 智谱 / OpenAI）传未知参数
 * 有被直接拒绝的风险。判断逻辑见 {@link ModelCatalog#supportsThinking}。</li>
 * </ol>
 */
@Service
public class ChatModelFactory {

 /**
 * 云端温度固定为 0。
 *
 * <p>与早期设计一致：本项目是"个人助理"场景，要的是确定、不胡编，
 * 不追求创作型模型的多样性。温度 0 还能让同样的输入尽量得到同样的输出，
 * 便于排查问题。
 */
 private static final Double TEMPERATURE = 0.0;

 private final AppProperties properties;

 /**
 * 韧性保护（限流 / 熔断 / 退避重试）。
 *
 * <p>装饰在本工厂的出口上，等价于给<b>所有</b>模型调用统一加保护 ——
 * 工厂是全项目唯一构造模型的地方，调用方（Agent / 知识问答 / 查询改写 / 课表导入）一行都不用改。
 */
 private final LlmResilience resilience;

 public ChatModelFactory(AppProperties properties, LlmResilience resilience) {
 this.properties = properties;
 this.resilience = resilience;
 }

 /**
 * 构造模型。
 *
 * @param provider {@code ollama} 或 {@code cloud}
 * @param model 模型名
 * @param apiKey 云端 Key（provider=cloud 时必填）
 * @param baseUrl 云端端点（provider=cloud 时必填）
 * @param enableThinking 云端深度思考开关；<b>null 表示完全不附加该参数</b>
 * （测连与非流式调用必须传 null，否则百炼会报
 * "parameter.enable_thinking must be set to false for non-streaming calls"）
 * @param thinkingBudget 思维链 token 上限；null 或 &lt;=0 表示用平台默认
 * @param settings 运行时配置（提供本地 num_ctx 等）
 */
 public ChatModel create(String provider,
 String model,
 String apiKey,
 String baseUrl,
 Boolean enableThinking,
 Integer thinkingBudget,
 RuntimeSettingsService.LlmSettings settings) {

 String p = provider == null ? "" : provider.trim().toLowerCase();
 ChatModel raw = "ollama".equals(p)
 ? createOllama(model, settings)
 : createCloud(model, apiKey, baseUrl, enableThinking, thinkingBudget);
 // 统一套上韧性保护：工厂是全项目唯一构造模型的地方，
 // 在这里装饰一次，所有入口（Agent / 知识问答 / 查询改写 / 课表导入）就都带上了。
 // label 取 "provider:model"，出问题时日志里能直接看出是哪个模型在抖动。
 return new ResilientChatModel(raw, resilience, p + ":" + model);
 }

 // ==================================================================
 // 本地 Ollama
 // ==================================================================

 /**
 * 构造本地 Ollama 模型。
 *
 * <p>{@code numCtx} 来自运行时配置（设置页可改），它决定"模型能看到多长的
 * 对话历史 + 提示词"。这是本项目<b>唯一真正能控制的上下文窗口</b> ——
 * 云端模型的窗口由平台决定，请求里没有可调参数（早期设计注释专门澄清过这点）。
 */
 private ChatModel createOllama(String model, RuntimeSettingsService.LlmSettings settings) {
 AppProperties.Llm.Ollama ollama = properties.llm().ollama();

 OllamaApi api = OllamaApi.builder()
 .baseUrl(ollama.baseUrl())
 .build();

 // ⚠️【实测修正】Spring AI 1.1.2 的 OllamaChatOptions **是有关思考开关的**：
 // Builder 上有 disableThinking() / enableThinking() / thinkOption(ThinkOption)。
 // 之前的结论「无 think 开关」是错的（只查了部分 API 表面）。
 //
 // 为什么必须显式关思考（而不是沿用模型默认）：
 // qwen3 默认开启思考。端到端实测发现：一旦 Agent 带着工具（search_knowledge_base）
 // 跑一轮「模型 → 调用工具 → 生成最终答案」，qwen3 把最终答案放进了 thinking 字段、
 // content 为空 —— 于是流里一个 token 都收不到，界面只剩空气泡。
 // 配置项 OLLAMA_THINK 默认 false（对应"关思考提速"），这里照它显式开关。
 OllamaChatOptions.Builder options = OllamaChatOptions.builder()
 .model(model != null && !model.isBlank() ? model : ollama.model())
 .temperature(TEMPERATURE)
 .numCtx(settings.ollamaNumCtx());
 if (ollama.think()) {
 options.enableThinking();
 } else {
 options.disableThinking();
 }

 return OllamaChatModel.builder()
 .ollamaApi(api)
 .defaultOptions(options.build())
 .build();
 }

 // ==================================================================
 // 云端 OpenAI 兼容平台
 // ==================================================================

 /**
 * 构造云端模型（智谱 / DeepSeek / 百炼 / 硅基流动 / OpenAI 官方 都走这条路，
 * 因为它们都提供 OpenAI 兼容端点）。
 */
 private ChatModel createCloud(String model,
 String apiKey,
 String baseUrl,
 Boolean enableThinking,
 Integer thinkingBudget) {

 // 与早期设计一致的报错文案：明确告诉用户"去哪里配"，而不是抛一个空指针
 if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
 throw ApiException.badRequest(
 "云端模型未配置：请在设置里接入 API，或配置默认云端凭据");
 }

 // base_url 统一成"不含 /v1 的前缀"（用户可能填 .../compatible-mode/v1，也可能填 .../compatible-mode）。
 // Spring AI 的 OpenAiApi 默认路径就是 "/v1/chat/completions"，会自己补 /v1 ——
 // 若不归一化，带 /v1 的地址会变成 ".../v1/v1/chat/completions"，直接 404。
 // ⚠️ 这正是"测连通过（能保存）、一聊天就报错"的根因：测连自己拼 /chat/completions，
 // 而这里走 Spring AI 又补了一次 /v1，两边对 base_url 的预期不一致。
 OpenAiApi api = OpenAiApi.builder()
 .baseUrl(ModelCatalog.normalizeOpenAiPrefix(baseUrl))
 .apiKey(apiKey)
 .build();

 OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
 .model(model)
 .temperature(TEMPERATURE);

 // 深度思考参数：只对支持它的端点透传（见类注释第 2 条）
 if (enableThinking != null && ModelCatalog.supportsThinking(baseUrl)) {
 // OpenAiChatOptions 支持透传平台专有参数（extraBody），
 // 百炼的 enable_thinking / thinking_budget 就走这里。
 java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
 extra.put("enable_thinking", enableThinking);
 if (enableThinking && thinkingBudget != null && thinkingBudget > 0) {
 // 限制思维链长度，避免模型无限推理导致长时间等待
 extra.put("thinking_budget", thinkingBudget);
 }
 options.extraBody(extra);
 }

 return OpenAiChatModel.builder()
 .openAiApi(api)
 .defaultOptions(options.build())
 .build();
 }
}
