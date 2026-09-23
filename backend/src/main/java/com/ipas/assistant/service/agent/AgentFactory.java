package com.ipas.assistant.service.agent;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.common.SystemPrompts;
import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.entity.Conversation;
import com.ipas.assistant.common.PromptGuard;
import com.ipas.assistant.entity.LlmProvider;
import com.ipas.assistant.entity.Skill;
import com.ipas.assistant.repository.ConversationRepository;
import com.ipas.assistant.repository.LlmProviderRepository;
import com.ipas.assistant.repository.SkillRepository;
import com.ipas.assistant.service.RuntimeSettingsService;
import com.ipas.assistant.service.llm.ChatModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Agent 工厂：按会话配置构建（或复用）带记忆的 ReAct 智能体。
 * 对应 的 {@code get_agent_for_conversation()}。
 *
 * <h2>这是整个第三阶段的核心</h2>
 *
 * <p>它把四样东西组装起来：
 * <pre>
 * 大模型（ChatModelFactory 按会话凭据动态构造）
 * + 工具（AssistantTools.forUser，闭包绑定用户）
 * + 系统提示词（基础提示词 + 已启用技能的附加指令）
 * + 记忆（全局共享的 MysqlSaver，按 thread_id = 会话 id 隔离）
 * ↓
 * ReactAgent（对应的 ReactAgent 构建器）
 * </pre>
 *
 * <h2>★ 记忆为什么是"全局共享"的（早期设计特意强调过）</h2>
 *
 * <p>所有会话的 Agent 共用<b>同一个</b> {@link MysqlSaver} 实例，
 * 而不是每个 Agent 各建一份。原因：用户"切换模型 / 技能 / 知识库"时
 * Agent 会被重建，若记忆是 Agent 私有的，一换模型对话历史就断了 ——
 * 表现为"AI 突然失忆"。共享记忆按 {@code thread_id} 天然隔离到各会话，
 * 这样重建 Agent 只是换了"大脑和工具"，记忆仍然接得上。
 *
 * <h2>缓存策略</h2>
 *
 * <p>命中 {@link AgentCache} 就直接返回（构建开销不小：初始化模型 + 绑工具 + 编译图）。
 * 缓存键是复合的（见 {@link AgentCache.Key}），任何一个配置维度变化都会重建。
 */
@Service
@Profile("!test")
public class AgentFactory {

 private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

 /**
 * 上下文摘要提示词。对应 的 {@code _summary_instruction()}，
 * 要求保留「身份/偏好」「话题与结论」「未解决与待办」「具体名称与数值」四类信息。
 *
 * <p>⚠️ 官方 {@code SummarizationHook} 会执行 {@code String.format(prompt, 渲染后的对话)}，
 * 因此正文结尾必须保留唯一一个 {@code %s} 占位符，且正文里不能再出现其它 {@code %}。
 */
 private static final String SUMMARY_PROMPT = """
 请把下面的对话历史压缩成一段简洁的中文摘要，供之后的对话继续参考。
 必须保留以下四类信息：
 1. 用户的身份、偏好与长期目标（例如「我叫小明」「我在做毕业设计」）；
 2. 讨论过的主要话题，以及已经得出的结论；
 3. 尚未解决的问题、用户表达过的待办意图；
 4. 出现过的具体名称与数值（文件名、模型名、配置项、数量等）。
 要求：不超过 300 字；只陈述事实，不要评价、不要客套；不要遗漏关键数字；不要输出「以下是摘要」这类前缀，直接给摘要正文。

 【对话历史】
 %s
 """;

 /**
 * 摘要注入为 system 消息时加的前缀。
 * <p>官方 hook 的拼接形式是 {@code summaryPrefix + summary}。
 */
 private static final String SUMMARY_PREFIX =
 "\n\n【前文摘要】（更早的对话已压缩为摘要，供你理解上下文，"
 + "必要时可据此回答用户关于「之前聊过什么」的提问）\n";

 private final ConversationRepository conversationRepository;
 private final SkillRepository skillRepository;
 private final LlmProviderRepository providerRepository;
 private final RuntimeSettingsService settingsService;
 private final ChatModelFactory modelFactory;
 private final AssistantTools tools;
 private final MysqlSaver checkpointSaver;
 private final AgentCache cache;
 private final AppProperties properties;

 public AgentFactory(ConversationRepository conversationRepository,
 SkillRepository skillRepository,
 LlmProviderRepository providerRepository,
 RuntimeSettingsService settingsService,
 ChatModelFactory modelFactory,
 AssistantTools tools,
 MysqlSaver checkpointSaver,
 AgentCache cache,
 AppProperties properties) {
 this.conversationRepository = conversationRepository;
 this.skillRepository = skillRepository;
 this.providerRepository = providerRepository;
 this.settingsService = settingsService;
 this.modelFactory = modelFactory;
 this.tools = tools;
 this.checkpointSaver = checkpointSaver;
 this.cache = cache;
 this.properties = properties;
 }

 /**
 * 取（或构建）某会话的 Agent。
 *
 * @param conversationId 会话 id（同时用作记忆的 thread_id）
 * @param userId 归属用户（越权校验）
 * @param enableThinking 云端深度思考开关；null 表示不附加该参数
 * @param thinkingBudget 思维链上限；null/0 表示用平台默认
 * @return 可直接调用的 ReAct 智能体
 * @throws ApiException 404 会话不存在（不属于该用户时同样 404，不泄露存在性）
 */
 @Transactional(readOnly = true)
 public ReactAgent forConversation(Long conversationId,
 Long userId,
 Boolean enableThinking,
 Integer thinkingBudget) {

 Conversation conv = conversationRepository.findByIdAndUserId(conversationId, userId)
 .orElseThrow(() -> ApiException.notFound("会话不存在"));

 // ---- 解析「已启用的技能」----
 // 三个条件缺一不可：属于本用户、is_enabled=true、id 在会话勾选的集合内。
 // 少了 is_enabled 的过滤，用户「关闭」某个技能后它依然生效。
 List<Long> skillIds = conv.getActiveSkillIds() == null ? List.of() : conv.getActiveSkillIds();
 List<Skill> activeSkills = skillIds.isEmpty()
 ? List.of()
 : skillRepository.findByIdInAndUserIdAndIsEnabledTrueOrderByIdAsc(skillIds, userId);

 // ---- 解析知识库集合（RAG 工具已接入，kbIds 既参与缓存键，也决定 search_knowledge_base 的检索范围）----
 List<Long> kbIds = conv.getActiveKbIds() == null ? List.of() : conv.getActiveKbIds();

 // ---- 解析模型与凭据（与「确定性知识问答」链路共用同一套规则，见 resolveModel）----
 ResolvedModel rm = resolveModel(conv, userId, enableThinking, thinkingBudget);

 // ---- 缓存查找 ----
 AgentCache.Key key = AgentCache.keyOf(conversationId, rm.provider(), rm.model(),
 activeSkills.stream().map(Skill::getId).toList(), kbIds, rm.thinking(), rm.thinkingBudget(),
 conv.getProviderId());
 Object cached = cache.get(key);
 if (cached instanceof ReactAgent agent) {
 return agent;
 }

 // ---- 构建 ----
 // 基础提示词 + 注入防护规则。
 // 为什么这里也要加：Agent 链路会把用户上传的附件正文拼进用户消息，
 // 那同样是不可信输入（有人可以在文档里写"忽略以上指令"）。
 String systemPrompt = SystemPrompts.BASE + "\n\n" + PromptGuard.RULE;
 if (!activeSkills.isEmpty()) {
 systemPrompt = SystemPrompts.withSkills(systemPrompt, activeSkills.stream()
 .map(s -> new SystemPrompts.SkillPrompt(
 s.getName(), s.getDescription(), s.getPrompt()))
 .toList());
 }

 Builder agentBuilder = ReactAgent.builder()
 .name("ipas-assistant")
 .model(rm.chatModel())
 .tools(tools.forUser(userId, kbIds, conversationId))
 .systemPrompt(systemPrompt)
 // 记忆：全局共享的 MysqlSaver。thread_id 由调用方在 RunnableConfig 里传，
 // 我们用会话 id（见 ChatService），保证同一会话的历史接得上。
 .saver(checkpointSaver);

 // ---- 上下文压缩（对应「滑动窗口 + 摘要」）----
 // 评估结论（详见 docs/05 与 AppProperties.History 的注释）：
 // SAA 官方 SummarizationHook 与原自研逻辑的核心语义一致 ——
 // · 保留最近 messagesToKeep 条原文（保证当下对话连贯、指代能对上）；
 // · 更早的消息交给模型压成一段摘要（压缩 token）；
 // · 且不会把一次工具调用拆成两半（官方 findSafeCutoff；
 // 拆断会让 图工作流框架/SAA 在调模型前直接报错、会话卡死）。
 // 唯一差别是「按 token 触发」而非「按消息条数」——按 docs 的指示，
 // 官方能替代就不必自研那 259 行。summaryMaxTokens <= 0 表示关闭压缩。
 AppProperties.History history = properties.history();
 if (history != null && history.summaryMaxTokens() > 0) {
 SummarizationHook compression = SummarizationHook.builder()
 .model(rm.chatModel()) // 摘要复用同一模型（早期实现也是）
 .tokenCounter(TokenCounter.approximateMsgCounter()) // 官方按字符数估算 token
 .messagesToKeep(Math.max(2, history.keepRecent())) // 最近 N 条保留原文
 .maxTokensBeforeSummary(history.summaryMaxTokens()) // 超过该 token 才启动压缩
 .summaryPrompt(SUMMARY_PROMPT)
 .summaryPrefix(SUMMARY_PREFIX)
 // 与原文一致：用户身份靠摘要承载，不额外保留"首条用户消息"（避免与摘要重复）
 .keepFirstUserMessage(false)
 .build();
 agentBuilder.hooks(List.of(compression));
 }

 ReactAgent agent = agentBuilder.build();

 cache.put(key, agent);
 log.info("已构建 Agent：会话={} provider={} model={} 技能={} 知识库={} 思考={}",
 conversationId, rm.provider(), rm.model(), key.skillIds(), key.kbIds(), rm.thinking());
 return agent;
 }

 /**
 * 解析某会话最终要使用的模型与凭据。
 *
 * <h2>为什么把它从 {@link #forConversation} 里抽出来</h2>
 *
 * <p>模型凭据的解析有一套不短的兜底链（会话指定的接入平台 → 全局默认云端凭据 →
 * 第一个已接入的平台 → 本地 Ollama），而且涉及「providerId 指向的行属于本用户」
 * 这类安全约束。除了构建 Agent，<b>「确定性知识问答」链路也要用同一个模型</b>
 * （它不走 Agent、直接调模型生成答案）。
 *
 * <p>如果那条链路自己再写一遍凭据解析，两边一旦走岔，就会出现
 * 「Agent 能连上模型、知识问答却报未配置」这种极难定位的问题。
 * 所以这里做成公开方法，两条链路共用同一份规则。
 *
 * <p>刻意<b>不加</b> {@code @Transactional}：它只读不写（仓储自身的读方法已带事务），
 * 加在这里反而带来"同类自调用不走代理"的隐患。
 */
 public ResolvedModel resolveModel(Conversation conv, Long userId,
 Boolean enableThinking, Integer thinkingBudget) {
 RuntimeSettingsService.LlmSettings settings = settingsService.load(userId);

 // ---- 解析模型凭据：优先会话指定的 provider，否则回落默认配置 ----
 // 多 API 兜底链：会话指定 → 全局默认 → 第一个已接入的平台。
 String provider = conv.getProvider() == null ? "" : conv.getProvider();
 String model = conv.getModel();
 LlmProvider providerRow = null;
 if (conv.getProviderId() != null) {
 providerRow = providerRepository
 .findByIdAndUserId(conv.getProviderId(), userId)
 .orElse(null);
 }
 if (providerRow == null && !"ollama".equalsIgnoreCase(provider)
 && settings.apiKey().isBlank()) {
 // 旧会话没指定 provider，且默认云端凭据也已清空 → 兜底用第一个已接入的平台，
 // 保证老会话仍然可用（早期实现同样有这段兜底）
 providerRow = providerRepository.findFirstByUserIdOrderByIdAsc(userId).orElse(null);
 }
 if (providerRow != null) {
 provider = "cloud";
 if (model == null || model.isBlank()) {
 model = providerRow.getModel();
 }
 } else if (model == null || model.isBlank()) {
 model = "ollama".equalsIgnoreCase(provider)
 ? properties.llm().ollama().model()
 : settings.model();
 }

 boolean thinking = provider != null && !"ollama".equalsIgnoreCase(provider)
 && Boolean.TRUE.equals(enableThinking);
 int budget = thinking && thinkingBudget != null ? Math.max(0, thinkingBudget) : 0;

 ChatModel chatModel = modelFactory.create(
 provider, model,
 providerRow == null ? null : providerRow.getApiKey(),
 providerRow == null ? null : providerRow.getBaseUrl(),
 thinking ? Boolean.TRUE : null,
 budget > 0 ? budget : null,
 settings);

 return new ResolvedModel(chatModel, provider, model, thinking, budget);
 }

 /**
 * 会话解析出的模型与凭据。
 *
 * @param chatModel 可直接调用的模型
 * @param provider {@code ollama} 或 {@code cloud}（落库 / SSE done 事件要带上）
 * @param model 实际使用的模型名
 * @param thinking 是否启用了云端深度思考（Agent 缓存键的维度之一）
 * @param thinkingBudget 思维链上限，0 表示用平台默认
 */
 public record ResolvedModel(ChatModel chatModel, String provider, String model,
 boolean thinking, int thinkingBudget) {
 }
}
