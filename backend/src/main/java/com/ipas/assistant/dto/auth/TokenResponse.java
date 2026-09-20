package com.ipas.assistant.dto.auth;

/**
 * 登录 / 注册成功后的响应体。
 *
 * <p>对应 {@code schemas.TokenResponse}：
 * <pre>
 * class TokenResponse(BaseModel):
 * token: str
 * token_type: str = "bearer"
 * username: str
 * </pre>
 *
 * <p>前端类型定义（{@code frontend/src/types.ts}）与之严格对应：
 * <pre>
 * export interface AuthResult {
 * token: string
 * token_type: string
 * username: string
 * }
 * </pre>
 *
 * <p><b>三个字段一个都不能少</b>：
 * <ul>
 * <li>{@code token} —— 存进 localStorage，随后由 axios 拦截器自动塞进
 * {@code Authorization} 头；</li>
 * <li>{@code token_type} —— 前端虽然当前没用到，但接口类型里定义了它。
 * 字段名要经 Jackson 的 SNAKE_CASE 策略转成 {@code token_type}，
 * 不能输出成 {@code tokenType}，否则 TypeScript 类型对不上
 * （运行时不会报错，但会让接口契约失真）；</li>
 * <li>{@code username} —— 界面右上角显示与 localStorage 缓存都要用。</li>
 * </ul>
 *
 * @param token 访问令牌（JWT 紧凑格式）
 * @param tokenType 令牌类型，恒为 "bearer"（与早期设计默认值一致）
 * @param username 登录账号
 */
public record TokenResponse(
 String token,
 String tokenType,
 String username
) {

 /**
 * 便捷构造：自动补上 tokenType = "bearer"，
 * 对应 数据校验框架 的 {@code token_type: str = "bearer"} 默认值。
 */
 public static TokenResponse of(String token, String username) {
 return new TokenResponse(token, "bearer", username);
 }
}
