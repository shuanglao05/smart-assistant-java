package com.ipas.assistant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 流式聊天请求体。对应 流式入口的入参。
 *
 * <h2>字段名必须与前端逐字一致</h2>
 *
 * <p>前端 {@code ChatWindow.tsx} 里 {@code runStream} 发出的 JSON 是 snake_case
 * （{@code session_id} / {@code message} / {@code file_ids} / {@code regenerate} /
 * {@code enable_thinking} / {@code thinking_budget}）。本项目 Jackson 全局已是
 * SNAKE_CASE（见 application.yml），所以字段即使不加 {@code @JsonProperty} 也能对上；
 * 但这里是契约的命门，我<b>显式写了 @JsonProperty</b>，万一将来全局策略被改也不至于
 * 静默失配 —— 那种"字段名对不上导致整条消息发不出去"的 bug 极难定位。</p>
 *
 * <p>{@code session_id} 上挂了 {@code @NotNull}：它是必填项，缺失时由
 * {@code GlobalExceptionHandler} 转成 422 + {@code {"detail":"..."}}，与前端的
 * {@code !resp.ok} 分支读取逻辑一致。</p>
 */
public record ChatRequest(

 /** 会话 id（必填）。也是模型侧记忆的 thread_id 来源。 */
 @NotNull(message = "缺少会话 ID")
 @JsonProperty("session_id")
 Long sessionId,

 /** 本轮用户输入文本。regenerate（重新生成）时为 null。 */
 @JsonProperty("message")
 String message,

 /** 是否「重新生成最后一条回答」。true 时复用最后一条用户消息、清掉旧回答。 */
 @JsonProperty("regenerate")
 Boolean regenerate,

 /** 用户消息引用的附件 id 列表（前端气泡上方要显示文档名）。 */
 @JsonProperty("file_ids")
 List<Long> fileIds,

 /** 云端深度思考开关。前端未传时回落到用户已保存的设置。 */
 @JsonProperty("enable_thinking")
 Boolean enableThinking,

 /** 思维链 token 上限。0 / 未传 = 平台默认。 */
 @JsonProperty("thinking_budget")
 Integer thinkingBudget
) {
}
