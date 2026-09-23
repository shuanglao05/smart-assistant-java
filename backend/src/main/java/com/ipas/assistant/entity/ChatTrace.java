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
 * 一轮回答的「执行阶段」记录。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>用户问"为什么这次回答不准"，光看日志答不上来：不知道检索到了几段、分数多少、
 * 哪一步最慢、首字等了多久。这些信息在流式对话里被冲散了 —— 有了这张表，
 * 每一轮回答都能"自证"它的执行过程。
 *
 * <h2>为什么按"阶段"一行一行存，而不是一条记录塞一个 JSON</h2>
 *
 * <p>按行存有三个好处：新增阶段不用改表结构；可以直接按阶段做统计
 * （"最近一周首字延迟的分布"是一条 GROUP BY）；写入是追加式的，
 * 不必读改写整条记录。
 *
 * <h2>为什么 exchange_id 是助手消息 id</h2>
 *
 * <p>一轮"提问 + 回答"里，助手消息是一个稳定的锚点：前端拿到回答就知道它的 id，
 * 于是可以用 {@code /api/sessions/{sid}/messages/{mid}/trace} 直接查这一轮的执行过程，
 * 不需要再引入一个"轮次"概念。
 */
@Entity
@Table(name = "chat_traces")
@Getter
@Setter
public class ChatTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 本轮对应的助手消息 id（前端的锚点）。 */
    @Column(name = "exchange_id", nullable = false)
    private Long exchangeId;

    /**
     * 阶段名：PREPARE / ROUTE / RETRIEVE / AGENT_BUILD / FIRST_TOKEN / DONE。
     * 用字符串而不是枚举：数据库里直接可读，新增阶段也不用改表。
     */
    @Column(name = "stage", nullable = false, length = 32)
    private String stage;

    /** 该阶段耗时（毫秒）。FIRST_TOKEN 记录的是"从请求开始到首字"，DONE 是总耗时。 */
    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs = 0;

    /**
     * 提示词侧 token 的<b>估算值</b>。
     *
     * <p>为什么是估算：流式接口拿不到每次调用的 usage（框架只在部分路径回传），
     * 而"大致花了多少"对排查与展示已经够用。按字符数折算（中文约 2 字符 1 token）。
     * 表里保留真实值的可能（将来框架回传 usage 时可以填精确值）。
     */
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    /** 回答侧 token 的估算值。 */
    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** 阶段补充信息（如路由结果、命中片段数、模型名）。用文本而非 JSON 列：内容短且要直接可读。 */
    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Times.nowUtc();
        }
        if (durationMs == null) {
            durationMs = 0;
        }
    }
}
