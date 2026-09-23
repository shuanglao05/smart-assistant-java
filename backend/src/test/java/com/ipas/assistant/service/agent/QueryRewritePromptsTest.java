package com.ipas.assistant.service.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询改写的提示词构造与结果解析测试。
 *
 * <p>重点在<b>解析容错</b>：模型很少老老实实只吐 JSON，常见的有三种偏差 ——
 * 包 ```json 代码块、前后加一句客套话、干脆只给纯文本。任何解析失败都必须
 * <b>退回原问题</b>，否则检索会拿到空串，召回直接归零。
 */
class QueryRewritePromptsTest {

    @Test
    @DisplayName("解析：标准 JSON 取 query 字段")
    void parsePlainJson() {
        assertEquals("年假申请流程", QueryRewritePrompts.parse("{\"query\":\"年假申请流程\"}", "原问题"));
    }

    @Test
    @DisplayName("解析：剥掉 ```json 代码围栏")
    void parseFencedJson() {
        String out = "```json\n{\"query\":\"差旅报销标准\"}\n```";
        assertEquals("差旅报销标准", QueryRewritePrompts.parse(out, "原问题"));
    }

    @Test
    @DisplayName("解析：JSON 前后夹带客套话也能取出来")
    void parseJsonWithSurroundingText() {
        String out = "好的，改写如下：\n{\"query\":\"合同第 3.2 条的违约责任\"}\n希望有帮助。";
        assertEquals("合同第 3.2 条的违约责任", QueryRewritePrompts.parse(out, "原问题"));
    }

    @Test
    @DisplayName("解析：模型只给了纯文本短句 → 直接采用")
    void parsePlainText() {
        assertEquals("年假申请流程", QueryRewritePrompts.parse("年假申请流程", "原问题"));
    }

    @Test
    @DisplayName("解析：空输出 / 合法 JSON 但缺 query 字段 → 退回原问题")
    void parseFallsBackWhenUnusable() {
        assertEquals("原问题", QueryRewritePrompts.parse("", "原问题"));
        assertEquals("原问题", QueryRewritePrompts.parse(null, "原问题"));
        assertEquals("原问题", QueryRewritePrompts.parse("   ", "原问题"));
        // 合法 JSON 却没 query：绝不能把这串 JSON 本身当查询
        assertEquals("原问题", QueryRewritePrompts.parse("{\"foo\":\"bar\"}", "原问题"));
    }

    @Test
    @DisplayName("解析：超长输出（模型写了一整段解释）→ 退回原问题")
    void parseFallsBackOnTooLongOutput() {
        String longText = "这是一段很长的说明".repeat(40);
        assertEquals("原问题", QueryRewritePrompts.parse(longText, "原问题"));
    }

    @Test
    @DisplayName("历史拼接：只保留最近若干条，且逐条截断")
    void historyIsTrimmed() {
        List<String[]> history = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            history.add(new String[]{"user", "第" + i + "句"});
        }
        String formatted = QueryRewritePrompts.formatHistory(history);

        assertFalse(formatted.contains("第1句"), "太早的历史应被裁掉");
        assertTrue(formatted.contains("第10句"), "最近的历史应保留");
        assertTrue(formatted.contains("用户: 第10句"), "应带角色前缀");

        // 超长内容逐条截断
        String longOne = "x".repeat(QueryRewritePrompts.HISTORY_MAX_CHARS_PER_MESSAGE + 50);
        String one = QueryRewritePrompts.formatHistory(
                List.<String[]>of(new String[]{"assistant", longOne}));
        assertTrue(one.contains("…"), "超长内容应被截断标记");
    }

    @Test
    @DisplayName("历史为空时给出占位文本（提示词模板不留空洞）")
    void emptyHistoryHasPlaceholder() {
        assertEquals("（无历史）", QueryRewritePrompts.formatHistory(List.of()));
        assertEquals("（无历史）", QueryRewritePrompts.formatHistory(null));
    }

    @Test
    @DisplayName("启发式：短句或含指代词才需要改写（省掉无谓的模型调用）")
    void contextDependenceHeuristic() {
        assertTrue(QueryRewritePrompts.looksContextDependent("那它怎么配置"));
        assertTrue(QueryRewritePrompts.looksContextDependent("这个呢"));
        assertTrue(QueryRewritePrompts.looksContextDependent("该文档里有吗"));
        assertFalse(QueryRewritePrompts.looksContextDependent(
                "差旅报销的标准和审批流程分别是什么"), "已完整的问句不该再改写");
        assertFalse(QueryRewritePrompts.looksContextDependent(null));
    }
}
