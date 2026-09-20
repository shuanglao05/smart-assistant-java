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
 * 课程实体。对应 的 {@code Course} / {@code courses} 表。
 *
 * <p>一门课 = 「星期几 + 起止节次 + 周次」。前端按「节次行 × 星期列」的网格渲染，
 * 再按当前周过滤掉本周不上课的课程。
 *
 * <p><b>startSection 与 endSection 必须保证 start &lt;= end</b>。
 * 用户在界面上完全可能先填「第 5 节」再填「第 3 节」，
 * 早期设计用 {@code _norm_sections()} 在创建和更新时都做了一次交换校正。
 * 这个校正<b>必须在服务层做</b>，不能只在创建时做 —— 只改其中一个字段
 * （如把结束节次从 4 改成 2）同样会产生倒挂。见 CourseService。
 */
@Entity
@Table(name = "courses")
@Getter
@Setter
public class Course {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 @Column(name = "name", nullable = false, length = 100)
 private String name;

 @Column(name = "teacher", length = 100)
 private String teacher;

 @Column(name = "location", length = 100)
 private String location;

 /** 星期几：1=周一 … 7=周日。列上有索引，列表按它排序。 */
 @Column(name = "weekday", nullable = false)
 private Integer weekday;

 /** 起始节次（从 1 开始计数）。 */
 @Column(name = "start_section", nullable = false)
 private Integer startSection;

 /** 结束节次。 */
 @Column(name = "end_section", nullable = false)
 private Integer endSection;

 /** 周次范围文本，如 {@code "1-16"}、{@code "1-16单周"}；空表示每周都上。 */
 @Column(name = "weeks", length = 50)
 private String weeks;

 /** 前端网格上给这门课用的颜色。存的是前端认识的颜色标识，后端不解析。 */
 @Column(name = "color", length = 20)
 private String color;

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 @PrePersist
 void onCreate() {
 if (createdAt == null) {
 createdAt = Times.nowUtc();
 }
 }
}
