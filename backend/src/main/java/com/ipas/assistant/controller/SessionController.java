package com.ipas.assistant.controller;

import com.ipas.assistant.dto.SessionDtos;
import com.ipas.assistant.dto.StatusResponse;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话与消息接口。对应 的
 * {@code APIRouter(prefix="/api/sessions")}。
 *
 * <p>前端左侧的会话列表、聊天区的消息流、以及"删除单条消息""清空全部对话"
 * 都走这里。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

 private final SessionService sessionService;

 public SessionController(SessionService sessionService) {
 this.sessionService = sessionService;
 }

 /** GET /api/sessions —— 会话列表（按最后更新时间倒序）。 */
 @GetMapping
 public ResponseEntity<List<SessionDtos.SessionOut>> list(@AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(sessionService.list(me.id()));
 }

 /**
 * POST /api/sessions —— 新建会话。
 *
 * <p><b>{@code @RequestBody(required = false)} 不能改成必填</b>：
 * 前端点「新对话」时<b>不发请求体</b>（早期设计签名是
 * {@code payload: SessionCreate | None = None}）。若设为必填，
 * 这个核心操作会直接 400 —— 而它是最常用的按钮之一。
 */
 @PostMapping
 public ResponseEntity<SessionDtos.SessionOut> create(
 @AuthenticationPrincipal AuthUser me,
 @RequestBody(required = false) SessionDtos.CreateRequest payload) {
 return ResponseEntity.ok(sessionService.create(me.id(), payload));
 }

 /**
 * GET /api/sessions/{sessionId}/messages —— 该会话的全部消息。
 *
 * <p>返回里已经把附件引用补全成「文件名 + 大小」（{@code ref_files}），
 * 前端不用再为每条消息单独请求。
 */
 @GetMapping("/{sessionId}/messages")
 public ResponseEntity<List<SessionDtos.MessageOut>> messages(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long sessionId) {
 return ResponseEntity.ok(sessionService.messages(me.id(), sessionId));
 }

 /**
 * PATCH /api/sessions/{sessionId} —— 改标题 / 切模型 / 改启用的技能与知识库。
 *
 * <p>切模型的联动逻辑较复杂（provider 与 provider_id 互相影响），
 * 详见 {@code SessionService.update} 的注释。
 */
 @PatchMapping("/{sessionId}")
 public ResponseEntity<SessionDtos.SessionOut> update(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long sessionId,
 @RequestBody SessionDtos.UpdateRequest payload) {
 return ResponseEntity.ok(sessionService.update(me.id(), sessionId, payload));
 }

 /** DELETE /api/sessions/{sessionId} —— 删除会话（含消息与记忆）。 */
 @DeleteMapping("/{sessionId}")
 public ResponseEntity<StatusResponse> delete(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long sessionId) {
 sessionService.delete(me.id(), sessionId);
 return ResponseEntity.ok(StatusResponse.deleted());
 }

 /**
 * DELETE /api/sessions/{sessionId}/messages/{messageId} —— 删除单条消息。
 *
 * <p><b>会连带删除同一轮的另一条</b>（删提问连带删回答，删回答连带删提问），
 * 返回的 {@code ids} 里就是本次实际删掉的全部 id。
 */
 @DeleteMapping("/{sessionId}/messages/{messageId}")
 public ResponseEntity<SessionDtos.DeleteMessagesResult> deleteMessage(
 @AuthenticationPrincipal AuthUser me,
 @PathVariable Long sessionId,
 @PathVariable Long messageId) {
 return ResponseEntity.ok(sessionService.deleteMessage(me.id(), sessionId, messageId));
 }

 /**
 * DELETE /api/sessions —— 清空当前用户的全部会话。
 *
 * <p>注意路径是<b>集合本身</b>（不带 id），语义是"全部清空"。
 * 返回 {@code {"status":"deleted","count":n}}，
 * {@code count} 是清理前的会话数，供前端给出"N 个会话已清空"的反馈。
 */
 @DeleteMapping
 public ResponseEntity<SessionDtos.ClearAllResult> deleteAll(
 @AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(sessionService.deleteAll(me.id()));
 }
}
