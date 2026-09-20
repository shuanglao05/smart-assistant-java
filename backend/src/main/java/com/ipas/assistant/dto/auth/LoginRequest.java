package com.ipas.assistant.dto.auth;

/**
 * 登录请求体。对应 {@code schemas.LoginRequest}：
 * <pre>
 * class LoginRequest(BaseModel):
 * username: str
 * password: str
 * </pre>
 *
 * <p><b>注意这里刻意没有加 {@code @NotBlank} 等校验注解</b>，这是有意为之：
 * 早期设计的 LoginRequest 两个字段都是裸 {@code str}，不带任何约束。
 *
 * <p>登录接口不该对「格式」做过多判断——用户名不存在、密码错误、用户名是空的，
 * 对调用方而言都应该得到同一个含糊的回应（401「用户名或密码错误」），
 * 而不是「用户名不能为空」（400）。后者会泄露「这个用户名格式不对」
 * 这类信息，也给暴力破解提供了区分依据。校验逻辑统一放在 AuthService 里。
 */
public record LoginRequest(
 String username,
 String password
) {
}
