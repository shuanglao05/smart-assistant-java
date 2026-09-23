package com.ipas.assistant.service.agent;

import com.ipas.assistant.service.agent.ChatModeRouter.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 对话模式路由的单元测试。
 *
 * <p>这套判定直接决定"用户的功能会不会被做坏"：把"帮我记个待办"误判成知识问答，
 * 用户就会看到系统跑去翻资料。所以用例重点覆盖<b>必须排除</b>的几类意图。
 */
class ChatModeRouterTest {

    private final ChatModeRouter router = new ChatModeRouter();
    private static final List<Long> KB = List.of(1L);

    @Test
    @DisplayName("未启用知识库 → 一律走 Agent（确定性检索无事可做）")
    void noKnowledgeBaseGoesToAgent() {
        assertEquals(Mode.OPEN_AGENT, router.route("报销标准是什么", List.of()));
        assertEquals(Mode.OPEN_AGENT, router.route("报销标准是什么", null));
    }

    @Test
    @DisplayName("问内容的问句 + 有知识库 → 走确定性知识问答")
    void knowledgeQuestionGoesToKbQa() {
        assertEquals(Mode.KB_QA, router.route("报销标准是什么", KB));
        assertEquals(Mode.KB_QA, router.route("合同里怎么写的规定", KB));
        assertEquals(Mode.KB_QA, router.route("app.rag.top-k 默认值是多少", KB));
    }

    @Test
    @DisplayName("以问号结尾也算问句（用问号判定，不依赖特征词）")
    void questionMarkCountsAsQuestion() {
        assertEquals(Mode.KB_QA, router.route("第三章讲了什么内容吗？", KB));
        assertEquals(Mode.KB_QA, router.route("这个参数可以改吗?", KB));
    }

    @Test
    @DisplayName("工具型意图必须走 Agent（否则功能会被做坏）")
    void toolIntentGoesToAgent() {
        assertEquals(Mode.OPEN_AGENT, router.route("帮我记一下明天交报告", KB));
        assertEquals(Mode.OPEN_AGENT, router.route("添加到待办：买牛奶", KB));
        assertEquals(Mode.OPEN_AGENT, router.route("提醒我今天下午开会", KB));
        assertEquals(Mode.OPEN_AGENT, router.route("北京天气怎么样", KB));
        assertEquals(Mode.OPEN_AGENT, router.route("帮我算一下 12 乘以 8 等于多少", KB));
    }

    @Test
    @DisplayName("创作型请求走 Agent（不该被检索拦下）")
    void creativeRequestGoesToAgent() {
        assertEquals(Mode.OPEN_AGENT, router.route("帮我写一首关于春天的诗", KB));
        assertEquals(Mode.OPEN_AGENT, router.route("把这个翻译成英文", KB));
    }

    @Test
    @DisplayName("闲聊 / 能力询问走 Agent")
    void smallTalkGoesToAgent() {
        assertEquals(Mode.OPEN_AGENT, router.route("你好", KB));
        assertEquals(Mode.OPEN_AGENT, router.route("你能做什么", KB));
        assertEquals(Mode.OPEN_AGENT, router.route("谢谢你", KB));
    }

    @Test
    @DisplayName("没有问句特征、也不是工具意图 → 保守走 Agent（漏判好过误判）")
    void nonQuestionFallsBackToAgent() {
        assertEquals(Mode.OPEN_AGENT, router.route("随便聊聊最近发生的事吧", KB));
        assertEquals(Mode.OPEN_AGENT, router.route("嗯", KB));
    }

    @Test
    @DisplayName("空问题走 Agent（避免无意义地走检索）")
    void blankQuestionGoesToAgent() {
        assertEquals(Mode.OPEN_AGENT, router.route("   ", KB));
        assertEquals(Mode.OPEN_AGENT, router.route(null, KB));
    }
}
