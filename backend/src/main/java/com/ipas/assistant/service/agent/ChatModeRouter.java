package com.ipas.assistant.service.agent;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对话模式路由：决定一轮提问走"确定性知识问答"还是"开放式 Agent"。
 *
 * <h2>为什么需要路由</h2>
 *
 * <p>把一切都交给 ReAct Agent 有个固有缺陷：<b>"要不要检索知识库"由模型自己决定</b>。
 * 同一句"帮我看看这份资料里的报销标准"，模型这次可能调检索工具、下次不调，
 * 结果不可复现；而知识问答一旦不检索，就很容易编答案。确定性路径的价值就是把
 * "必须检索"变成代码的硬约束，而不是模型的自觉。
 *
 * <h2>为什么判定要"保守"</h2>
 *
 * <p>路由判错的代价并不对称：
 * <ul>
 * <li>该走确定性路径却走了 Agent → 只是回到优化前的行为，不坏；</li>
 * <li>该走 Agent 却走了确定性路径 → <b>功能受损</b>：用户说"帮我记个待办"，系统却去检索资料；
 * 或者让写一首诗，却回答"未检索到足够证据"。</li>
 * </ul>
 * 所以判定条件取"<b>有知识库 + 明确是问内容的问句 + 不是工具型/闲聊型意图</b>"，
 * 而不是"不是工具意图就都算知识问答"——后者会把创作、闲聊、工具请求全部误判进来。
 *
 * <p>另外，确定性检索<b>没有找到证据时会回退给 Agent</b>（见 {@code KbQaService} 的调用方），
 * 所以即便这里的判定偏严，"问资料里没有的东西"也不会变成生硬的"没有证据"。
 */
@Component
public class ChatModeRouter {

    /** 一轮提问的执行模式。 */
    public enum Mode {
        /** 确定性知识问答：先检索资料、再把证据交给模型生成。 */
        KB_QA,
        /** 开放式 Agent：走带工具的 ReAct 循环（写写画画、待办、天气、闲聊等）。 */
        OPEN_AGENT
    }

    /**
     * 工具型意图关键词：命中就走 Agent。
     *
     * <p>这些都是"要执行一个动作"而不是"问资料里的内容"，确定性检索帮不上忙，
     * 会直接把功能做坏，所以必须优先排除。
     */
    private static final List<String> TOOL_INTENT_KEYWORDS = List.of(
            "帮我记", "记一下", "记录下来", "加个待办", "添加到待办", "新建待办", "待办",
            "提醒我", "提醒一下", "通知我", "发个通知", "日程", "安排一下",
            "天气", "气温", "下雨", "算一下", "帮我算", "计算一下", "等于多少",
            "写一首", "写个", "写一篇", "起个名", "取个名", "翻译");

    /** 闲聊 / 能力询问关键词：命中就走 Agent（这类问题检索资料毫无意义）。 */
    private static final List<String> SMALL_TALK_KEYWORDS = List.of(
            "你好", "您好", "哈喽", "谢谢", "多谢", "感谢", "再见", "拜拜",
            "你能做什么", "你会什么", "你是谁", "你是干什么的",
            "介绍一下你", "自我介绍", "有什么功能", "有哪些功能", "怎么用你");

    /**
     * 问内容的问题特征词：<b>至少要命中一个</b>才认为可能是知识问答。
     *
     * <p>用"需要命中"而不是"不能命中排除词"，是因为排除法永远列不全 ——
     * 用户能说出无穷多种非知识型请求。宁可漏判（退回 Agent，行为不变），
     * 也不要误判（把功能做坏）。
     */
    private static final List<String> QUESTION_HINTS = List.of(
            "是什么", "什么是", "什么叫", "怎么", "怎样", "如何", "为什么", "为何",
            "哪些", "哪个", "哪个", "多少", "多久", "几点", "是否", "有没有", "能不能",
            "介绍", "说明", "解释", "区别", "差异", "对比", "步骤", "流程", "规定",
            "标准", "要求", "条件", "配置", "参数", "默认值", "怎么设置", "在哪",
            "文档里", "资料里", "笔记里", "文件里", "报告里", "手册里", "合同里",
            "上面说", "里面写");

    /**
     * 判定执行模式。
     *
     * @param question 用户这一轮的原始提问
     * @param kbIds    本会话勾选的知识库 id（空 = 没启用知识库）
     * @return {@link Mode#OPEN_AGENT} 或 {@link Mode#KB_QA}
     */
    public Mode route(String question, List<Long> kbIds) {
        // 没启用知识库 → 确定性检索无事可做
        if (kbIds == null || kbIds.isEmpty()) {
            return Mode.OPEN_AGENT;
        }
        if (question == null || question.isBlank()) {
            return Mode.OPEN_AGENT;
        }
        String q = question.strip();

        // 工具型 / 闲聊型意图优先排除（这两类最怕被误判）
        if (containsAny(q, TOOL_INTENT_KEYWORDS) || containsAny(q, SMALL_TALK_KEYWORDS)) {
            return Mode.OPEN_AGENT;
        }

        // 必须"像在问内容"：命中问句特征词，或以问号结尾
        boolean looksLikeQuestion = containsAny(q, QUESTION_HINTS)
                || q.endsWith("?") || q.endsWith("？");
        return looksLikeQuestion ? Mode.KB_QA : Mode.OPEN_AGENT;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
