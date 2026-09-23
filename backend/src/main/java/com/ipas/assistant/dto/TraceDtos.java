package com.ipas.assistant.dto;

import com.ipas.assistant.entity.ChatTrace;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行过程（trace）模块的响应体。
 *
 * <p>字段用 camelCase，由全局 SNAKE_CASE 策略序列化成 snake_case
 * （{@code duration_ms} / {@code prompt_tokens} / {@code created_at} …），与其它模块一致。
 */
public final class TraceDtos {

    private TraceDtos() {
    }

    /** 单个执行阶段。 */
    public record TraceStage(
            /** 阶段名：PREPARE / ROUTE / RETRIEVE / AGENT_BUILD / FIRST_TOKEN / DONE。 */
            String stage,
            /** 耗时（毫秒）。FIRST_TOKEN 是"从请求开始到首字"，DONE 是总耗时。 */
            int durationMs,
            /** 提示词侧 token 估算值（可能为 null）。 */
            Integer promptTokens,
            /** 回答侧 token 估算值（可能为 null）。 */
            Integer completionTokens,
            /** 阶段补充信息（路由结果、命中片段数、模型名等）。 */
            String detail,
            LocalDateTime createdAt
    ) {
        public static TraceStage from(ChatTrace t) {
            return new TraceStage(t.getStage(),
                    t.getDurationMs() == null ? 0 : t.getDurationMs(),
                    t.getPromptTokens(), t.getCompletionTokens(),
                    t.getDetail(), t.getCreatedAt());
        }
    }

    /**
     * 一轮回答的完整时间线。
     *
     * @param sessionId 会话 id
     * @param messageId 助手消息 id（本轮的执行锚点）
     * @param totalMs   总耗时（取自 DONE 阶段；没有则 0）
     * @param firstTokenMs 首字延迟（取自 FIRST_TOKEN 阶段；没有则 0）—— 最有价值的体验指标
     * @param stages    各阶段明细，按发生顺序
     */
    public record TraceTimeline(
            Long sessionId,
            Long messageId,
            int totalMs,
            int firstTokenMs,
            List<TraceStage> stages
    ) {
    }
}
