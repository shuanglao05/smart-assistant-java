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
 * 消息实体。对应 的 {@code Message} / {@code messages} 表。
 *
 * <p><b>【务必分清的两套"对话历史"—— 这是整个项目最容易混淆的地方】</b>
 *
 * <table border="1">
 * <tr><th>表 / 存储</th><th>给谁看</th><th>用途</th></tr>
 * <tr><td><b>messages（本表）</b></td><td>用户</td>
 * <td>左侧历史列表、聊天记录回看、全文检索。内容就是界面上显示的字。</td></tr>
 * <tr><td>Spring AI 的 chat memory 表<br>（早期设计是 图工作流框架 的 checkpoints.db）</td>
 * <td>大模型</td>
 * <td>喂给模型的上下文。包含 HumanMessage / AIMessage / ToolMessage，
 * 还会被「滑动窗口 + 增量摘要」压缩过。</td></tr>
 * </table>
 *
 * <p>两者是<b>分开写</b>的、可以不一致：比如流式回答被中途打断时，
 * 本表会存下「（回答生成已中断）」，而模型侧的上下文可能已被整体清掉
 * （早期设计会清掉该会话记忆以避免半截 tool_calls 历史导致后续请求永久报错）。
 * 所以千万不要试图用本表去重建模型上下文。
 *
 * <p><b>content 为什么是 MEDIUMTEXT</b>：AI 的 Markdown 回答动辄上万字，
 * MySQL 的 TEXT 上限只有 65535 <b>字节</b>（中文一个字 3 字节，约 2 万字），
 * 用 TEXT 会在写入时被静默截断 —— 表现为"回答后半段凭空消失"，极难排查。
 */
@Entity
@Table(name = "messages")
@Getter
@Setter
public class Message {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 @Column(name = "conversation_id", nullable = false)
 private Long conversationId;

 /** {@code user} 或 {@code assistant}。本项目不存 system，系统提示词不落库。 */
 @Column(name = "role", nullable = false, length = 20)
 private String role;

 /** 消息正文。见类注释：必须用 MEDIUMTEXT，不是 TEXT。 */
 @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
 private String content;

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 /**
 * 用户消息引用的附件 id，存 JSON 数组文本（如 {@code [3,7]}）。
 *
 * <p>类型是 TEXT 而不是 JSON 列，是为了和早期设计 {@code Column(Text)} 完全一致 ——
 * 早期设计写入时是 {@code json.dumps(ref_ids)}，存的就是普通文本。
 *
 * <p>为什么不在这里直接做成 {@code List<Long>} 用转换器：
 * 这里的原始值只是"引用记录"，接口返回时还要拿这些 id 去 files 表查出
 * <b>文件名和大小</b>（前端要在气泡上方显示可点击的文档名）。
 * 转换后的结果放在 {@code MessageOut.ref_files} 里，而不是实体字段上。
 */
 @Column(name = "ref_file_ids")
 private String refFileIds;

 /** 产生这条回答所用的模型供应方（仅助手消息有）。 */
 @Column(name = "provider", length = 20)
 private String provider;

 /** 产生这条回答所用的模型名（仅助手消息有；前端在回答下方标注）。 */
 @Column(name = "model", length = 120)
 private String model;

 @PrePersist
 void onCreate() {
 if (createdAt == null) {
 createdAt = Times.nowUtc();
 }
 }
}
