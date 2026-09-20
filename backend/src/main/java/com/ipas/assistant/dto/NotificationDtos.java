package com.ipas.assistant.dto;

import com.ipas.assistant.entity.Notification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 通知模块的请求 / 响应体。对应 的
 * {@code NotificationCreate} / {@code NotificationOut}。
 *
 * <p><b>⚠️ 注意这里的 {@code type} 字段名</b>：数据库列叫 {@code notify_type}
 * （避开 MySQL 关键字），但<b>接口字段必须是 {@code type}</b> ——
 * 前端类型定义和所有 UI 逻辑都认这个名字。改名只发生在持久层，
 * 由实体的 {@code @Column(name = "notify_type")} 完成映射，DTO 这层不受影响。
 */
public final class NotificationDtos {

 private NotificationDtos() {
 }

 /**
 * 创建通知。对应 {@code NotificationCreate}：
 * {@code title} (1~200)、{@code body} 可选、{@code type="info"}。
 */
 public record Create(
 @NotBlank(message = "通知标题不能为空")
 @Size(max = 200, message = "通知标题最长 200 个字符")
 String title,

 String body,

 /** info / remind / alert / system，缺省 info。 */
 String type
 ) {
 }

 /** 通知响应。对应 {@code NotificationOut}。 */
 public record Out(
 Long id,
 String title,
 String body,
 /** 接口字段名是 {@code type}（见类注释）。 */
 String type,
 Boolean isRead,
 LocalDateTime createdAt
 ) {
 public static Out from(Notification e) {
 return new Out(e.getId(), e.getTitle(), e.getBody(), e.getType(),
 e.getIsRead(), e.getCreatedAt());
 }
 }

 /** 未读数响应：{@code {"count": n}}。 */
 public record UnreadCount(long count) {
 }
}
