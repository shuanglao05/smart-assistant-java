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
 * 日程实体。对应 的 {@code Schedule} / {@code schedules} 表。
 *
 * <p><b>startAt 为什么用 epoch 秒（Double）而不是 DATETIME</b>：
 * 早期设计的注释写得很明白 ——「避免时区换算的坑」。
 * epoch 秒是一个<b>绝对时刻</b>，不存在"这个时间该按哪个时区理解"的歧义，
 * 也就能安全地拿来做数值比较（找出「未来 5 分钟内要开始的日程」只需
 * {@code start_at > now && start_at <= now + 300}，不涉及任何时区转换）。
 *
 * <p>注意这与 {@code createdAt} 的语义不同：{@code createdAt} 是给人和 SQL 看的
 * 时间戳（UTC 墙上时间），而 {@code startAt} 是业务上的绝对时刻。
 * 两种表示法混在一张表里是早期设计的既有设计，<b>刻意保持一致</b> ——
 * 改成统一表示会让两端数据无法直接对照。
 *
 * <p>提醒由后台定时任务实现（{@code ScheduleReminderJob}，每 30 秒扫一次），
 * 提前 5 分钟发一条站内通知。{@code reminded} 就是防止重复推送的幂等标记。
 */
@Entity
@Table(name = "schedules")
@Getter
@Setter
public class Schedule {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 @Column(name = "title", nullable = false, length = 200)
 private String title;

 /** 开始时刻，epoch 秒（UTC）。列上有索引，后台提醒任务靠它做范围查询。 */
 @Column(name = "start_at", nullable = false)
 private Double startAt;

 @Column(name = "note", columnDefinition = "TEXT")
 private String note;

 /**
 * 是否已发过提醒。
 *
 * <p>注意一个<b>容易被忽略的业务细节</b>：修改 {@code startAt} 时必须把它重置为
 * false（见 ScheduleService.update），否则「把日程改到明天」之后
 * 因为记得"已经提醒过了"而永远不会再提醒。
 */
 @Column(name = "reminded", nullable = false)
 private Boolean reminded = false;

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 @PrePersist
 void onCreate() {
 if (createdAt == null) {
 createdAt = Times.nowUtc();
 }
 if (reminded == null) {
 reminded = false;
 }
 }
}
