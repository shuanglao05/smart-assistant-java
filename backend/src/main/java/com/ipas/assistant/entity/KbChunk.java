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
 * RAG 知识片段实体。对应 的 {@code KbChunk} / {@code kb_chunks} 表。
 *
 * <p>一个文档被切分成若干小段（chunk），每段配一个向量（embedding）存起来。
 * 检索时把问题也向量化，与所有片段算余弦相似度，取最像的几段交给模型。
 *
 * <p><b>⚠️ 列名映射：Java 字段 {@code text} → 数据库列 {@code chunk_text}</b>
 * <br>早期设计列名就叫 {@code text}。但 {@code TEXT} 是 MySQL 的类型关键字，
 * 作为列名在不少场景下必须加反引号，写 SQL 时极容易漏。改用 {@code chunk_text} 更稳。
 *
 * <p><b>embedding 为什么存文本而不是专门的向量列</b>：
 * <ol>
 * <li><b>数据可直接搬运</b>：早期设计就是 {@code json.dumps(向量列表)} 存的文本，
 * 格式完全同构。迁移时已入库的 bge-m3 向量（1024 维）
 * 可以原样搬过来，<b>不需要重新向量化</b> —— 对大资料库而言这能省下几小时。</li>
 * <li>本项目是"几十~几千个片段"的量级，用纯 Java 算余弦是毫秒级，
 * 没必要上专门的向量数据库或 MySQL 的向量检索能力。</li>
 * </ol>
 * 代价是片段数涨到十万级后会慢。真到那时，只需替换 RagService 里
 * 「取片段 + 算余弦」这两处实现，上层（工具、Agent、接口）都不用动 ——
 * 早期设计注释里也是这么规划的。
 *
 * <p>用 MEDIUMTEXT 而不是 TEXT：1024 维 float 序列化成 JSON 约 15~20KB，
 * TEXT 的 65535 字节上限虽勉强够，但换更大的 embedding 模型就会溢出，
 * 留出余量更安全。
 */
@Entity
@Table(name = "kb_chunks")
@Getter
@Setter
public class KbChunk {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 /** 所属知识库。可能为 null（历史数据未归档到具体库）。 */
 @Column(name = "collection_id")
 private Long collectionId;

 /** 来源文档 id，指回 {@code files} 表。用于在检索结果里标出「这段话出自哪个文件」。 */
 @Column(name = "file_id", nullable = false)
 private Long fileId;

 /** 该文档内的片段序号（从 0 开始），用于按顺序回放与拼图展示。 */
 @Column(name = "chunk_index", nullable = false)
 private Integer chunkIndex = 0;

 /** 片段正文。数据库列名是 {@code chunk_text}（见类注释）。 */
 @Column(name = "chunk_text", nullable = false, columnDefinition = "MEDIUMTEXT")
 private String text;

 /** 向量，形如 {@code [0.0123, -0.456, ...]} 的 JSON 文本。 */
 @Column(name = "embedding", nullable = false, columnDefinition = "MEDIUMTEXT")
 private String embedding;

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 @PrePersist
 void onCreate() {
 if (createdAt == null) {
 createdAt = Times.nowUtc();
 }
 if (chunkIndex == null) {
 chunkIndex = 0;
 }
 }
}
