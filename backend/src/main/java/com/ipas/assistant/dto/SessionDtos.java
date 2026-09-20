package com.ipas.assistant.dto;

import com.ipas.assistant.entity.Conversation;
import com.ipas.assistant.entity.FileItem;
import com.ipas.assistant.entity.Message;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话与消息模块的请求 / 响应体。对应 的
 * {@code SessionCreate} / {@code SessionOut} / {@code SessionUpdate} /
 * {@code MessageOut} / {@code RefFile}。
 */
public final class SessionDtos {

 private SessionDtos() {
 }

 /**
 * 新建会话请求。对应 {@code SessionCreate}。
 *
 * <p>整个请求体是<b>可选</b>的（原签名 {@code payload: SessionCreate | None = None}）：
 * 前端点「新对话」时不带任何 body。所以 {@code title} 允许为 null，
 * 由服务层回落到「新对话」。
 */
 public record CreateRequest(
 @Size(max = 100, message = "标题最长 100 个字符")
 String title
 ) {
 }

 /**
 * 更新会话请求。对应 {@code SessionUpdate}，六个字段都可选，null = 不改。
 *
 * <p><b>{@code providerId} 的语义与 {@code provider} 有联动</b>（见
 * {@code SessionService.update}），不是简单的"逐字段赋值"：
 * 传了 {@code providerId} 会连带把 provider 设为 cloud 并按该接入的默认模型赋值。
 */
 public record UpdateRequest(
 @Size(max = 100, message = "标题最长 100 个字符")
 String title,
 String provider,
 String model,
 Long providerId,
 List<Long> activeSkillIds,
 List<Long> activeKbIds
 ) {
 }

 /** 会话响应。对应 {@code SessionOut}。 */
 public record SessionOut(
 Long id,
 String title,
 String provider,
 String model,
 Long providerId,
 List<Long> activeSkillIds,
 List<Long> activeKbIds,
 LocalDateTime createdAt,
 LocalDateTime updatedAt
 ) {
 public static SessionOut from(Conversation c) {
 return new SessionOut(
 c.getId(), c.getTitle(), c.getProvider(), c.getModel(), c.getProviderId(),
 // 用空列表兜底而不是让它为 null：前端直接 .length / .map 会被 null 搞崩
 c.getActiveSkillIds() == null ? List.of() : c.getActiveSkillIds(),
 c.getActiveKbIds() == null ? List.of() : c.getActiveKbIds(),
 c.getCreatedAt(), c.getUpdatedAt());
 }
 }

 /**
 * 消息响应。对应 {@code MessageOut}。
 *
 * <p>{@code refFiles} 不是数据库字段 —— 它由 {@code ref_file_ids}
 * 里的 id 去 files 表查出文件名与大小后拼成，供前端在气泡上方
 * 显示可点击的文档名。助手消息恒为空列表。
 */
 public record MessageOut(
 Long id,
 String role,
 String content,
 LocalDateTime createdAt,
 List<RefFile> refFiles,
 /** 产生这条回答的模型（仅助手消息有），前端在回答下方标注。 */
 String provider,
 String model
 ) {
 public static MessageOut from(Message m, List<RefFile> refFiles) {
 return new MessageOut(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt(),
 refFiles == null ? List.of() : refFiles, m.getProvider(), m.getModel());
 }
 }

 /** 消息引用的附件（id + 文件名 + 大小）。对应 {@code RefFile}。 */
 public record RefFile(Long id, String filename, Long size) {
 public static RefFile from(FileItem f) {
 return new RefFile(f.getId(), f.getFilename(), f.getSize());
 }
 }

 /**
 * 删除消息的结果。对应 {@code DELETE /api/sessions/{id}/messages/{mid}} 的返回。
 *
 * <p>{@code ids} 是本次实际删掉的消息 id 列表 ——
 * <b>通常是两条</b>，因为删除是「成对」的（见 {@code SessionService.deleteMessage}）。
 * 前端拿它做本地列表的同步移除，避免整页刷新。
 */
 public record DeleteMessagesResult(String status, List<Long> ids) {
 }

 /** 清空全部会话的结果：{@code {"status":"deleted","count":n}}。 */
 public record ClearAllResult(String status, int count) {
 }
}
