package com.ipas.assistant.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.common.ChatStreamEvent;
import com.ipas.assistant.dto.ChatRequest;
import com.ipas.assistant.entity.Conversation;
import com.ipas.assistant.entity.Message;
import com.ipas.assistant.repository.ConversationRepository;
import com.ipas.assistant.repository.MessageRepository;
import com.ipas.assistant.service.agent.AgentFactory;
import com.ipas.assistant.service.agent.ChatModeRouter;
import com.ipas.assistant.service.agent.KbQaService;
import com.ipas.assistant.service.agent.ThinkSplitter;
import com.ipas.assistant.service.memory.ConversationMemoryPort;
import com.ipas.assistant.service.rag.RagSourcesBus;
import com.ipas.assistant.service.trace.ChatTraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式聊天（SSE）服务。对应 的流式接口。
 *
 * <h2>整体流程（与早期设计一一对应）</h2>
 * <pre>
 * 1. prepare() —— 校验归属、落库用户消息（或重新生成时删旧回答）、
 * 预建空助手消息、首轮自动命名会话
 * 2. forConversation()—— 取/建带记忆的 Agent（记忆按 thread_id=会话id 隔离）
 * 3. streamMessages() —— 订阅 Agent 的流式输出，逐帧拆分「思考/正文」推给前端
 * 4. 收尾 —— 把完整正文回写助手行、发 done 事件；异常/断连则回写部分内容并清记忆
 * </pre>
 *
 * <h2>★ 为什么被 {@code @Profile("!test")} 排除在契约测试之外</h2>
 *
 * <p>本服务依赖<b>真实的大模型 + 真实的 MySQL 记忆表</b>（{@code MysqlSaver} 构造即连库）。
 * 而本项目的 49 个契约测试刻意<b>不依赖 MySQL</b>（用 Mockito 替身仓储），且
 * {@code ApiContractTest} 激活的是 {@code test} 这个 Spring profile。
 * 因此所有 {@code !test} 的 Agent Bean（含本类）在契约测试里根本不会被装配，
 * 既不会拖慢测试，也不会因为"连不上模型"而让测试挂掉。
 * 这类需要真实环境的逻辑，由端到端联调覆盖（与 {@code AgentMemoryConfig} 注释里的决策一致）。</p>
 *
 * <h2>错误处理的两个阶段（务必分清）</h2>
 *
 * <p><b>阶段一：还没开始推流</b>。prepare / 取 Agent 阶段若出错（会话不存在、模型未配置），
 * 直接抛 {@link ApiException}，由 {@code GlobalExceptionHandler} 转成
 * {@code {"detail":...}} 的普通 JSON 错误响应（连接尚未以 SSE 形式建立，前端走
 * {@code !resp.ok} 分支读 detail）。</p>
 *
 * <p><b>阶段二：已经开始推流</b>。此时 HTTP 已是 200 / SSE，任何后续错误都用
 * {@code {"error":"..."}} 事件回传（连接保持 200，前端把它显示成"出错了: ..."）。
 * 异常或客户端断开时，还要<b>回写已生成的部分内容</b>并<b>清掉该会话记忆</b>——
 * 这正是早期实现 里"断连清记忆"的做法，目的是避免半截 tool_calls 历史
 * 导致后续请求永久报错。</p>
 *
 * <h2>流式 token 的两种语义兼容</h2>
 *
 * <p>SAA 的 {@code ReactAgent.streamMessages} 在不同版本/配置下，可能逐帧吐
 * <b>增量片段</b>，也可能每帧都是<b>到目前为止的全文</b>。两种语义下若处理不当，
 * 会出现"重复刷字"或"只显示最后一帧"。这里用 {@code seen} 维护"已见全文"，
 * 取差量作为本次要处理的增量再喂给 {@link ThinkSplitter}，<b>两种语义都正确</b>。
 * （这一点的确切行为，最终由端到端联调确认；此处做了双重兼容以抗风险。）</p>
 */
@Service
@Profile("!test")
public class ChatService {

 private static final Logger log = LoggerFactory.getLogger(ChatService.class);

 /**
 * SSE 连接超时：10 分钟。
 *
 * <p>大模型首字可能很慢（尤其本地 qwen3 开思考时），也不能因为"长时间没新 token"
 * 就被容器强制断开。10 分钟是宽松上限，正常对话远到不了。超时走
 * {@code emitter.onTimeout} 做清理，不会静默丢数据。</p>
 */
 private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

 /** 首轮自动命名取用户消息的前 N 个字作为会话标题（与早期设计一致）。 */
 private static final int TITLE_MAX_LEN = 20;

 /**
 * 会话级"生成中"互斥闸门：同一会话同时只允许一条流（设计说明见该类注释）。
 *
 * <p>互斥状态、超时接管与清扫逻辑都封装在它里面；本类只负责在正确的时机调用 ——
 * 进入时 {@code acquire}，三条收尾路径（正常 / 异常 / 断连超时）以及"同步阶段失败"时
 * {@code release}。这样那段纯逻辑就能独立单测，不必依赖真实模型与数据库。
 */
 private final ConversationStreamGuard streamGuard;

 private final ConversationRepository conversationRepository;
 private final MessageRepository messageRepository;
 private final AgentFactory agentFactory;
 private final RuntimeSettingsService settingsService;
 private final ConversationMemoryPort memory;
 private final RagSourcesBus ragSourcesBus;

 /**
 * 执行模式路由：决定这一轮走"确定性知识问答"还是"开放式 Agent"。
 * 判据是纯逻辑、可单测，见 {@link ChatModeRouter}。
 */
 private final ChatModeRouter chatModeRouter;

 /**
 * 确定性知识问答链路：先检索、再把带编号的证据交给模型生成（见 {@link KbQaService}）。
 * 检索不到证据时会返回空，由本类回退到 Agent 路径。
 */
 private final KbQaService kbQaService;

 /**
 * 执行过程记录（可观测性）：把这一轮的阶段耗时、首字延迟、token 估算写进 chat_traces。
 * 写入失败会被它自己吞掉，绝不影响对话。
 */
 private final ChatTraceService traceService;

 /**
 * 指向<b>自己的 Spring 代理</b>（自注入）。
 *
 * <h2>为什么需要它（这是修掉一个真实崩溃的关键）</h2>
 *
 * <p>{@link #prepare} 需要 {@code @Transactional}，而它是由<b>同一个类里</b>的
 * {@link #stream} 调用的。Spring 的事务是靠<b>动态代理</b>织入的，而
 * "自己调自己"（{@code this.prepare(...)}）根本不经过代理 —— 于是
 * {@code @Transactional} <b>静默失效</b>，方法体里的数据库操作跑在"没有事务的线程"上。
 *
 * <p>症状（真实复现）：点"重新生成"时报
 * {@code InvalidDataAccessApiUsageException: No EntityManager with actual transaction
 * available for current thread - cannot reliably process 'remove' call} ——
 * 因为 {@code deleteBy...} 这类<b>派生删除</b>方法不像 {@code save()} 那样自带事务，
 * 必须靠外层事务；外层事务一没生效，它就炸。
 * （普通发送没事，是因为那条分支只用到了自带事务的 {@code save()}。）
 *
 * <p>修法：注入自己的代理，改为 {@code self.prepare(...)}，让调用<b>经过代理</b>。
 * {@code @Lazy} 用于打破"自己注入自己"的循环依赖 —— 这里注入的是延迟代理，
 * 构造阶段不会真的去获取自身。
 *
 * <p>⚠️ 另一个同样隐蔽的点：Spring 官方明确说明，<b>代理模式下
 * {@code @Transactional} 只对 public 方法生效</b>；标在 protected/private
 * 方法上不会报错，但事务配置会被<b>忽略</b>。所以 {@link #prepare} 必须是 public。
 */
 private final ChatService self;

 public ChatService(ConversationRepository conversationRepository,
 MessageRepository messageRepository,
 AgentFactory agentFactory,
 RuntimeSettingsService settingsService,
 ConversationMemoryPort memory,
 RagSourcesBus ragSourcesBus,
 ChatModeRouter chatModeRouter,
 KbQaService kbQaService,
 ChatTraceService traceService,
 ConversationStreamGuard streamGuard,
 @Lazy ChatService self) {
 this.conversationRepository = conversationRepository;
 this.messageRepository = messageRepository;
 this.agentFactory = agentFactory;
 this.settingsService = settingsService;
 this.memory = memory;
 this.ragSourcesBus = ragSourcesBus;
 this.chatModeRouter = chatModeRouter;
 this.kbQaService = kbQaService;
 this.traceService = traceService;
 this.streamGuard = streamGuard;
 this.self = self;
 }

 // ==================================================================
 // 对外入口
 // ==================================================================

 /**
 * 处理一轮流式聊天，返回 {@link SseEmitter}。
 *
 * <p>方法体前半段（prepare + 取 Agent）是<b>同步</b>的：任何错误都在此之前抛出，
 * 以普通 JSON 响应返回。确认无误后才创建 SseEmitter 并返回，真正的流式在
 * {@code .subscribe(...)} 之后异步进行（运行在 Reactor 的线程上，不阻塞 HTTP 线程）。</p>
 *
 * @param userId 当前用户（多用户隔离依据）
 * @param body 前端发来的请求体
 * @return SSE 发射器；调用方（Controller）直接返回它即可
 */
 public SseEmitter stream(Long userId, ChatRequest body) {
 // ★★★ 会话级互斥（同一会话同时只允许一条流在生成）★★★
 // 为什么必须加：本方法原本对同一 conversationId 没有任何互斥。而"用户双击发送"或
 // "前端请求超时后自动重试"都会让同一会话同时跑两条流，后果有三个（都真实存在）：
 // ① 两条回答交替写入 messages → 界面出现"两个气泡你一句我一句"；
 // ② RagSourcesBus 的收集器按会话 id 索引 → 后一条流覆盖前一条，来源标注串台；
 // ③ "重新生成"分支会删掉 id 更大的助手消息 → 可能把正在生成的那条删掉。
 // 做法：进入时用 putIfAbsent 占住名额，被占用则以 409 直接拒绝（前端会提示"请稍候"）。
 Long lockId = body.sessionId();
 streamGuard.acquire(lockId);
 // 本轮计时的起点：首字延迟与总耗时都相对它计算
 long startedAt = System.currentTimeMillis();
 try {
 // ---- 阶段一：同步准备（出错 = 普通 JSON 错误，连接尚未建立）----
 // ⚠️ 必须经 self（自己的代理）调用，不能写成 prepare(...)：
 // 后者是"同类自调用"，不经过代理 → prepare 上的 @Transactional 会静默失效（见 self 字段的注释）。
 Prepared p = self.prepare(userId, body);
 Conversation conv = p.conv();
 Long convId = conv.getId();
 Long assistantId = p.assistantId();
 // provider / model 不再在这里取：它取决于本轮走哪条链路（见下方 chooseTarget），
 // 因为确定性知识问答用的是解析出的模型，可能与会话记录里写的不同。

 // 记录准备阶段耗时。assistantId 同时就是这一轮的"锚点"（execution id）。
 traceService.record(convId, assistantId, ChatTraceService.STAGE_PREPARE,
 System.currentTimeMillis() - startedAt, null);

 // 为本轮请求建立"知识库来源收集器"（工具执行时往里追加命中文件名，
 // 见 RagSourcesBus 注释：解决 Agent 缓存复用与每请求收集器的冲突）
 ragSourcesBus.register(convId);

 // 思考开关：前端没传就用用户已保存的设置（前端设置页改的是这里）
 Boolean enableThinking = body.enableThinking();
 Integer thinkingBudget = body.thinkingBudget();
 if (enableThinking == null || thinkingBudget == null) {
 RuntimeSettingsService.LlmSettings s = settingsService.load(userId);
 if (enableThinking == null) {
 enableThinking = s.enableThinking();
 }
 if (thinkingBudget == null) {
 thinkingBudget = s.thinkingBudget();
 }
 }

 // ---- 路由：决定这一轮由谁执行 ----
 // 判据见 ChatModeRouter：只有"启用了知识库 + 明确在问资料内容"才走确定性链路；
 // 工具型、创作型、闲聊型一律交回 Agent —— 判错的代价不对称，宁可漏判。
 List<Long> kbIds = conv.getActiveKbIds() == null ? List.of() : conv.getActiveKbIds();
 ExecTarget target;
 try {
 target = chooseTarget(conv, convId, userId, p.sendText(), kbIds,
 enableThinking, thinkingBudget, assistantId, startedAt);
 } catch (GraphRunnerException e) {
 throw ApiException.badRequest("模型初始化失败：" + e.getMessage());
 }
 Flux<org.springframework.ai.chat.messages.Message> messageStream = target.stream();
 // provider/model 参与落库与 done 事件，必须只赋值一次（lambda 要求"有效 final"）。
 // KB_QA 链路用的是解析出的模型，可能与会话记录里写的略有不同（走的是同一套兜底链）。
 String provider = target.provider();
 String model = target.model();
 // 提示词字符数：确定性链路知道自己的证据长度，Agent 链路拿不到，用于 DONE 里的 token 估算
 int promptChars = target.promptChars();

 // ---- 阶段二：建立 SSE，异步推流 ----
 SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
 ThinkSplitter splitter = new ThinkSplitter();
 StringBuilder answer = new StringBuilder(); // 已发送的正文增量累计
 String[] seen = {""}; // 已见全文（用于兼容累积式流式）
 AtomicBoolean sourcesEmitted = new AtomicBoolean(false); // 本轮 sources 事件是否已发
 Disposable[] disp = {null}; // 订阅句柄，便于断连时取消

 // 客户端断开 / 超时：取消订阅 + 回写部分内容 + 清记忆（避免半截 tool 历史）
 emitter.onTimeout(() -> onAbort(disp[0], assistantId, convId, answer, provider, model));
 emitter.onError(e -> onAbort(disp[0], assistantId, convId, answer, provider, model));

 // 订阅消息流。
 // 注意：模型初始化（可能抛受检异常 GraphRunnerException）已经在上面处理完 ——
 // 走到这里说明"已经拿到可用的流"，因此之后的错误一律用 {"error":...} 事件回传
 // （连接已是 200/SSE，无法再改状态码）。
 disp[0] = messageStream.subscribe(
 msg -> onNext(msg, emitter, splitter, answer, seen, convId, sourcesEmitted,
 assistantId, startedAt),
 err -> onError(err, emitter, assistantId, convId, answer, provider, model),
 () -> onComplete(emitter, assistantId, convId, answer,
 provider, model, p.userMessageId(), sourcesEmitted, splitter,
 startedAt, promptChars));

 return emitter;
 } catch (RuntimeException e) {
 // 同步阶段（还没开始推流）就失败时，也必须释放占位 ——
 // 否则这个会话会被一次失败的请求永久锁住，之后再也发不出消息。
 // 流已成功启动后，占位由三条收尾路径负责释放（onComplete / onError / onAbort）。
 streamGuard.release(lockId);
 throw e;
 }
 }

 // ==================================================================
 // 执行链路选择（确定性知识问答 / 开放式 Agent）
 // ==================================================================

 /**
 * 决定这一轮由谁执行，并给出消息流与实际使用的 provider/model。
 *
 * <h2>两条链路的取舍</h2>
 * <ul>
 * <li><b>确定性知识问答</b>：先检索、再把带编号的证据交给模型生成。
 * 好处是"必须检索"由代码保证，不再取决于模型这一轮愿不愿意调工具。</li>
 * <li><b>开放式 Agent</b>：带工具的 ReAct 循环，能写写画画、加待办、查天气。</li>
 * </ul>
 *
 * <h2>⚠️ 已知限制（务必知悉）</h2>
 *
 * <p>确定性链路<b>不经过 Agent，因此不会写入 Agent 的对话记忆</b>。
 * 界面上的聊天记录照常落库、下一轮的查询改写也能读到；但"知识问答之后紧接一轮
 * 需要回忆该内容的 Agent 对话"时，Agent 看不到上一轮的内容。
 * 要补齐需要把该轮问答写进记忆检查点，而手工构造检查点状态风险较高（见记忆回写相关说明），
 * 故暂不做；同模式的连续提问不受影响。
 *
 * @throws GraphRunnerException Agent 初始化失败（由调用方转成 400 普通 JSON 错误）
 */
 private ExecTarget chooseTarget(Conversation conv, Long convId, Long userId, String sendText,
 List<Long> kbIds, Boolean enableThinking, Integer thinkingBudget,
 Long exchangeId, long startedAt)
 throws GraphRunnerException {
 // ---- 路由（耗时极低，但记录下来才能证明"这一轮走的是哪条链路"）----
 long routeStart = System.currentTimeMillis();
 boolean kbQa = chatModeRouter.route(sendText, kbIds) == ChatModeRouter.Mode.KB_QA;
 traceService.record(convId, exchangeId, ChatTraceService.STAGE_ROUTE,
 System.currentTimeMillis() - routeStart, "mode=" + (kbQa ? "KB_QA" : "OPEN_AGENT"));

 if (kbQa) {
 long retrieveStart = System.currentTimeMillis();
 // 与 Agent 链路共用同一套凭据解析（见 AgentFactory.resolveModel）——
 // 若各写一份，两边一旦走岔就会出现"Agent 能连上模型、知识问答却报未配置"的怪问题。
 // 知识问答不需要"深度思考"（只会徒增首字延迟），显式关掉。
 AgentFactory.ResolvedModel rm = agentFactory.resolveModel(conv, userId, false, null);
 Optional<KbQaService.Prepared> prepared =
 kbQaService.prepare(userId, convId, sendText, kbIds, rm);
 traceService.record(convId, exchangeId, ChatTraceService.STAGE_RETRIEVE,
 System.currentTimeMillis() - retrieveStart,
 prepared.map(p -> "命中" + p.hitCount() + "段；查询=" + p.usedQuery())
 .orElse("未命中证据，回退 Agent"));
 if (prepared.isPresent()) {
 return new ExecTarget(prepared.get().flux(), rm.provider(), rm.model(),
 prepared.get().evidenceChars());
 }
 // 资料里没检索到证据（或链路出错）→ 回退 Agent：
 // 用户问的可能本来就不在资料范围内，回一句生硬的"未检索到证据"体验很差。
 log.info("知识问答未命中证据，回退 Agent 路径（会话 {}）", convId);
 }

 // 开放式 Agent：取/建带工具与记忆的 ReAct 智能体。
 // 记忆隔离靠 thread_id = 会话 id 的字符串形式。
 long buildStart = System.currentTimeMillis();
 ReactAgent agent = agentFactory.forConversation(convId, userId, enableThinking, thinkingBudget);
 UserMessage userMessage = new UserMessage(sendText);
 RunnableConfig config = RunnableConfig.builder()
 .threadId(String.valueOf(convId))
 .build();
 Flux<org.springframework.ai.chat.messages.Message> stream = agent.streamMessages(userMessage, config);
 traceService.record(convId, exchangeId, ChatTraceService.STAGE_AGENT_BUILD,
 System.currentTimeMillis() - buildStart,
 "provider=" + conv.getProvider() + " model=" + conv.getModel());

 // Agent 链路拿不到提示词长度，promptChars 记 0（DONE 里只统计回答侧 token 估算）
 return new ExecTarget(stream, conv.getProvider(), conv.getModel(), 0);
 }

 /**
 * 本轮的执行目标。
 *
 * @param stream      可直接订阅的消息流
 * @param provider    实际使用的 provider（落库与 done 事件要用）
 * @param model       实际使用的模型名
 * @param promptChars 提示词/证据的字符数（用于 token 估算；未知时为 0）
 */
 private record ExecTarget(Flux<org.springframework.ai.chat.messages.Message> stream,
 String provider, String model, int promptChars) {
 }

 // ==================================================================
 // 阶段一：准备（同步、事务内）
 // ==================================================================

 /**
 * 落库消息、预建助手行、按需自动命名。所有写操作放在一个事务里，拿到自增 id 后再返回。
 *
 * <p><b>必须是 public</b>：Spring 用代理实现事务，而代理只拦 <b>public</b> 方法 ——
 * 标在 protected/private 上不会报错，但事务会被<b>静默忽略</b>。
 * 同理，调用它也必须经 {@link #self} 代理（不能同类直接调）。
 * 这两点缺任何一个，"重新生成"（走派生删除，必须依赖外层事务）都会报
 * {@code InvalidDataAccessApiUsageException}。
 */
 @Transactional
 public Prepared prepare(Long userId, ChatRequest body) {
 Long convId = body.sessionId();
 Conversation conv = conversationRepository.findByIdAndUserId(convId, userId)
 .orElseThrow(() -> ApiException.notFound("会话不存在"));
 boolean regenerate = Boolean.TRUE.equals(body.regenerate());

 Long userMessageId;
 String sendText;

 if (regenerate) {
 // 重新生成：复用最后一条用户消息，删掉它之后的所有助手回答
 Message lastUser = messageRepository
 .findFirstByConversationIdAndRoleOrderByIdDesc(convId, "user")
 .orElseThrow(() -> ApiException.badRequest("没有可重新生成的历史消息"));
 sendText = lastUser.getContent();
 userMessageId = lastUser.getId();
 messageRepository.deleteByConversationIdAndRoleAndIdGreaterThan(
 convId, "assistant", lastUser.getId());
 } else {
 // 普通发送：落库用户消息
 String text = body.message();
 if (text == null || text.isBlank()) {
 throw ApiException.badRequest("消息内容不能为空");
 }
 sendText = text;

 Message um = new Message();
 um.setConversationId(convId);
 um.setRole("user");
 um.setContent(text);
 um.setRefFileIds(toRefFileIdsJson(body.fileIds()));
 Message saved = messageRepository.save(um);
 userMessageId = saved.getId();

 // 首轮自动命名：标题还是"新对话"且这是第一条消息时，
 // 用用户消息前 20 字当标题（早期实现 的 msg_count==0 判断）
 if ("新对话".equals(conv.getTitle())
 && messageRepository.countByConversationId(convId) <= 1) {
 conv.setTitle(truncateTitle(text));
 conversationRepository.save(conv);
 }
 }

 // 预建空助手消息：流式期间界面就显示这个气泡；断连/报错时也能回写部分内容
 Message am = new Message();
 am.setConversationId(convId);
 am.setRole("assistant");
 am.setContent("");
 am.setProvider(conv.getProvider());
 am.setModel(conv.getModel());
 Message savedAm = messageRepository.save(am);

 return new Prepared(conv, userMessageId, savedAm.getId(), sendText);
 }

 // ==================================================================
 // 阶段二：流事件处理
 // ==================================================================

 /** 每个 AI 消息帧：兼容增量/累积两种语义后，拆出思考与正文推给前端。 */
 private void onNext(org.springframework.ai.chat.messages.Message msg,
 SseEmitter emitter,
 ThinkSplitter splitter,
 StringBuilder answer,
 String[] seen,
 Long convId,
 AtomicBoolean sourcesEmitted,
 Long exchangeId,
 long startedAt) {
 // 只处理 AI（助手）消息；工具消息等内部流转的不向外暴露
 // ⚠️ Spring AI 的 MessageType 枚举值是 ASSISTANT（不是 AI；没有 AI 这个常量）
 if (!MessageType.ASSISTANT.equals(msg.getMessageType())) {
 return;
 }
 String text = msg.getText();
 if (text == null || text.isEmpty()) {
 return;
 }

 // 工具（search_knowledge_base）一定先于最终回答的 token 执行，
 // 所以首个 ASSISTANT 帧到达时 sources 收集器已就绪 —— 趁早发 sources 事件。
 // 放在 delta 计算之前，即使本帧 delta 为空也会先把来源推给前端。
 maybeEmitSources(emitter, convId, sourcesEmitted);

 // 兼容两种流式语义：①增量（每帧是新增片段）②累积（每帧是到目前为止的全文）
 String delta;
 if (!seen[0].isEmpty() && text.startsWith(seen[0])) {
 // ② 累积：本次增量 = 全文 减去 已见部分
 delta = text.substring(seen[0].length());
 seen[0] = text;
 } else {
 // ① 增量（或首帧）：整段即新增
 delta = text;
 seen[0] = seen[0] + text;
 }
 if (delta.isEmpty()) {
 return;
 }

 String[] parts = splitter.feed(delta);
 try {
 if (!parts[0].isEmpty()) {
 emitter.send(SseEmitter.event()
 .data(ChatStreamEvent.reasoning(parts[0]).toData()));
 }
 if (!parts[1].isEmpty()) {
 // 首字延迟：第一次真正吐出正文时记一笔（从请求开始算）。
 // 这是体验上最有价值的指标 —— 比总耗时更能反映"用户等了多久才看到东西"。
 if (answer.length() == 0) {
 traceService.record(convId, exchangeId, ChatTraceService.STAGE_FIRST_TOKEN,
 System.currentTimeMillis() - startedAt, null);
 }
 answer.append(parts[1]);
 emitter.send(SseEmitter.event()
 .data(ChatStreamEvent.token(parts[1]).toData()));
 }
 } catch (IOException e) {
 // 客户端可能已断开；onError/onTimeout 会负责清理，这里不打堆栈
 log.debug("token/reasoning 事件发送失败（客户端可能已断开）: {}", e.getMessage());
 }
 }

 /** 若本轮已检索到知识库来源且尚未发送，发一次 {@code sources} 事件（去重保序）。 */
 private void maybeEmitSources(SseEmitter emitter, Long convId, AtomicBoolean sourcesEmitted) {
 if (sourcesEmitted.get()) {
 return;
 }
 List<String> sink = ragSourcesBus.sink(convId);
 if (sink == null || sink.isEmpty()) {
 return;
 }
 // 工具已按出现顺序追加，这里再确保无重复（保留首次出现顺序）
 List<String> names = new ArrayList<>(new LinkedHashSet<>(sink));
 try {
 emitter.send(SseEmitter.event().data(ChatStreamEvent.sources(names).toData()));
 sourcesEmitted.set(true);
 } catch (IOException e) {
 log.debug("sources 事件发送失败（客户端可能已断开）: {}", e.getMessage());
 }
 }

 /** 流正常结束：冲掉拆分器缓冲 → 回写完整正文 + 发 done 事件（带回 user_id / assistant_id 供前端补 id）。 */
 private void onComplete(SseEmitter emitter, Long assistantId, Long convId,
 StringBuilder answer, String provider, String model,
 Long userMessageId, AtomicBoolean sourcesEmitted,
 ThinkSplitter splitter,
 long startedAt, int promptChars) {
 // ★★★ 收尾必须 flush 拆分器（对应 流式聊天 的 `splitter.flush()`，本实现一度漏掉）★★★
 // ThinkSplitter.feed() 每帧都会"故意保留末尾若干字符"（怕 thinking 标签跨帧被拆断），
 // 所以流结束时缓冲里一定还剩东西。不 flush 会有两个后果：
 // ① 回答结尾被"吃掉"几个字 —— 用户看到的就是"输出到一半就停住"；
 // ② 若模型开了思考却没吐出结束标签，缓冲里的正文会<b>整段丢失</b>。
 // 注意顺序：必须先 flush 并追加进 answer，再 persistAssistant，否则落库的正文也是缺尾的。
 // 1) 冲掉拆分器残留。每步各自兜异常 —— 收尾流程不能被某一帧的失败打断。
 try {
 String[] tail = splitter.flush();
 if (!tail[0].isEmpty()) {
 safeSend(emitter, ChatStreamEvent.reasoning(tail[0]).toData());
 }
 if (!tail[1].isEmpty()) {
 answer.append(tail[1]);
 safeSend(emitter, ChatStreamEvent.token(tail[1]).toData());
 }
 } catch (Exception e) {
 log.warn("收尾 flush 失败（不影响收尾）: {}", e.getMessage());
 }

 persistAssistant(assistantId, answer.toString(), provider, model); // 自身已吞异常

 // 收尾记录：总耗时 + token 估算（prompt 侧未知时记 0，只统计回答侧）。
 // 只在这条"正常结束"的路径记录 —— 超时/出错/断连那两条路径没有可靠的结束时刻。
 traceService.recordDone(convId, assistantId, System.currentTimeMillis() - startedAt,
 provider, model, promptChars, answer.length());

 // 2) ★★★ 无论前面发生什么，都必须把流"关掉" ★★★
 // 【真实 bug】原实现把 emitter.complete() 放在只 catch(IOException) 的 try 里，
 // 前面任一步抛"非受检异常"（emitter.send 在客户端已断开/已关闭时会抛
 // IllegalStateException）就会跳过 complete() → HTTP 连接一直挂着，
 // 直到 SSE 超时（本类常量 = 10 分钟）才断。
 // 前端因此读不到"流结束"信号 → 聊天框的【停止按钮长时间不复位】（用户反馈）。
 // 所以：done 事件单独 try，complete() 放 finally 无条件执行。
 try {
 // 兜底：若流里没机会发 sources（极少见，工具执行后无正文 token），这里补发一次。
 // 正常情况 sources 已在 onNext 首个正文帧之前发出，这里不会重复（sourcesEmitted 已置位）。
 maybeEmitSources(emitter, convId, sourcesEmitted);
 // 注意：done 里的 user_id 是刚落库的用户消息 id（不是登录用户 id），
 // assistant_id 是刚预建的助手消息 id —— 前端据此把本地气泡补上后端 id，
 // 否则删除按钮不显示、也无法定位
 safeSend(emitter, ChatStreamEvent.done(provider, model, userMessageId, assistantId).toData());
 } catch (Exception e) {
 log.warn("发送 done 事件失败: {}", e.getMessage());
 } finally {
 completeQuietly(emitter); // ★ 无条件关闭连接（止血点）
 // 流结束（无论成功与否）都清理本轮来源收集器，避免陈旧数据滞留与内存泄漏
 ragSourcesBus.unregister(convId);
 // 同时释放会话占位，让用户能接着发下一条（漏了这句，停止后本会话就被锁死了）
 streamGuard.release(convId);
 }
 }

 /** 流异常：回写部分内容 + 清记忆 + 发 error 事件（连接保持 200）。 */
 private void onError(Throwable err, SseEmitter emitter, Long assistantId, Long convId,
 StringBuilder answer, String provider, String model) {
 log.error("聊天流异常（会话 {}）: {}", convId, err.getMessage());
 persistAssistant(assistantId, answer.toString(), provider, model);
 // 清记忆：避免半截 tool_calls 历史污染后续请求（早期实现 的清理逻辑）。
 // 清记忆失败也绝不能挡住下面的"发 error + 关闭连接"。
 try {
 memory.clearMemory(convId);
 } catch (Exception e) {
 log.warn("清记忆失败（不影响收尾）: {}", e.getMessage());
 }
 try {
 String reason = err.getMessage() == null ? "生成失败" : err.getMessage();
 safeSend(emitter, ChatStreamEvent.error(reason).toData());
 } finally {
 completeQuietly(emitter); // ★ 错误路径同样必须无条件关闭，否则停止按钮一样会卡住
 ragSourcesBus.unregister(convId);
 streamGuard.release(convId); // 出错也必须释放占位，否则该会话再也发不出消息
 }
 }

 /** 客户端断开 / 超时：取消订阅 + 回写部分内容 + 清记忆。 */
 private void onAbort(Disposable disp, Long assistantId, Long convId,
 StringBuilder answer, String provider, String model) {
 if (disp != null) {
 disp.dispose();
 }
 persistAssistant(assistantId, answer.toString(), provider, model);
 try {
 memory.clearMemory(convId);
 } catch (Exception e) {
 log.warn("清记忆失败（不影响收尾）: {}", e.getMessage());
 }
 ragSourcesBus.unregister(convId);
 streamGuard.release(convId); // 断连/超时同样释放占位
 }

 /**
 * 发送一帧 SSE。<b>任何异常都只记日志、绝不外抛。</b>
 *
 * <p>收尾流程（尤其是"最后一帧 + 关闭连接"）不能被某一帧的失败打断 ——
 * 这是"停止按钮长时间不复位"那个 bug 的直接教训。
 */
 private void safeSend(SseEmitter emitter, Object data) {
 try {
 emitter.send(SseEmitter.event().data(data));
 } catch (Exception e) {
 log.debug("SSE 帧发送失败（客户端可能已断开）：{}", e.getMessage());
 }
 }

 /**
 * 无条件关闭 emitter；已关闭 / 已出错时抛出的异常一律忽略。
 *
 * <p><b>这是"停止按钮不复位"的根治点</b>：前端是靠"读到流结束"来把
 * loading 状态清掉、把「停止」换回「发送」的。只要有一条路径漏掉 close，
 * 连接就会一直挂到 {@link #SSE_TIMEOUT_MS}（10 分钟），用户看到的就是
 * "回答都生成完了，按钮还卡在停止"。
 */
 private void completeQuietly(SseEmitter emitter) {
 try {
 emitter.complete();
 } catch (Exception e) {
 log.debug("关闭 SSE 失败（可能已关闭）：{}", e.getMessage());
 }
 }

 // ==================================================================
 // 会话级互斥的兜底清扫
 // ==================================================================

 /**
 * 定时清扫超时占位（兜底）。
 *
 * <p>占位的正常释放靠三条收尾路径；但回调在极端情况下可能一个都不触发
 * （容器异常、连接被中间设备静默断开）。闸门自身的"超时接管"能兜住这种情况，
 * 但那要等到下一条请求进来才生效；定时清扫让状态维持在干净状态，也避免条目长期堆积。
 *
 * <p>每 60 秒扫一次足够（阈值是分钟级）。互斥语义本身见 {@link ConversationStreamGuard}。
 */
 @Scheduled(fixedDelay = 60_000L)
 public void sweepStaleStreams() {
 int removed = streamGuard.sweep();
 if (removed > 0) {
 log.warn("清扫超时的会话生成占位 {} 个（对应流的收尾回调未触发）", removed);
 }
 }

 // ==================================================================
 // 辅助
 // ==================================================================

 /**
 * 把已生成内容写回助手消息行。
 *
 * <p>content 为空（一个字都没推出来）时写成 {@code (回答生成已中断)}，
 * 这样界面上至少有个明确提示，而不是一个空气泡。部分内容或完整内容都走同一方法。</p>
 */
 private void persistAssistant(Long assistantId, String content, String provider, String model) {
 try {
 final String finalContent = (content == null || content.isBlank())
 ? "(回答生成已中断)" : content;
 messageRepository.findById(assistantId).ifPresent(m -> {
 m.setContent(finalContent);
 m.setProvider(provider);
 m.setModel(model);
 messageRepository.save(m);
 });
 } catch (Exception e) {
 // 回写失败绝不该影响已经推给前端的内容，只记日志
 log.warn("回写助手消息失败（助手 {}）: {}", assistantId, e.getMessage());
 }
 }

 /** 把附件 id 列表序列化成与解析端一致的 JSON 数组文本（如 {@code [3,7]}）。 */
 private static String toRefFileIdsJson(List<Long> fileIds) {
 if (fileIds == null || fileIds.isEmpty()) {
 return null;
 }
 StringBuilder sb = new StringBuilder("[");
 for (int i = 0; i < fileIds.size(); i++) {
 if (i > 0) {
 sb.append(",");
 }
 sb.append(fileIds.get(i));
 }
 sb.append("]");
 return sb.toString();
 }

 /** 取用户消息前 20 字（去空白）作为会话标题。 */
 private static String truncateTitle(String text) {
 String t = text.trim();
 int n = Math.min(TITLE_MAX_LEN, t.length());
 return t.substring(0, n);
 }

 /**
 * prepare 的返回：会话实体 + 两条消息的 id + 要发给模型的正文。
 * （包级可见而非 private —— 因为 {@link #prepare} 是 public，返回类型不该比方法更隐蔽。）
 */
 record Prepared(Conversation conv, Long userMessageId, Long assistantId, String sendText) {
 }
}
