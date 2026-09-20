package com.ipas.assistant.dto;

import com.ipas.assistant.entity.Message;

import java.time.LocalDateTime;

/**
 * 跨会话历史检索的响应体。
 *
 * <p>对应 {@code schemas.SearchHit} 与 。
 * 检索是<b>纯 LIKE 子串匹配</b>（不是语义检索）—— 语义检索由 RAG 知识库那条路负责，
 * 这里搜的是「用户自己的聊天历史」。
 */
public final class SearchDtos {

 private SearchDtos() {
 }

 /**
 * 一条检索命中。
 *
 * @param conversationId 命中所属的会话（前端点击后跳转到该会话）
 * @param title 会话标题
 * @param role {@code title} / {@code user} / {@code assistant}
 * —— <b>注意有第三种取值 {@code "title"}</b>，
 * 表示这条命中来自「会话标题」而非某条消息。
 * 前端按 role 决定图标与跳转行为。
 * @param content 摘要（命中词前后截一段），<b>不是整条消息的全文</b>
 * @param createdAt 时间；标题命中时用的是会话的 {@code updated_at}
 */
 public record SearchHit(
 Long conversationId,
 String title,
 String role,
 String content,
 LocalDateTime createdAt
 ) {
 /**
 * 标题命中：一条会话产生一条命中。
 *
 * <p>正文固定为「会话标题：xxx」而不是标题本身 ——
 * 前端列表里标题是独立字段渲染的，正文再重复一遍标题看起来像 bug，
 * 加上前缀能明确"这条命中是因为标题匹配"。
 */
 public static SearchHit ofTitle(Long conversationId, String title, LocalDateTime updatedAt) {
 return new SearchHit(conversationId, title, "title",
 "会话标题：" + title, updatedAt);
 }

 /** 消息命中：正文里截出含关键词的一小段。 */
 public static SearchHit ofMessage(Message m, String title, String snippet) {
 return new SearchHit(m.getConversationId(), title, m.getRole(),
 snippet, m.getCreatedAt());
 }
 }
}
