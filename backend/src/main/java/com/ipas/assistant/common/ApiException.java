package com.ipas.assistant.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与中文提示，由 {@link GlobalExceptionHandler} 统一转成
 * 接口框架 风格的 {@code {"detail": "..."}} 响应。
 *
 * <p>这是早期设计里 {@code raise HTTPException(400, "用户名已存在")} 的对应写法。
 * 框架层那句话一句话完成了「中断流程 + 决定状态码 + 给出提示」三件事；
 * Java 里靠抛异常 + 全局处理器达到同样效果，业务代码不用关心响应怎么拼。
 *
 * <p>继承 {@link RuntimeException} 而不是受检异常的原因：鉴权失败、参数不合法
 * 这类情况几乎每个方法都可能发生，若用受检异常，所有调用链上都要写
 * {@code throws}，代码会被噪音淹没。Spring 的事务回滚对 RuntimeException 也默认生效。
 *
 * <p>用法示例：
 * <pre>
 * if (userRepository.existsByUsername(username)) {
 * throw new ApiException(HttpStatus.BAD_REQUEST, "用户名已存在");
 * }
 * </pre>
 */
public class ApiException extends RuntimeException {

 private final HttpStatus status;

 /**
 * @param status HTTP 状态码（400 参数问题 / 401 未认证 / 404 资源不存在 / 409 冲突）
 * @param message 面向用户的中文提示，会原样写进响应体的 {@code detail} 字段
 */
 public ApiException(HttpStatus status, String message) {
 super(message);
 this.status = status;
 }

 /** 便捷构造：400 Bad Request。 */
 public static ApiException badRequest(String message) {
 return new ApiException(HttpStatus.BAD_REQUEST, message);
 }

 /** 便捷构造：401 Unauthorized。 */
 public static ApiException unauthorized(String message) {
 return new ApiException(HttpStatus.UNAUTHORIZED, message);
 }

 /** 便捷构造：404 Not Found。 */
 public static ApiException notFound(String message) {
 return new ApiException(HttpStatus.NOT_FOUND, message);
 }

 public HttpStatus getStatus() {
 return status;
 }
}
