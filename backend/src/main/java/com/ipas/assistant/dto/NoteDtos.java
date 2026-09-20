package com.ipas.assistant.dto;

import com.ipas.assistant.entity.Note;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 笔记模块的请求 / 响应体。对应 的
 * {@code NoteCreate} / {@code NoteUpdate} / {@code NoteOut} / {@code NoteSummarizeRequest}。
 *
 * <p>合并到同一容器类的理由见 {@link TodoDtos} 的类注释。
 */
public final class NoteDtos {

 private NoteDtos() {
 }

 /**
 * 创建笔记。对应 {@code NoteCreate}：
 * {@code title=""} (max 200)、{@code content=""}、{@code day: str | None = None}。
 *
 * <p>{@code day} 缺省时由后端取<b>服务器本地日期</b>（原 {@code date.today()}）。
 */
 public record Create(
 @Size(max = 200, message = "标题最长 200 个字符")
 String title,
 String content,
 /** 归档日期 {@code YYYY-MM-DD}；null = 今天。 */
 String day
 ) {
 }

 /** 更新笔记。三个字段都可选，null = 不改。 */
 public record Update(
 @Size(max = 200, message = "标题最长 200 个字符")
 String title,
 String content,
 String day
 ) {
 }

 /** 笔记响应。对应 {@code NoteOut}。 */
 public record Out(
 Long id,
 String title,
 String content,
 /** 归档日期标签，注意是字符串不是时间戳。 */
 String day,
 LocalDateTime createdAt,
 LocalDateTime updatedAt
 ) {
 public static Out from(Note e) {
 return new Out(e.getId(), e.getTitle(), e.getContent(), e.getDay(),
 e.getCreatedAt(), e.getUpdatedAt());
 }
 }

 /**
 * AI 归纳某天笔记的请求。对应 {@code NoteSummarizeRequest}。
 *
 * <p>{@code provider} / {@code model} 允许前端传全局选中的模型；
 * 不传则用服务端默认配置。这种「前端可覆盖模型」的写法在多个接口里都出现
 * （笔记归纳、课表导入），是本项目的既有约定。
 */
 public record SummarizeRequest(
 String day,
 String provider,
 String model
 ) {
 }

 /** AI 归纳结果。接口返回形如 {@code {"day","summary","count"}} 的对象。 */
 public record SummarizeResponse(
 String day,
 String summary,
 int count
 ) {
 }
}
