package com.ipas.assistant.controller;

import com.ipas.assistant.dto.auth.LoginRequest;
import com.ipas.assistant.dto.auth.RegisterRequest;
import com.ipas.assistant.dto.auth.TokenResponse;
import com.ipas.assistant.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册 / 登录接口。对应 的
 * {@code router = APIRouter(prefix="/api/auth")}。
 *
 * <p>两个接口都是<b>公开</b>的（在 {@code SecurityConfig.PUBLIC_PATHS} 里放行），
 * 因为此时用户还没有 token。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

 private final AuthService authService;

 public AuthController(AuthService authService) {
 this.authService = authService;
 }

 /**
 * POST /api/auth/register —— 注册。
 *
 * <p>{@code @Valid} 触发 DTO 上的校验注解；校验失败由
 * {@code GlobalExceptionHandler} 转成 422 响应。
 *
 * <p>返回 200 而不是 201：早期设计 接口框架 的默认成功状态码就是 200
 * （没有在装饰器里写 {@code status_code=201}），前端也只判断 2xx。
 * 保持一致可以避免前端出现「明明成功却走了异常分支」的问题。
 */
 @PostMapping("/register")
 public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest payload) {
 return ResponseEntity.ok(authService.register(payload));
 }

 /**
 * POST /api/auth/login —— 登录。
 *
 * <p>注意这里<b>没有</b>加 {@code @Valid}：{@code LoginRequest} 是故意不加校验注解的
 * （见该类注释）—— 账号密码格式问题统一由 AuthService 返回
 * 401「用户名或密码错误」，不对外暴露「是用户名格式不对还是密码不对」。
 */
 @PostMapping("/login")
 public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest payload) {
 return ResponseEntity.ok(authService.login(payload));
 }
}
