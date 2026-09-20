package com.ipas.assistant.controller;

import com.ipas.assistant.dto.CourseDtos;
import com.ipas.assistant.dto.StatusResponse;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.CourseService;
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
 * 课表接口。对应 的 {@code APIRouter(prefix="/api/courses")}。
 */
@RestController
@RequestMapping("/api/courses")
public class CourseController {

 private final CourseService courseService;

 public CourseController(CourseService courseService) {
 this.courseService = courseService;
 }

 /** GET /api/courses —— 课表列表（按星期 + 起始节次排序）。 */
 @GetMapping
 public ResponseEntity<List<CourseDtos.Out>> list(@AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(courseService.list(me.id()));
 }

 /** POST /api/courses —— 新建课程（节次倒挂会被自动校正）。 */
 @PostMapping
 public ResponseEntity<CourseDtos.Out> create(@AuthenticationPrincipal AuthUser me,
 @Valid @RequestBody CourseDtos.Create payload) {
 return ResponseEntity.ok(courseService.create(me.id(), payload));
 }

 /**
 * PATCH /api/courses/{id} —— 更新课程。
 *
 * <p>所有字段改完后会统一做一次节次校正 ——
 * 因为前端可能只改其中一个字段而造成 start &gt; end（详见 Service 注释）。
 */
 @PatchMapping("/{id}")
 public ResponseEntity<CourseDtos.Out> update(@AuthenticationPrincipal AuthUser me,
 @PathVariable Long id,
 @RequestBody CourseDtos.Update payload) {
 return ResponseEntity.ok(courseService.update(me.id(), id, payload));
 }

 /** DELETE /api/courses/{id} —— 删除课程。 */
 @DeleteMapping("/{id}")
 public ResponseEntity<StatusResponse> delete(@AuthenticationPrincipal AuthUser me,
 @PathVariable Long id) {
 courseService.delete(me.id(), id);
 return ResponseEntity.ok(StatusResponse.deleted());
 }

 /**
 * POST /api/courses/import —— 智能导入课表（网址 / 文本 / 截图）。
 *
 * <p>已接入 Spring AI（第三阶段完成，原先的 501 占位已移除）：会抓网页 / 调大模型
 * 解析出课程草稿。输入合法性校验（三种输入都没给 → 400）在 Service 里先触发。
 *
 * <p>本接口<b>不落库</b>：返回解析出的课程草稿，前端预览确认后再逐条 POST 创建。
 * 这是早期设计的设计（避免 AI 解析错误直接污染用户的课表）。
 */
 @PostMapping("/import")
 public ResponseEntity<CourseDtos.ImportResponse> importCourses(
 @AuthenticationPrincipal AuthUser me,
 @RequestBody CourseDtos.ImportRequest payload) {
 return ResponseEntity.ok(courseService.importCourses(me.id(), payload));
 }
}
