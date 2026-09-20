package com.ipas.assistant.controller;

import com.ipas.assistant.dto.ScheduleDtos;
import com.ipas.assistant.dto.StatusResponse;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.ScheduleService;
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
 * 日程接口。对应 的
 * {@code APIRouter(prefix="/api/schedules")}。
 *
 * <p>本控制器只有增删改查。<b>提醒推送不在接口里</b> ——
 * 它由后台定时任务 {@code ScheduleReminderJob} 每 30 秒自动执行
 * （早期设计在 启动时挂了一个协程，同一设计）。
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

 private final ScheduleService scheduleService;

 public ScheduleController(ScheduleService scheduleService) {
 this.scheduleService = scheduleService;
 }

 /** GET /api/schedules —— 日程列表，按开始时刻正序。 */
 @GetMapping
 public ResponseEntity<List<ScheduleDtos.Out>> list(@AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(scheduleService.list(me.id()));
 }

 /** POST /api/schedules —— 新建日程。 */
 @PostMapping
 public ResponseEntity<ScheduleDtos.Out> create(@AuthenticationPrincipal AuthUser me,
 @Valid @RequestBody ScheduleDtos.Create payload) {
 return ResponseEntity.ok(scheduleService.create(me.id(), payload));
 }

 /**
 * PATCH /api/schedules/{id} —— 更新日程。
 *
 * <p>⚠️ 改了 {@code startAt} 会把 {@code reminded} 重置为 false，
 * 否则改期之后这条日程永远不会再提醒。详见 Service 注释。
 */
 @PatchMapping("/{id}")
 public ResponseEntity<ScheduleDtos.Out> update(@AuthenticationPrincipal AuthUser me,
 @PathVariable Long id,
 @RequestBody ScheduleDtos.Update payload) {
 return ResponseEntity.ok(scheduleService.update(me.id(), id, payload));
 }

 /** DELETE /api/schedules/{id} —— 删除日程。 */
 @DeleteMapping("/{id}")
 public ResponseEntity<StatusResponse> delete(@AuthenticationPrincipal AuthUser me,
 @PathVariable Long id) {
 scheduleService.delete(me.id(), id);
 return ResponseEntity.ok(StatusResponse.deleted());
 }
}
