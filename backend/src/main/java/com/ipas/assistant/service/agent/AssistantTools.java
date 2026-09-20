package com.ipas.assistant.service.agent;

import com.ipas.assistant.service.NotificationService;
import com.ipas.assistant.service.TodoService;
import com.ipas.assistant.service.WeatherService;
import com.ipas.assistant.service.rag.RagService;
import com.ipas.assistant.service.rag.RagSourcesBus;
import com.ipas.assistant.dto.NotificationDtos;
import com.ipas.assistant.dto.TodoDtos;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 的工具集。对应 。
 *
 * <h2>关键设计一：工具<b>复用 Service</b>，不另写一套数据访问</h2>
 *
 * <p>早期设计里待办和通知各有<b>两条入口</b>：
 * REST 接口（ / ）与 Agent 工具
 * （ 里的 {@code add_todo} / {@code notify_user}），
 * 两者写同一张表。早期设计是在工具函数里直接写 SQL 的。
 *
 * <p>本实现改成<b>工具调用同一批 Service</b>。原因很实际：
 * 如果各写一套，排序规则、默认值、事务边界很容易走岔 ——
 * 表现为"界面上看到的待办顺序，和 AI 列出来的不一样"这种诡异问题。
 * 而 {@code TodoService} 已经端到端验证过了，复用它是免费的。
 *
 * <h2>关键设计二：按用户隔离用"闭包"，<b>不把 userId 暴露给模型</b></h2>
 *
 * <p>待办、通知都是 per-user 数据，工具内部必须知道"这是哪个用户的操作"。
 * 但<b>绝不能把 userId 做成工具参数</b>：
 * <ul>
 * <li>模型可能传错（甚至被用户诱导传别人的 id），那就是越权；</li>
 * <li>对模型来说它也不该关心这个 —— 用户问"帮我记个待办"，
 * 模型只需要知道"记什么"，不需要知道"记给谁"。</li>
 * </ul>
 * 所以这里用 {@link #forUser} 返回一个<b>捕获了 userId 的内部类实例</b>，
 * 它的 {@code @Tool} 方法内部直接用闭包里的 userId。
 * 这正是早期设计 {@code make_todo_tools(user_id)} 的思路。
 *
 * <h2>本类与 {@code @Tool} 注解</h2>
 *
 * <p>方法上的 {@code @Tool(name=..., description=...)} 就是发给模型的"工具说明书"：
 * name 是调用标识，description 是行为描述。
 * <b>description 必须写清楚「用途 + 输入格式 + 返回什么」</b>，
 * 否则模型会误用或干脆不用（早期设计注释专门强调了这一点）。
 *
 * <p>⚠️ 工具名与 {@code SystemPrompts.BASE} 里列出的名字<b>必须逐字一致</b>，
 * 改任意一边都要同步另一边。
 */
@Component
public class AssistantTools {

 private final WeatherService weatherService;
 private final TodoService todoService;
 private final NotificationService notificationService;
 private final RagService ragService;
 private final RagSourcesBus ragSourcesBus;

 public AssistantTools(WeatherService weatherService,
 TodoService todoService,
 NotificationService notificationService,
 RagService ragService,
 RagSourcesBus ragSourcesBus) {
 this.weatherService = weatherService;
 this.todoService = todoService;
 this.notificationService = notificationService;
 this.ragService = ragService;
 this.ragSourcesBus = ragSourcesBus;
 }

 /**
 * 构造「绑定到某个用户 + 某次会话」的工具集合。
 *
 * <p>调用方（{@code AgentFactory}）把这个数组交给
 * {@code ReactAgent.builder().tools(...)}。</p>
 *
 * <p><b>闭包捕获的维度</b>：
 * <ul>
 * <li>{@code userId} —— 多用户隔离（工具内部落库/检索都限定本用户）；</li>
 * <li>{@code kbIds} —— 本次对话勾选的知识库集合（RAG 只在这范围内检索；
 * 空集合 = 检索该用户全部知识库）；</li>
 * <li>{@code conversationId} —— 仅用来在 {@link RagSourcesBus} 里定位"本轮请求的
 * 来源收集器"，不暴露给模型（见该类的注释，解决 Agent 缓存复用与每请求收集器的冲突）。</li>
 * </ul>
 *
 * @param userId 当前用户（多用户隔离依据，不暴露给模型）
 * @param kbIds 本次对话启用的知识库 id 集合（空 = 全部库）
 * @param conversationId 会话 id（仅用于 RagSourcesBus 索引，不暴露给模型）
 * @return 工具回调数组
 */
 public ToolCallback[] forUser(Long userId, List<Long> kbIds, Long conversationId) {
 // ToolCallbacks.from 会扫描对象上的 @Tool 方法并包装成模型可调用的回调
 return ToolCallbacks.from(new ScopedTools(userId, kbIds, conversationId));
 }

 /**
 * 承载 {@code @Tool} 方法的载体，闭包捕获 userId / kbIds / conversationId。
 *
 * <p>做成非静态内部类（而不是静态嵌套类 + 显式 field），
 * 是为了让"这些工具天然属于某个用户"这件事在类型上就成立 ——
 * 静态类可以被任意 new 出来，容易被误用到别的用户上。
 *
 * <p>注意它是 {@code public} 而非 private：Spring AI 的
 * {@code ToolCallbacks.from} 需要通过反射调用这些方法，
 * 非 public 方法在某些 JDK/模块配置下会因反射限制而失败。</p>
 */
 public final class ScopedTools {

 private final Long userId;
 private final List<Long> kbIds;
 private final Long conversationId;

 private ScopedTools(Long userId, List<Long> kbIds, Long conversationId) {
 this.userId = userId;
 this.kbIds = kbIds == null ? List.of() : kbIds;
 this.conversationId = conversationId;
 }

 // ==============================================================
 // 通用工具（无状态，不涉及用户数据）
 // ==============================================================

 @Tool(name = "calculator",
 description = "计算数学表达式。输入一个算术表达式字符串，"
 + "例如 '2+3*4' 或 '(1+2)*10'，返回计算结果。")
 public String calculator(
 @ToolParam(description = "算术表达式，支持 + - * / % // ** 与括号")
 String expression) {
 try {
 double value = SafeCalculator.evaluate(expression);
 return SafeCalculator.format(value);
 } catch (Exception e) {
 // 工具失败也返回可读文本而不是抛异常 ——
 // 让模型看到"计算错误: xxx"并据此向用户解释，
 // 比让整个 Agent 循环崩掉好得多
 return "计算错误: " + e.getMessage();
 }
 }

 @Tool(name = "get_weather",
 description = "查询指定城市的实时天气。支持中文或英文城市名，"
 + "例如 '北京'、'Beijing'，返回温度与天气描述。")
 public String getWeather(
 @ToolParam(description = "城市名，中英文均可") String city) {
 return weatherService.current(city);
 }

 @Tool(name = "get_weather_forecast",
 description = "查询指定城市未来几天的天气预报。"
 + "输入城市中文名（如 '北京'）和天数 days（1~4，默认 3，包含今天），"
 + "返回每天白天/夜间的天气状况与气温。")
 public String getWeatherForecast(
 @ToolParam(description = "城市名，中英文均可") String city,
 @ToolParam(description = "预报天数 1~4，含今天", required = false) Integer days) {
 return weatherService.forecast(city, days == null ? 3 : days);
 }

 // ==============================================================
 // 按用户隔离的工具（用闭包里的 userId，不接受模型传参）
 // ==============================================================

 @Tool(name = "add_todo",
 description = "添加一条待办事项。输入待办内容文本，例如 '写课程报告'，"
 + "返回添加成功提示。")
 public String addTodo(
 @ToolParam(description = "待办内容") String task) {
 TodoDtos.Out created = todoService.create(userId, new TodoDtos.Create(task));
 return "已添加待办：" + created.id() + " - " + created.task();
 }

 @Tool(name = "list_todos",
 description = "列出当前用户的所有待办事项。无需输入参数，返回待办清单。")
 public String listTodos() {
 List<TodoDtos.Out> todos = todoService.list(userId);
 if (todos.isEmpty()) {
 return "暂无待办事项";
 }
 StringBuilder sb = new StringBuilder();
 for (TodoDtos.Out t : todos) {
 if (sb.length() > 0) {
 sb.append("\n");
 }
 sb.append(t.id()).append(". ")
 .append(Boolean.TRUE.equals(t.done()) ? "[x]" : "[ ]")
 .append(" ").append(t.task());
 }
 return sb.toString();
 }

 @Tool(name = "notify_user",
 description = "向当前用户推送一条站内通知（铃铛提醒）。"
 + "适合用于定时提醒、日程事项、重要提示等场景。"
 + "输入 title（必填，简短标题）与 body（可选，补充说明）。")
 public String notifyUser(
 @ToolParam(description = "通知标题，简短") String title,
 @ToolParam(description = "通知正文，可选", required = false) String body) {
 // 标题截断到 200 字与早期设计一致（数据库列就是 varchar(200)，
 // 不截断会因超长写入失败，而那会让整个工具调用报错）
 String safeTitle = title == null ? "" : title;
 if (safeTitle.length() > 200) {
 safeTitle = safeTitle.substring(0, 200);
 }
 notificationService.create(userId,
 new NotificationDtos.Create(safeTitle, body, null));
 return "已发送通知：" + safeTitle;
 }

 // ==============================================================
 // 知识库检索（RAG，按用户隔离 + 按会话收集来源）
 // ==============================================================

 @Tool(name = "search_knowledge_base",
 description = "在用户上传的知识库文档中检索最相关的片段。当用户询问其上传资料"
 + "（课程笔记、报告、说明书、规范、合同等）里的具体内容时使用。"
 + "输入检索问题，返回若干相关片段并标注来源文件名。")
 public String searchKnowledgeBase(
 @ToolParam(description = "检索问题，描述你想从资料里找的内容") String query) {
 RagService.Result res = ragService.retrieve(userId, query, kbIds);
 if (res.hits().isEmpty()) {
 return "知识库中没有找到相关内容（可能尚未上传文档、文档未建立索引，"
 + "或本次对话未启用对应知识库）";
 }
 // 把命中的来源文件名写入本轮请求的收集器，供 ChatService 发 sources 事件
 // （工具一定先于最终回答的 token 执行，所以首个正文 token 到达时这里已就绪）
 List<String> sink = ragSourcesBus.sink(conversationId);
 if (sink != null) {
 sink.addAll(res.sourceNames());
 }
 // 拼成「【文件名】片段」文本交给模型，模型据此在回答里注明来源
 StringBuilder sb = new StringBuilder();
 for (RagService.Hit h : res.hits()) {
 if (sb.length() > 0) {
 sb.append("\n\n");
 }
 sb.append("【").append(h.fileName()).append("】").append(h.text());
 }
 return sb.toString();
 }
 }
}
