package com.ipas.assistant.controller;

import com.ipas.assistant.dto.user.UserOut;
import com.ipas.assistant.dto.user.UserUpdateRequest;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人资料接口。对应 的
 * {@code router = APIRouter(prefix="/api/users")}。
 *
 * <p><b>关于「当前用户」的取得方式</b>：
 * 早期设计靠 接口框架 的依赖注入 —— 路由函数签名里写上
 * {@code current_user=Depends(get_current_user)}，框架就会先解析 JWT
 * 再把 User 对象塞进参数。
 *
 * <p>Spring 的等价写法是 {@code @AuthenticationPrincipal AuthUser me}：
 * 由 {@code JwtAuthenticationFilter} 把 {@link AuthUser} 放进 SecurityContext，
 * 这里直接取出来。两者的共同优点是<b>业务代码完全看不到 JWT 解析过程</b>，
 * 也不存在「忘了校验」的可能 —— 没带合法 token 的请求根本进不到方法体
 * （会被 {@code SecurityConfig} 拦在 401）。
 *
 * <p>{@code @AuthenticationPrincipal} 不允许解析失败时返回 null（除非写
 * {@code required = false}）。本项目该注解只用在已认证接口上，
 * 因此 {@code me} 一定非空。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

 private final UserService userService;

 public UserController(UserService userService) {
 this.userService = userService;
 }

 /**
 * GET /api/users/me —— 查看个人资料。
 */
 @GetMapping("/me")
 public ResponseEntity<UserOut> getMe(@AuthenticationPrincipal AuthUser me) {
 return ResponseEntity.ok(userService.getProfile(me.id()));
 }

 /**
 * PATCH /api/users/me —— 修改资料或改密。
 *
 * <p>用 PATCH 而不是 PUT 的原因与早期设计一致：这是<b>部分更新</b>语义 ——
 * 只提交要改的字段，没提交的字段保持原值。用 PUT 会暗示「整体替换」，
 * 调用方就得把完整对象传回来，容易误清空字段。
 */
 @PatchMapping("/me")
 public ResponseEntity<UserOut> updateMe(@AuthenticationPrincipal AuthUser me,
 @Valid @RequestBody UserUpdateRequest payload) {
 return ResponseEntity.ok(userService.updateProfile(me.id(), payload));
 }
}
