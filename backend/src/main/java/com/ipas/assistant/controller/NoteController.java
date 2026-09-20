package com.ipas.assistant.controller;

import com.ipas.assistant.dto.NoteDtos;
import com.ipas.assistant.dto.StatusResponse;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.NoteService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 笔记接口。对应 的 {@code APIRouter(prefix="/api/notes")}。
 */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

 private final NoteService noteService;

 public NoteController(NoteService noteService) {
 this.noteService = noteService;
 }

 /** GET /api/notes —— 全部笔记，按日期与修改时间倒序。 */
 @GetMapping
 public ResponseEntity<List<NoteDtos.Out>> list(@AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(noteService.list(me.id()));
 }

 /** POST /api/notes —— 新建笔记（day 缺省为今天）。 */
 @PostMapping
 public ResponseEntity<NoteDtos.Out> create(@AuthenticationPrincipal AuthUser me,
 @RequestBody NoteDtos.Create payload) {
 return ResponseEntity.ok(noteService.create(me.id(), payload));
 }

 /** PATCH /api/notes/{id} —— 更新笔记。 */
 @PatchMapping("/{id}")
 public ResponseEntity<NoteDtos.Out> update(@AuthenticationPrincipal AuthUser me,
 @PathVariable Long id,
 @RequestBody NoteDtos.Update payload) {
 return ResponseEntity.ok(noteService.update(me.id(), id, payload));
 }

 /** DELETE /api/notes/{id} —— 删除笔记。 */
 @DeleteMapping("/{id}")
 public ResponseEntity<StatusResponse> delete(@AuthenticationPrincipal AuthUser me,
 @PathVariable Long id) {
 noteService.delete(me.id(), id);
 return ResponseEntity.ok(StatusResponse.deleted());
 }

 /**
 * POST /api/notes/summarize —— 让 AI 归纳某天的笔记。
 *
 * <p>已接入 Spring AI（第三阶段完成，原先的 501 占位已移除）。
 * 「这一天还没有笔记」的 400 校验在 Service 里先触发，保证纯业务错误
 * 不会被模型调用问题掩盖。
 * <p>注意：构造模型（如云端 Key 未配置 → 400）放在 try 外，只有真正的模型调用
 * 才包进 try，否则会把「配置缺失(400)」误包装成「服务端故障(500)」。
 */
 @PostMapping("/summarize")
 public ResponseEntity<NoteDtos.SummarizeResponse> summarize(
 @AuthenticationPrincipal AuthUser me,
 @RequestBody NoteDtos.SummarizeRequest payload) {
 return ResponseEntity.ok(noteService.summarize(me.id(), payload));
 }
}
