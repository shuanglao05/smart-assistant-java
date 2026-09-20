package com.ipas.assistant.dto;

import com.ipas.assistant.entity.Schedule;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 日程模块的请求 / 响应体。对应 的
 * {@code ScheduleCreate} / {@code ScheduleUpdate} / {@code ScheduleOut}。
 */
public final class ScheduleDtos {

 private ScheduleDtos() {
 }

 /**
 * 创建日程。对应 {@code ScheduleCreate}。
 *
 * <p>{@code startAt} 用 {@code Double} 而不是 {@code double}：
 * 原始类型在字段缺失时会被反序列化成 0.0，于是「前端忘了传时间」
 * 会变成「日程定在 1970 年」这种更隐蔽的错。用包装类型 + {@code @NotNull}
 * 能让它变成一个清晰的 422 提示。
 */
 public record Create(
 @NotBlank(message = "日程标题不能为空")
 @Size(max = 200, message = "日程标题最长 200 个字符")
 String title,

 /** 开始时刻，epoch 秒（UTC）。 */
 @NotNull(message = "开始时间不能为空")
 Double startAt,

 String note
 ) {
 }

 /** 更新日程。三个字段都可选，null = 不改。 */
 public record Update(
 @Size(max = 200, message = "日程标题最长 200 个字符")
 String title,

 Double startAt,

 String note
 ) {
 }

 /** 日程响应。对应 {@code ScheduleOut}。 */
 public record Out(
 Long id,
 String title,
 /** epoch 秒（UTC），前端自行格式化成当地时间展示。 */
 Double startAt,
 String note,
 Boolean reminded,
 LocalDateTime createdAt
 ) {
 public static Out from(Schedule e) {
 return new Out(e.getId(), e.getTitle(), e.getStartAt(), e.getNote(),
 e.getReminded(), e.getCreatedAt());
 }
 }
}
