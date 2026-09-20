package com.ipas.assistant.common;

import java.util.List;
import java.util.Map;

/**
 * 聊天流式 SSE 事件。对应 的五种事件，前端按这个 shape 解析。
 *
 * <h2>★ 格式硬约束（与 早期实现逐字一致）</h2>
 *
 * <p>每个事件的 JSON <b>key 就是事件类型</b>（{@code {"token":"..."}}），
 * 不是 {@code {"type":"token", ...}}。前端只认前者，改了就读不到。
 *
 * <p>事件清单：
 * <ul>
 * <li>{@code {"token": "..."}} 正文增量（打字机效果的主体）</li>
 * <li>{@code {"reasoning": "..."}} 思考过程增量（推理模型的 thinking）</li>
 * <li>{@code {"sources": ["a","b"]}} 知识库命中来源的文件名列表</li>
 * <li>{@code {"done": true, "provider":..., "model":..., "user_id":..., "assistant_id":...}}
 * 结束事件，带回本次回答所用的模型与两条消息的后端 id</li>
 * <li>{@code {"error": "..."}} 错误</li>
 * </ul>
 *
 * <p>{@code done} 事件里的 {@code user_id} / {@code assistant_id} 必须是
 * <b>snake_case</b> —— 原前端据此给刚生成的这轮问答补上后端 id（否则新消息没有 id，
 * 单条删除按钮不显示）。这点曾经被我标错成 camelCase，这里明确锁死。
 */
public final class ChatStreamEvent {

 /** 事件类型。与 SSE JSON 的 key 一一对应。 */
 public enum Type { TOKEN, REASONING, SOURCES, DONE, ERROR }

 private final Type type;
 /** 载体：token/reasoning/error → String；sources → List<String>；done → Map<String,Object>。 */
 private final Object payload;

 private ChatStreamEvent(Type type, Object payload) {
 this.type = type;
 this.payload = payload;
 }

 public Type type() {
 return type;
 }

 /**
 * 转成 SSE 的 data 负载：一个<b>单键</b> Map，key = 事件类型。
 * 例如 {@code Map.of("token", "你好")} 经 Jackson 序列化即为 {@code {"token":"你好"}}，
 * 由控制器拼成 {@code data: {"token":"你好"}\n\n}，与 早期实现一致。
 */
 public Map<String, Object> toData() {
 return switch (type) {
 case TOKEN -> Map.of("token", payload);
 case REASONING -> Map.of("reasoning", payload);
 case SOURCES -> Map.of("sources", payload);
 case ERROR -> Map.of("error", payload);
 case DONE -> doneMap();
 };
 }

 @SuppressWarnings("unchecked")
 private Map<String, Object> doneMap() {
 return (Map<String, Object>) payload;
 }

 // ===================== 工厂方法 =====================

 public static ChatStreamEvent token(String text) {
 return new ChatStreamEvent(Type.TOKEN, text);
 }

 public static ChatStreamEvent reasoning(String text) {
 return new ChatStreamEvent(Type.REASONING, text);
 }

 public static ChatStreamEvent sources(List<String> names) {
 return new ChatStreamEvent(Type.SOURCES, names);
 }

 public static ChatStreamEvent error(String text) {
 return new ChatStreamEvent(Type.ERROR, text);
 }

 public static ChatStreamEvent done(String provider, String model,
 Long userId, Long assistantId) {
 return new ChatStreamEvent(Type.DONE, Map.of(
 "done", true,
 "provider", provider == null ? "" : provider,
 "model", model == null ? "" : model,
 "user_id", userId,
 "assistant_id", assistantId));
 }
}
