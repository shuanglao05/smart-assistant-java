package com.ipas.assistant.controller;

import com.ipas.assistant.dto.StatusResponse;
import com.ipas.assistant.dto.TodoDtos;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.TodoService;
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
 * 待办接口。对应 的
 * {@code APIRouter(prefix="/api/todos")}。
 *
 * <p><b>关于 {@code @AuthenticationPrincipal AuthUser me}</b>：
 * 这是早期设计 {@code Depends(get_current_user)} 的 Spring 对应物。
 * JWT 过滤器（{@code JwtAuthenticationFilter}）已经校验过令牌并把
 * {@code AuthUser} 放进了 SecurityContext，这里直接取用即可 ——
 * 不需要在方法里再解析一次 token。
 *
 * <p>注意：<b>能走到这些方法就说明已经通过鉴权</b>（由 {@code SecurityConfig}
 * 的 {@code anyRequest().authenticated()} 保证），所以方法体内不需要再做登录判断。
 * 但<b>仍然必须用 {@code me.id()} 过滤数据</b> —— 鉴权解决的是"你是谁"，
 * 数据隔离解决的是"你只能碰自己的数据"，两者缺一不可。
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

 private final TodoService todoService;

 public TodoController(TodoService todoService) {
 this.todoService = todoService;
 }

 /** GET /api/todos —— 待办列表（未完成优先）。 */
 @GetMapping
 public ResponseEntity<List<TodoDtos.Out>> list(@AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(todoService.list(me.id()));
 }

 /** POST /api/todos —— 新建待办。 */
 @PostMapping
 public ResponseEntity<TodoDtos.Out> create(@AuthenticationPrincipal AuthUser me,
 @Valid @RequestBody TodoDtos.Create payload) {
 return ResponseEntity.ok(todoService.create(me.id(), payload));
 }

 /**
 * PATCH /api/todos/{id} —— 更新待办（改内容 / 勾选完成）。
 *
 * <p>用 PATCH 而不是 PUT：语义上这是"部分更新"，
 * 前端勾选完成时只发 {@code {"done": true}}，不会带上 task。
 */
 @PatchMapping("/{id}")
 public ResponseEntity<TodoDtos.Out> update(@AuthenticationPrincipal AuthUser me,
 @PathVariable Long id,
 @RequestBody TodoDtos.Update payload) {
 return ResponseEntity.ok(todoService.update(me.id(), id, payload));
 }

 /**
 * DELETE /api/todos/{id} —— 删除待办。
 *
 * <p>返回 {@code {"status": "deleted"}} 而不是 204 No Content：
 * 早期设计就是这么返回的，前端也据此判断（若改成 204，前端读
 * {@code res.data.status} 会拿到 undefined）。
 */
 @DeleteMapping("/{id}")
 public ResponseEntity<StatusResponse> delete(@AuthenticationPrincipal AuthUser me,
 @PathVariable Long id) {
 todoService.delete(me.id(), id);
 return ResponseEntity.ok(StatusResponse.deleted());
 }

 /**
 * GET /api/todos/stats —— 统计。
 *
 * <p>⚠️ 路径声明顺序有讲究：本方法必须声明为 {@code /stats} 且
 * <b>不能被 {@code /{id}} 抢先匹配</b>。当前只有 PATCH/DELETE 用了
 * {@code /{id}}，GET 下没有通配路径，所以不会冲突。
 * 但将来若新增 {@code GET /api/todos/{id}}，Spring 的路径匹配会优先
 * 选择更具体的字面量路径（{@code /stats}），仍然安全 ——
 * 这点比需要手动排路由顺序的框架省心。
 */
 @GetMapping("/stats")
 public ResponseEntity<TodoDtos.Stats> stats(@AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(todoService.stats(me.id()));
 }
}
