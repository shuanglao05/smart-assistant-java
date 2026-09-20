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
 * 运行时可改的应用配置（键值对）。
 *
 * <p>这张表替代了早期设计「把设置写回 backend/.env 文件」的做法，
 * 详细原因见 {@code db/schema-mysql.sql} 里建表语句上方的注释。
 *
 * <p>值的类型统一是文本，读取时由 {@code RuntimeSettingsService} 按配置项名
 * 解析成字符串 / 布尔 / 整数。之所以不做成多个强类型列：
 * 配置项是会持续增加的（今天加个代理、明天加个思考开关），
 * 每加一项都改表结构成本太高；键值对能容纳任意新增项。
 *
 * <p>唯一索引 {@code (user_id, setting_key)} 是这张表的<b>正确性基石</b>：
 * 它保证同一用户同一配置项只有一行，从而让「保存配置」可以用
 * 先删后插 / upsert 的语义实现，不用担心重复写入造成读到旧值。
 */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
public class AppSetting {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 /** 配置项名，如 {@code cloud.api-key}、{@code ollama.num-ctx}。 */
 @Column(name = "setting_key", nullable = false, length = 60)
 private String settingKey;

 /** 配置值。null 与空串语义相同（都表示"未设置"）。 */
 @Column(name = "setting_value", columnDefinition = "MEDIUMTEXT")
 private String settingValue;

 @Column(name = "updated_at")
 private LocalDateTime updatedAt;

 @PrePersist
 void onCreate() {
 if (updatedAt == null) {
 updatedAt = Times.nowUtc();
 }
 }

 @PreUpdate
 void onUpdate() {
 updatedAt = Times.nowUtc();
 }

 /** 便捷构造（配合 upsert 使用）。 */
 public static AppSetting of(Long userId, String key, String value) {
 AppSetting s = new AppSetting();
 s.setUserId(userId);
 s.setSettingKey(key);
 s.setSettingValue(value);
 return s;
 }
}
