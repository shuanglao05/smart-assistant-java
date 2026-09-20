package com.ipas.assistant.entity;

import com.ipas.assistant.common.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 站内通知实体。对应 的 {@code Notification} / {@code notifications} 表。
 *
 * <p>用途是右上角铃铛：日程提醒、Agent 主动推送的重要提示、
 * 以及用户在界面上手动新建的通知。
 *
 * <p><b>⚠️ 列名映射：Java 字段 {@code type} → 数据库列 {@code notify_type}</b>
 * <br>早期设计列名就叫 {@code type}，但 {@code TYPE} 在 SQL 里是个容易踩坑的标识符
 * （不同数据库/版本对它的保留程度不一致，写 SQL 时常常需要加反引号/引号）。
 * 迁到 MySQL 时统一改成 {@code notify_type} 更省心。
 * <b>接口返回给前端的字段名仍然是 {@code type}</b>（由 DTO 决定），
 * 所以前端完全感知不到这个改名 —— 只有写原生 SQL 的人需要知道。
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 @Column(name = "title", nullable = false, length = 200)
 private String title;

 @Column(name = "body", columnDefinition = "TEXT")
 private String body;

 /**
 * 通知类型：{@code info} / {@code remind} / {@code alert} / {@code system}。
 *
 * <p>注意数据库列名是 {@code notify_type}（见类注释），
 * 但接口返回的 JSON 字段名是 {@code type}。
 */
 @Column(name = "notify_type", length = 20)
 private String type = "info";

 @Column(name = "is_read", nullable = false)
 private Boolean isRead = false;

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 @PrePersist
 void onCreate() {
 if (createdAt == null) {
 createdAt = Times.nowUtc();
 }
 if (isRead == null) {
 isRead = false;
 }
 if (type == null || type.isBlank()) {
 type = "info";
 }
 }
}
