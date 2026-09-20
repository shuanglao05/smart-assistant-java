package com.ipas.assistant.entity;

import com.ipas.assistant.common.Times;
import com.ipas.assistant.entity.converter.LongListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 会话实体。对应 的 {@code Conversation} / {@code conversations} 表。
 *
 * <p>一条会话身上挂了四类「上下文配置」，它们共同决定这一轮回答长什么样：
 * <ul>
 * <li>{@code provider} + {@code model} + {@code providerId} —— 用哪个大模型；</li>
 * <li>{@code activeSkillIds} —— 启用哪些技能（技能 prompt 会拼进系统提示词）；</li>
 * <li>{@code activeKbIds} —— 参与检索的知识库（空 = 检索该用户全部知识库）。</li>
 * </ul>
 *
 * <p><b>为什么这三项变化会导致「重建 Agent」</b>（第三阶段会用到）：
 * 早期设计把它们做成了 Agent 缓存键的一部分。如果用户切了模型却复用旧 Agent，
 * 就会出现「界面显示换成了新模型，实际还在用老的」这种极难排查的问题。
 * 实体层把这三项都持久化了，就是为了让缓存键能从库里稳定地算出来。
 *
 * <p><b>activeSkillIds / activeKbIds 的存储方式</b>：
 * 数据库是 MySQL 的 JSON 列，Java 侧是 {@code List<Long>}，
 * 中间靠 {@link LongListJsonConverter} 转换。
 * 注意这里初始化为<b>空列表</b>而不是 null —— 早期设计的列默认值就是空数组，
 * 且「空 = 不限制」是有业务含义的（检索全部库），不能与 null 混淆。
 */
@Entity
@Table(name = "conversations")
@Getter
@Setter
public class Conversation {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 /** 归属用户。所有查询都必须带上它做过滤，否则会越权看到别人的会话。 */
 @Column(name = "user_id", nullable = false)
 private Long userId;

 /** 会话标题。首轮提问时由后端截取前 20 个字符自动命名（见早期实现）。 */
 @Column(name = "title", length = 100)
 private String title = "新对话";

 /** 模型供应方：{@code ollama}（本地）或 {@code cloud}（云端 OpenAI 兼容平台）。 */
 @Column(name = "provider", nullable = false, length = 20)
 private String provider = "ollama";

 /** 具体模型名，如 {@code qwen3:8b}、{@code glm-5.2}。 */
 @Column(name = "model", nullable = false, length = 80)
 private String model = "";

 /**
 * 指向 {@code llm_providers.id} 的云端接入配置。
 * null 表示用 {@code app.llm.cloud.*} 默认配置或本地 Ollama。
 */
 @Column(name = "provider_id")
 private Long providerId;

 /** 本会话启用的技能 id 列表。 */
 @Convert(converter = LongListJsonConverter.class)
 @Column(name = "active_skill_ids")
 private List<Long> activeSkillIds = new ArrayList<>();

 /** 本会话参与检索的知识库 id 列表。空列表 = 不限制（检索全部）。 */
 @Convert(converter = LongListJsonConverter.class)
 @Column(name = "active_kb_ids")
 private List<Long> activeKbIds = new ArrayList<>();

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 /**
 * 最后更新时间。{@code conversations.updated_at} 上建了索引，
 * 因为会话列表要按它倒序排 —— 这是首页最频繁的查询。
 */
 @Column(name = "updated_at")
 private LocalDateTime updatedAt;

 /** 落库前补默认值（时间语义见 {@link Times} 的说明）。 */
 @PrePersist
 void onCreate() {
 LocalDateTime now = Times.nowUtc();
 if (createdAt == null) {
 createdAt = now;
 }
 if (updatedAt == null) {
 updatedAt = now;
 }
 if (title == null || title.isBlank()) {
 title = "新对话";
 }
 if (activeSkillIds == null) {
 activeSkillIds = new ArrayList<>();
 }
 if (activeKbIds == null) {
 activeKbIds = new ArrayList<>();
 }
 }

 /** 每次更新自动刷新 updatedAt（早期设计是业务代码里手写赋值的，这里集中处理）。 */
 @PreUpdate
 void onUpdate() {
 updatedAt = Times.nowUtc();
 }
}
