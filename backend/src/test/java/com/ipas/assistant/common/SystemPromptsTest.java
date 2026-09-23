package com.ipas.assistant.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础系统提示词的单元测试。
 *
 * <p>这些断言守的是提示词里最容易被"改错一边"的两处耦合：
 * <ol>
 * <li><b>工具名</b>：提示词里逐个列了工具名，而工具名由 {@code @Tool(name=...)} 决定。
 * 重命名工具却忘了改提示词 → 模型会去调一个不存在的工具（或该调的没调），
 * 而且不报错、只表现成"模型变笨了"。这个断言能在改错时立刻红。</li>
 * <li><b>技能拼接</b>：拼接顺序必须稳定（按 id 升序），否则同样的技能集会拼出不同的提示词，
 * 导致 Agent 缓存键抖动、Agent 被反复重建。</li>
 * </ol>
 */
class SystemPromptsTest {

    @Test
    @DisplayName("提示词里列出的工具名必须存在（与 @Tool 的名字逐字一致）")
    void baseListsAllRealToolNames() {
        String base = SystemPrompts.BASE;

        for (String tool : List.of("calculator", "get_weather", "get_weather_forecast",
                "add_todo", "list_todos", "notify_user", "search_knowledge_base")) {
            assertTrue(base.contains(tool), "提示词里应提到工具 " + tool + "（重命名工具时要同步改这里）");
        }
    }

    @Test
    @DisplayName("提示词要求用 Mermaid 代码块画图（否则前端渲染不出图）")
    void baseRequiresMermaidBlocks() {
        assertTrue(SystemPrompts.BASE.contains("mermaid"),
                "必须要求用 ```mermaid 代码块，否则前端 MermaidBlock 渲染不了");
    }

    @Test
    @DisplayName("技能拼接：按传入顺序稳定拼接，并带上技能名与正文")
    void withSkillsAppendsInOrder() {
        String result = SystemPrompts.withSkills("基础", List.of(
                new SystemPrompts.SkillPrompt("写作", "更书面", "用书面语。"),
                new SystemPrompts.SkillPrompt("严谨", "标注来源", "每个结论都要给出依据。")));

        assertTrue(result.startsWith("基础"));
        assertTrue(result.contains("【写作】更书面"));
        assertTrue(result.contains("【严谨】标注来源"));
        assertTrue(result.indexOf("【写作】") < result.indexOf("【严谨】"), "顺序必须与传入顺序一致");
    }

    @Test
    @DisplayName("技能拼接：没有技能时原样返回（不额外追加任何内容）")
    void withSkillsWithoutSkillsReturnsBase() {
        assertEquals("基础", SystemPrompts.withSkills("基础", List.of()));
        assertEquals("基础", SystemPrompts.withSkills("基础", null));
    }
}
