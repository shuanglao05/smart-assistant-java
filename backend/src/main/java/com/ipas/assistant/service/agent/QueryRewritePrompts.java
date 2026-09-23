package com.ipas.assistant.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 检索查询改写用的提示词构造与结果解析（纯函数，便于单测）。
 *
 * <h2>要解决的问题</h2>
 *
 * <p>多轮对话里用户大量使用省略与指代："那它怎么配置？"、"这个的默认值是多少？"。
 * 把这种句子直接丢给检索，"它/这个"两个词什么也匹配不到 —— 召回基本是废的。
 * 改写就是结合上一轮内容把它补成一句能独立检索的话。
 *
 * <h2>为什么把"构造"和"解析"单独抽出来</h2>
 *
 * <p>真正容易出错的不是"调模型"，而是两端的细节：
 * <ul>
 * <li>提示词要让模型只输出结构化结果，不能夹带解释；</li>
 * <li>但模型实际经常不听话 —— 返回 ```json 代码块、加一句"好的，改写如下："、
 * 或者干脆只给一句纯文本。解析必须容错，<b>任何解析失败都要退回原问题</b>，
 * 绝不能因为改写失败就让检索拿到空串。</li>
 * </ul>
 * 这些边界全是纯字符串逻辑，抽出来就能穷举断言，不必真的连模型。
 */
public final class QueryRewritePrompts {

    private QueryRewritePrompts() {
        // 工具类，不允许实例化
    }

    /** 历史最多带几条（越近越相关，带太多反而稀释注意力）。 */
    public static final int HISTORY_MAX_MESSAGES = 6;
    /** 历史每条最多截断多少字符（避免改写提示词本身过长）。 */
    public static final int HISTORY_MAX_CHARS_PER_MESSAGE = 200;
    /** 解析时认为"像一句查询"的最大长度；超过就不敢当成查询用。 */
    private static final int MAX_PLAIN_QUERY_LEN = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 构造改写提示词。
     *
     * @param question 用户本轮原始提问
     * @param history  最近若干轮对话，每项 {@code [role, content]}；可为空
     */
    public static String build(String question, List<String[]> history) {
        return """
                你是检索查询改写助手。请结合对话历史，把用户的问题改写为一条【可以独立检索】的查询语句。

                要求：
                1. 补全代词与省略的主语（例如"它""这个"要替换成历史里明确指代的对象）；
                2. 保留原问题里的具体名称、编号、配置项、数字，不要臆造新信息；
                3. 只输出 JSON，形如 {"query":"改写后的查询"}，不要任何解释或多余文字。

                【对话历史】
                %s

                【用户问题】
                %s
                """.formatted(formatHistory(history), question == null ? "" : question.strip());
    }

    /**
     * 把历史拼成"角色: 内容"的多行文本（只保留最近若干条，并逐条截断）。
     */
    public static String formatHistory(List<String[]> history) {
        if (history == null || history.isEmpty()) {
            return "（无历史）";
        }
        int from = Math.max(0, history.size() - HISTORY_MAX_MESSAGES);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < history.size(); i++) {
            String[] row = history.get(i);
            if (row == null || row.length < 2) {
                continue;
            }
            String roleLabel = "user".equalsIgnoreCase(row[0]) ? "用户" : "助手";
            String content = row[1] == null ? "" : row[1].strip();
            if (content.length() > HISTORY_MAX_CHARS_PER_MESSAGE) {
                content = content.substring(0, HISTORY_MAX_CHARS_PER_MESSAGE) + "…";
            }
            sb.append(roleLabel).append(": ").append(content).append('\n');
        }
        return sb.isEmpty() ? "（无历史）" : sb.toString().strip();
    }

    /**
     * 解析模型输出，取出改写后的查询；<b>任何异常或可疑情况都退回原问题</b>。
     *
     * <p>容错顺序：
     * <ol>
     * <li>剥掉 ```json / ``` 代码围栏与常见前缀；</li>
     * <li>尝试按 JSON 解析，取 {@code query} 字段；</li>
     * <li>若整段是短的纯文本（模型没用 JSON），就当它是改写结果；</li>
     * <li>其余情况（空、过长、解析失败）一律退回原问题。</li>
     * </ol>
     *
     * @param modelOutput 模型原始输出
     * @param fallback    退回时使用的原问题
     */
    public static String parse(String modelOutput, String fallback) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return fallback;
        }
        String text = modelOutput.strip();

        // ① 剥代码围栏
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
            text = text.strip();
        }

        // ② 按 JSON 取 query 字段（取第一个 { 到最后一个 } 之间的部分，容忍前后夹带文字）
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open >= 0 && close > open) {
            String candidate = text.substring(open, close + 1);
            try {
                JsonNode node = JSON.readTree(candidate);
                JsonNode query = node.get("query");
                if (query != null && !query.asText("").isBlank()) {
                    return query.asText().strip();
                }
                // 是合法 JSON 但没有 query 字段 → 模型没按约定返回，退回原问题
                // （不能把这段 JSON 文本本身当查询用）
                return fallback;
            } catch (Exception ignore) {
                // 不是合法 JSON → 落到下面的"纯文本"分支
            }
        }

        // ③ 模型直接给了纯文本（排除形似 JSON 的残片）
        if (!text.isEmpty() && text.length() <= MAX_PLAIN_QUERY_LEN
                && !text.contains("\n") && !text.startsWith("{")) {
            return text;
        }

        // ④ 不敢用 → 退回原问题
        return fallback;
    }

    /**
     * 廉价启发式：这句是否<b>可能</b>需要补全上下文。
     *
     * <p>用途是"省钱省时间"——改写要多一次模型调用（本地 8B 模型要 1~3 秒），
     * 而绝大多数提问本身已经完整（"报销标准是什么"），改写等于白跑。
     * 所以只在"看着像省略句"时才改写：句子很短，或含指代词。
     *
     * <p>它只影响"要不要改写"，不影响正确性：判错了最坏只是少改写一次，
     * 检索仍会拿到原始问题。
     */
    public static boolean looksContextDependent(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.strip();
        if (q.length() <= 10) {
            return true;
        }
        for (String pronoun : List.of("它", "他", "她", "这个", "那个", "这些", "那些",
                "上面", "前面", "刚才", "上述", "该文档", "该文件", "该配置", "此")) {
            if (q.contains(pronoun)) {
                return true;
            }
        }
        return false;
    }
}
