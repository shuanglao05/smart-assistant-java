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
 * 上传文件实体。对应 的 {@code FileItem} / {@code files} 表。
 *
 * <p><b>一张表承担两种用途</b>（这是理解本表的关键）：
 * <ol>
 * <li><b>聊天附件</b>：提问时选中某个文件，后端把 {@code content} 抽出来
 * 拼在问题前面一起发给模型（一问一答，不落进历史）；</li>
 * <li><b>知识库素材</b>：文件归属某个知识库，被切分成片段 + 向量化存进
 * {@code kb_chunks}，供 RAG 检索。</li>
 * </ol>
 * 所以 {@code collectionId} 可以是 null —— 表示这个文件只是聊天附件，不属于任何知识库。
 *
 * <p><b>content 是"上传时一次性抽取好的正文"</b>，不是每次都去读原文件。
 * 好处是聊天时零 IO 开销；代价是文件内容变了要重新上传或调 {@code /reindex}。
 * 用 MEDIUMTEXT 是因为抽取上限设到了 50 万字符（约 1.5MB UTF-8），
 * 普通 TEXT 只有 65535 字节，会截断。
 */
@Entity
@Table(name = "files")
@Getter
@Setter
public class FileItem {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "user_id", nullable = false)
 private Long userId;

 /** 所属知识库。null = 仅作聊天附件，未被纳入任何知识库。 */
 @Column(name = "collection_id")
 private Long collectionId;

 /** 原始文件名（含扩展名）。上传时前端提交的名字，用于展示与判断类型。 */
 @Column(name = "filename", nullable = false, length = 255)
 private String filename;

 /**
 * 磁盘上的存储路径。
 *
 * <p>存的是<b>相对路径</b>而不是绝对路径，这样整个数据目录被搬走
 * （或换台机器部署）之后记录依然有效。早期设计注释也强调了这点。
 */
 @Column(name = "stored_path", nullable = false, length = 500)
 private String storedPath;

 /** 文件字节数。存 BIGINT：早期设计用 Integer，但超过 2GB 会溢出。 */
 @Column(name = "size")
 private Long size = 0L;

 /** 上传时抽取的正文，供聊天注入上下文。超长会是 null 或截断到上限。 */
 @Column(name = "content", columnDefinition = "MEDIUMTEXT")
 private String content;

 /**
 * 索引状态：{@code PENDING / INDEXING / READY / FAILED / SKIPPED}（常量见 {@code IndexStatus}）。
 *
 * <p>为什么需要它：索引是"切分 + 调嵌入服务 + 写片段"的耗时段，大文档要几十秒。
 * 上传接口不再同步等它（否则必然超时），而是落库后立刻返回、后台异步索引，
 * 于是"跑到哪一步了"必须有个字段承载，前端才能显示"索引中 / 已就绪 / 失败"。
 *
 * <p>默认 {@code PENDING}：新建记录天然处于"等待索引"。数据库侧该列也有
 * {@code DEFAULT 'PENDING'}，保证绕过本实体的插入（如手工 SQL）不会写出 NULL。
 */
 @Column(name = "index_status", nullable = false, length = 16)
 private String indexStatus = com.ipas.assistant.common.IndexStatus.PENDING;

 /** 最近一次索引失败的原因；成功或未失败时为 null。列长 500，写入前会截断。 */
 @Column(name = "index_error", length = 500)
 private String indexError;

 /**
 * 已建立的索引片段数（与 {@code kb_chunks} 中该文件的条数一致；未索引 / 失败为 0）。
 *
 * <p>冗余存一份的原因：知识库文档列表要显示"已索引 N 段"，若每行都去 count 一次
 * {@code kb_chunks}，列表页就会变成 N+1 查询。这里由索引流程在完成时一并写回，读时就零成本。
 */
 @Column(name = "chunk_count", nullable = false)
 private Integer chunkCount = 0;

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 @PrePersist
 void onCreate() {
 if (createdAt == null) {
 createdAt = Times.nowUtc();
 }
 if (size == null) {
 size = 0L;
 }
 }
}
