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
 * 待办实体。对应 的 {@code TodoItem} / {@code todos} 表。
 *
 * <p><b>两套入口，同一张表</b>（早期设计注释里专门强调了这一点）：
 * <ul>
 * <li>REST 接口 {@code /api/todos/**} —— 给人点界面用的；</li>
 * <li>Agent 工具 {@code add_todo} / {@code list_todos} —— 给大模型调用的。</li>
 * </ul>
 * 两条路径都按 {@code userId} 隔离，写的是同一张表。
 * 所以第三阶段接 Agent 工具时，直接复用本次写的 Service 即可，
 * 不要在工具里另写一套 SQL（否则两边的排序规则、默认值很容易走岔）。
 */
@Entity
@Table(name = "todos")
@Getter
@Setter
public class TodoItem {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 /** 待办内容。早期设计上限 500 字符（数据校验框架 Field + 列长度一致）。 */
 @Column(name = "task", nullable = false, length = 500)
 private String task;

 /** 是否已完成。false 的排前面（见 TodoService 的排序规则）。 */
 @Column(name = "done")
 private Boolean done = false;

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 @PrePersist
 void onCreate() {
 if (createdAt == null) {
 createdAt = Times.nowUtc();
 }
 if (done == null) {
 done = false;
 }
 }
}
