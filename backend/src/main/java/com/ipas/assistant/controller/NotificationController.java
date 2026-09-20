package com.ipas.assistant.controller;

import com.ipas.assistant.dto.NotificationDtos;
import com.ipas.assistant.dto.StatusResponse;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知接口。对应 的
 * {@code APIRouter(prefix="/api/notifications")}。
 *
 * <p>注意路由声明顺序：{@code /unread-count} 与 {@code /read-all}
 * 是字面量路径，与 {@code /{id}} 不冲突（Spring 优先匹配更具体的字面量路径）。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

 private final NotificationService notificationService;

 public NotificationController(NotificationService notificationService) {
 this.notificationService = notificationService;
 }

 /**
 * GET /api/notifications —— 通知列表。
 *
 * <p>两个查询参数与早期设计签名对齐：
 * {@code unread_only: bool = False}、{@code limit: int = 50}（范围 1~200）。
 *
 * <p>{@code limit} 的边界夹取在 Service 里做（越界静默夹到边界），
 * 而不是在这里用 {@code @Min/@Max} 报 422 —— 理由见 Service 注释。
 */
 @GetMapping
 public ResponseEntity<List<NotificationDtos.Out>> list(
 @AuthenticationPrincipal AuthUser me,
 @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly,
 @RequestParam(name = "limit", defaultValue = "50") int limit) {
 return ResponseEntity.ok(notificationService.list(me.id(), unreadOnly, limit));
 }

 /** GET /api/notifications/unread-count —— 未读数（铃铛红点）。 */
 @GetMapping("/unread-count")
 public ResponseEntity<NotificationDtos.UnreadCount> unreadCount(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(new NotificationDtos.UnreadCount(
 notificationService.unreadCount(me.id())));
 }

 /** POST /api/notifications —— 手动新建通知。 */
 @PostMapping
 public ResponseEntity<NotificationDtos.Out> create(
 @AuthenticationPrincipal AuthUser me,
 @Valid @RequestBody NotificationDtos.Create payload) {
 return ResponseEntity.ok(notificationService.create(me.id(), payload));
 }

 /**
 * PATCH /api/notifications/{id} —— 标记已读 / 未读。
 *
 * <p>{@code is_read} 作为<b>查询参数</b>（不是请求体），默认 {@code true}。
 * 这是早期设计的签名 {@code mark_notification(nid, is_read: bool = True)} ——
 * 前端调用就是 {@code client.patch(`/notifications/${id}`)}，
 * 不带参数即表示"标记已读"。这个默认值是契约的一部分，不能改成必填。
 */
 @PatchMapping("/{id}")
 public ResponseEntity<NotificationDtos.Out> markRead(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long id,
 @RequestParam(name = "is_read", defaultValue = "true") boolean isRead) {
 return ResponseEntity.ok(notificationService.markRead(me.id(), id, isRead));
 }

 /** POST /api/notifications/read-all —— 全部标记已读。 */
 @PostMapping("/read-all")
 public ResponseEntity<StatusResponse> readAll(@AuthenticationPrincipal AuthUser me) {
 notificationService.markAllRead(me.id());
 return ResponseEntity.ok(StatusResponse.ok());
 }

 /**
 * DELETE /api/notifications —— 清空全部通知。
 *
 * <p>注意路径是<b>集合本身</b>（没有 {@code /{id}}），
 * 语义是"清空"，不是"删除某一条"。早期设计就是这么设计的。
 */
 @DeleteMapping
 public ResponseEntity<StatusResponse> clearAll(@AuthenticationPrincipal AuthUser me) {
 notificationService.clearAll(me.id());
 return ResponseEntity.ok(StatusResponse.cleared());
 }
}
