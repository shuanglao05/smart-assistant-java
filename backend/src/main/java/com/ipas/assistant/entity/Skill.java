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
 * 技能实体。对应 的 {@code Skill} / {@code skills} 表。
 *
 * <p>技能就是「一段附加到系统提示词后面的人设指令」。用户在界面上写一段 prompt，
 * 开启后在会话里选中，后端就把它拼到系统提示词末段 —— 于是模型的行为被改变
 * （例如"回答一律用表格"、"扮演面试官提问"）。
 *
 * <p><b>删除技能时必须从所有会话的 active_skill_ids 里摘掉它</b>，
 * 否则会留下指向不存在技能的悬空 id。早期设计用
 * {@code _remove_skill_from_sessions()} 处理；Java 侧放在 SkillService 里，
 * 且在<b>同一个事务</b>内完成（早期设计是同一事务，这里保持一致）。
 * 若不清理，第三阶段构建 Agent 时按 id 查技能会查出空集，
 * 表现为"用户明明勾了技能但没生效"，很难定位。
 */
@Entity
@Table(name = "skills")
@Getter
@Setter
public class Skill {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 @Column(name = "name", nullable = false, length = 100)
 private String name;

 @Column(name = "description", length = 500)
 private String description;

 /** 附加指令正文，会被拼进系统提示词。不能为空。 */
 @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
 private String prompt;

 /**
 * 是否启用。被关闭的技能即使被会话勾选也不会生效
 * （第三阶段构建 Agent 时会同时过滤 {@code is_enabled = true}）。
 */
 @Column(name = "is_enabled", nullable = false)
 private Boolean isEnabled = true;

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 /** 列表按它倒序排（最近改过的技能排最前），所以没建索引也要保证它被正确维护。 */
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
 if (isEnabled == null) {
 isEnabled = true;
 }
 }

 @PreUpdate
 void onUpdate() {
 updatedAt = Times.nowUtc();
 }
}
