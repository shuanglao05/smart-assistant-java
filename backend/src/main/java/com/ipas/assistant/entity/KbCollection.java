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
 * 知识库（集合）实体。对应 的
 * {@code KbCollection} / {@code kb_collections} 表。
 *
 * <p>一个用户可以建多个知识库，用来把不同领域的资料分开
 * （例如「课程笔记」「实验室规范」「合同模板」）。
 * 对话时可按会话选择启用哪些库，检索只在选中的范围内进行。
 *
 * <p><b>topK 是"每个库单独可调"的检索条数</b>，这个设计有实际意义：
 * <ul>
 * <li>规范 / 教材类资料需要<b>精确定位</b>，Top-K 取小（如 2），
 * 避免召回的无关片段干扰模型；</li>
 * <li>杂项资料库需要<b>尽量多召回</b>（如 8），宁滥勿缺。</li>
 * </ul>
 * {@code null} 表示"跟随全局默认"（{@code app.rag.top-k}，默认 4）——
 * 注意 null 与 0 语义不同：null 是回退默认，而 0 是非法值（校验范围 1~20）。
 */
@Entity
@Table(name = "kb_collections")
@Getter
@Setter
public class KbCollection {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 @Column(name = "name", nullable = false, length = 100)
 private String name;

 /** 该库专属的检索片段数（1~20）。null = 跟随全局默认 app.rag.top-k。 */
 @Column(name = "top_k")
 private Integer topK;

 /**
 * 创建时间。接口层允许它为空（DTO 里是 {@code datetime | None}）——
 * 因为早期数据是原生 SQL 插入的，可能没写这一列。这是早期设计注释里明确提到的容错。
 */
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
 }

 @PreUpdate
 void onUpdate() {
 updatedAt = Times.nowUtc();
 }
}
