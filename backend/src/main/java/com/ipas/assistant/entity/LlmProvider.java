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
 * 云端大模型接入配置实体。对应 的
 * {@code LlmProvider} / {@code llm_providers} 表。
 *
 * <p>用来替代早期"只能配一套云端 Key"的 {@code CLOUD_*} 方案：
 * 现在可以同时接入多个平台（智谱 / DeepSeek / 百炼 / 硅基流动…），
 * 每个平台一套独立凭据，对话时按会话选用。
 *
 * <p><b>本地 Ollama 不算 provider</b>，仍由 {@code app.llm.ollama.*} 配置控制，
 * 在前端模型下拉里作为一条特殊项展示。这个边界要保持 ——
 * 若把 Ollama 也塞进本表，会让"没有 base_url"的本地模型和
 * "必须有 base_url"的云端配置挤在同一套 NOT NULL 约束里。
 *
 * <p><b>⚠️ apiKey 目前是明文存储</b>，与早期设计保持一致（本地个人项目的取舍）。
 * 若将来要上生产环境，应当改为加密存储 + 由密钥管理服务托管，
 * 并且接口层要保证只回传脱敏后的掩码（见 {@code LlmProviderOut.maskedKey}）——
 * 这一条已经做到了，前端拿不到明文 Key。
 */
@Entity
@Table(name = "llm_providers")
@Getter
@Setter
public class LlmProvider {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 /** 显示名，如「智谱 GLM」「DeepSeek 官方」。 */
 @Column(name = "name", nullable = false, length = 50)
 private String name;

 /** OpenAI 兼容端点，如 {@code https://open.bigmodel.cn/api/paas/v4}。 */
 @Column(name = "base_url", nullable = false, length = 300)
 private String baseUrl;

 /** 密钥<b>明文</b>（见类注释的安全说明）。绝不回传前端，只回传掩码。 */
 @Column(name = "api_key", nullable = false, length = 300)
 private String apiKey;

 /** 默认模型名。 */
 @Column(name = "model", nullable = false, length = 120)
 private String model = "";

 /**
 * 可选模型清单，<b>逗号分隔</b>的原始文本（如 {@code "glm-5.2,glm-4.6"}）。
 *
 * <p>注意存储与接口的类型不一致：存的是逗号分隔字符串，
 * 而接口返回给前端的是<b>数组</b>（{@code LlmProviderOut.models}）。
 * 转换在 DTO 层做（{@code AppProperties.parseModelList} 的等价逻辑）。
 * 这样存的形态与早期设计完全一致，迁移时无需清洗数据。
 */
 @Column(name = "models", columnDefinition = "TEXT")
 private String models;

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
 if (model == null) {
 model = "";
 }
 }

 @PreUpdate
 void onUpdate() {
 updatedAt = Times.nowUtc();
 }
}
