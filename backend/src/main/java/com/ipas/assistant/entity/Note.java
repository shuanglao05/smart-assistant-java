package com.ipas.assistant.entity;

import com.ipas.assistant.common.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 智能笔记实体。对应 的 {@code Note} / {@code notes} 表。
 *
 * <p><b>day 字段为什么存字符串而不是日期类型</b>：
 * 它是 {@code "YYYY-MM-DD"} 形式的<b>归档标签</b>，不是时间点。
 * 用字符串有三个好处：跨数据库行为一致、前端可直接当分组键、
 * 「按天归纳」时能直接拿来比较（{@code Note.day == payload.day}），
 * 完全不需要处理时区。
 *
 * <p><b>day 的取值来源</b>：创建笔记时若前端没传，后端取
 * <b>服务器本地日期</b>（早期设计 {@code date.today()}）。
 * 注意这里和本表的 {@code createdAt}（UTC 墙上时间）语义不同 ——
 * {@code day} 是给人看的自然日标签，{@code createdAt} 是精确时间点。
 * 两者在跨时区时可能对不上，这是早期设计就有的设计，保持一致。
 */
@Entity
@Table(name = "notes")
@Getter
@Setter
public class Note {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 @Column(name = "title", nullable = false, length = 200)
 private String title = "";

 /** 正文，支持 Markdown。用 MEDIUMTEXT 防止长笔记被静默截断。 */
 @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
 private String content = "";

 /** 归档日期标签 {@code YYYY-MM-DD}。列上建了索引，因为列表按它倒序排。 */
 @Column(name = "day", nullable = false, length = 10)
 private String day;

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 @Column(name = "updated_at")
 private LocalDateTime updatedAt;

 @PrePersist
 void onCreate() {
 LocalDateTime now = Times.nowUtc();
 if (createdAt == null) {
 createdAt = now;
 }
 if (updatedAt == null) {
 updatedAt = now;
 }
 if (title == null) {
 title = "";
 }
 if (content == null) {
 content = "";
 }
 }

 @PreUpdate
 void onUpdate() {
 updatedAt = Times.nowUtc();
 }
}
