package com.ipas.assistant.common;

/**
 * Agent 的系统提示词。对应 的 {@code SYSTEM_PROMPT} 常量。
 *
 * <h2>为什么做成常量而不是配置项</h2>
 *
 * <p>早期设计里它也是<b>硬编码在 配置模块 里的常量</b>，不从 .env 读 ——
 * 所以这里保持一致，没有放进 {@code application.yml}。
 *
 * <p>更实际的原因：这段提示词里<b>写死了工具名</b>（calculator / get_weather /
 * add_todo / notify_user / search_web / search_knowledge_base）。
 * 工具集一旦增删，提示词必须跟着改，两者是<b>强耦合</b>的。
 * 把它做成"用户可随意改的配置"，一旦改了工具名就会静默失配
 * （模型会去调一个不存在的工具，或该调的没调），排查起来很绕。
 *
 * <h2>⚠️ 改动时注意</h2>
 *
 * <p>提示词里的工具名必须与实际注册的工具名<b>逐字一致</b>。
 * 工具名由 {@code @Tool(name = "...")} 决定，见
 * {@code service/agent/AssistantTools}。
 * 改任意一边都要同步另一边。
 */
public final class SystemPrompts {

 private SystemPrompts() {
 }

 /**
 * 基础系统提示词（原文逐字实现）。
 *
 * <p>三段内容的用意（原注释里没写，这里补上，便于将来调整）：
 *
 * <p><b>〔工具使用原则〕</b>——这是整段里最重要的部分。
 * 大模型对"能不能调工具"的边界判断很松，不加约束会出现：
 * 用户只是聊天说"我要背《望岳》"，模型就自作主张去调 add_todo 记了一条待办。
 * 所以原文用三条规则把边界收紧：只在明确要求执行动作时才调工具、
 * 写数据的操作格外谨慎（拿不准先问）、不编造结果。
 * <b>第 2 条尤其关键</b>：{@code add_todo} 和 {@code notify_user} 会写库，
 * 误调的后果是用户看到一个自己没要求的待办/通知。
 *
 * <p><b>〔工具清单〕</b>——逐个列出工具名与使用场景。
 * 虽然工具本身带 description（也会发给模型），但在这里再列一遍能显著提高
 * 模型选对工具的概率 —— 尤其是 {@code get_weather}（实况）与
 * {@code get_weather_forecast}（预报）这种名字相近的。
 *
 * <p><b>〔画图约定〕</b>——要求用 Mermaid 代码块而不是文字描述图形。
 * 前端的 {@code MermaidBlock} 组件会把 {@code ```mermaid} 代码块渲染成图，
 * 模型若用文字描述，用户看到的就是一段大白话而不是图。
 */
 public static final String BASE = """
 你是一个乐于助人的中文智能个人助理。

 【工具使用原则】（重要）
 1. 只在用户【明确要求执行某个动作】时才调用工具；仅仅聊天、提问、陈述、
 或顺口提到某件事（如「我要背《望岳》」「这个我不会」），一律不要调用工具，
 直接用文字回答即可。
 2. 会【写入数据】的操作要格外谨慎——尤其是 add_todo（新增待办）和
 notify_user（发通知）：必须用户明确表达「帮我记/加进待办」「提醒我」等意图
 才执行；拿不准时先问一句「需要我帮你记成待办吗？」，不要擅自写入。
 3. 不要编造结果；调用了工具就基于真实返回值回答，且不要复述原始数据。

 【工具清单】
 - 算数：calculator
 - 天气：当前实况 get_weather；未来几天（days 1~4）get_weather_forecast；城市名支持中英文
 - 待办：用户明确要「记一条待办 / 加进待办清单」用 add_todo；明确要「看看待办」用 list_todos
 - 提醒：用户明确要「提醒我 / 发个通知」用 notify_user（title 简短）
 - 联网资料：search_web
 - 用户上传的资料/知识库（笔记、报告、说明书、规范等）：search_knowledge_base
 - 画图（流程图/时序图/关系图/柱状图/折线图）：用 ```mermaid 代码块输出
 （流程图 graph TD/LR，时序图 sequenceDiagram，柱状图/折线图 xychart-beta），不要用文字描述图形。

 回答使用简体中文，保持简洁。""";

 /**
 * 拼接「启用技能」的附加指令。对应 {@code agent_manager} 里那段
 * {@code extra} 拼接逻辑。
 *
 * <p>格式（{@code 【技能名】描述\n指令正文}）与原文逐字一致 ——
 * 它没有功能意义，但改动它会让"技能对小模型的有效性"发生变化，
 * 而这是原作者实测调过的，不轻易动。
 *
 * @param base 基础提示词
 * @param skills 已启用的技能（按 id 升序，保证同样的技能集拼出同样的提示词，
 * 从而让 Agent 缓存键稳定 —— 顺序抖动会导致反复重建）
 */
 public static String withSkills(String base, java.util.List<SkillPrompt> skills) {
 if (skills == null || skills.isEmpty()) {
 return base;
 }
 StringBuilder sb = new StringBuilder(base);
 sb.append("\n\n当前会话已启用以下技能，请在回答中综合运用：\n");
 for (int i = 0; i < skills.size(); i++) {
 SkillPrompt s = skills.get(i);
 if (i > 0) {
 sb.append("\n\n");
 }
 sb.append("【").append(s.name()).append("】")
 .append(s.description() == null ? "" : s.description())
 .append("\n").append(s.prompt());
 }
 return sb.toString();
 }

 /**
 * 技能提示词的载体。
 *
 * <p>只用最小字段集而不是直接传 {@code Skill} 实体：提示词拼接不需要
 * userId、时间戳那些字段，收窄类型能让这个方法的依赖更清晰，
 * 也便于测试时不必构造完整实体。
 */
 public record SkillPrompt(String name, String description, String prompt) {
 }
}
