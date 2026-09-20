package com.ipas.assistant.common;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p><b>核心职责：让 Java 后端吐出的错误格式与 接口框架 完全一致</b>，这样 React 前端
 * 读 {@code err.response.data.detail} 的逻辑不用改一行。
 *
 * <p>原 早期实现的错误来源有两类：
 *
 * <ol>
 * <li><b>业务显式抛出</b>：{@code raise HTTPException(400, "用户名已存在")}
 * → 对应这里的 {@link ApiException} 分支。</li>
 * <li><b>框架参数校验</b>：数据校验框架 校验失败，接口框架 返回 <b>422</b>，
 * 且 {@code detail} 是一个数组（每项含 loc/msg/type）。</li>
 * </ol>
 *
 * <p>第 2 类这里做了一点<b>有意的简化</b>：422 状态码保留，但 {@code detail} 返回
 * 拼好的中文字符串，而不是数组。理由是早期设计绝大多数面向用户的校验都写在业务代码里
 * （抛中文 HTTPException），数据校验框架 的 422 只在「请求体格式完全不对」时出现，
 * 前端本来也只是当异常兜底。若返回数组，前端某些直接把 detail 塞进 JSX 的地方
 * 反而会渲染出 {@code [object Object]}。这是唯一一处与 接口框架 的格式差异，
 * 已在 docs 里记录。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

 private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

 /**
 * 业务异常：状态码与提示都由抛出处决定。
 *
 * <p>注意日志级别用 WARN 且只打一行 —— 这类异常是「预期内的业务结果」
 * （密码错、资源不存在），不是系统故障，不该打堆栈刷屏。
 */
 @ExceptionHandler(ApiException.class)
 public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
 log.warn("业务异常 {}: {}", ex.getStatus().value(), ex.getMessage());
 return ResponseEntity.status(ex.getStatus()).body(ErrorBody.of(ex.getMessage()));
 }

 /**
 * {@code @Valid} 校验失败（请求体字段不合法）。
 *
 * <p>例如 {@code RegisterRequest} 上标注了 {@code @NotBlank}，前端传了空 username。
 * 这里把每个失败字段拼成「字段名: 原因」的可读文本，返回 422（与 接口框架 一致）。
 */
 @ExceptionHandler(MethodArgumentNotValidException.class)
 public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
 String detail = ex.getBindingResult().getFieldErrors().stream()
 .map(this::describeFieldError)
 .collect(Collectors.joining("；"));
 if (detail.isBlank()) {
 detail = "请求参数不合法";
 }
 log.warn("参数校验失败: {}", detail);
 return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ErrorBody.of(detail));
 }

 /**
 * 请求体不是合法 JSON，或字段类型对不上（例如把字符串传给了 int 字段）。
 * 对应 接口框架 解析失败时的 422。
 */
 @ExceptionHandler(HttpMessageNotReadableException.class)
 public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
 log.warn("请求体解析失败: {}", ex.getMostSpecificCause().getMessage());
 return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .body(ErrorBody.of("请求体格式不正确"));
 }

 /** 必填的 query 参数缺失（接口框架 对应 422）。 */
 @ExceptionHandler(MissingServletRequestParameterException.class)
 public ResponseEntity<ErrorBody> handleMissingParam(MissingServletRequestParameterException ex) {
 log.warn("缺少必填参数: {}", ex.getParameterName());
 return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .body(ErrorBody.of("缺少必填参数：" + ex.getParameterName()));
 }

 /** query 参数类型不匹配，例如 {@code ?days=abc}（接口框架 对应 422）。 */
 @ExceptionHandler(MethodArgumentTypeMismatchException.class)
 public ResponseEntity<ErrorBody> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
 log.warn("参数类型不匹配: {}={}", ex.getName(), ex.getValue());
 return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
 .body(ErrorBody.of("参数 " + ex.getName() + " 类型不正确"));
 }

 /**
 * {@code @RequestParam} / {@code @PathVariable} 上的约束校验失败
 * （如 {@code @Min(1) @Max(4)} 的 days 传了 9）。
 *
 * <p><b>这个分支必须存在，否则那些注解会被静默忽略</b>：
 * 方法参数上的约束由方法级校验（method validation）处理，
 * 抛的是 {@code ConstraintViolationException}，而不是
 * {@code MethodArgumentNotValidException}（后者只针对 {@code @RequestBody}）。
 * 而它默认会落到「未预期的服务端异常」分支变成 500 —— 明明是用户输入问题，
 * 却报成服务端错误，排查时会往错误的方向找。
 *
 * <p>状态码取 422，与 接口框架 的 {@code Query(ge=..., le=...)} 行为一致。
 */
 @ExceptionHandler(ConstraintViolationException.class)
 public ResponseEntity<ErrorBody> handleConstraintViolation(ConstraintViolationException ex) {
 String detail = ex.getConstraintViolations().stream()
 .map(v -> {
 // 属性路径形如 "weather.days"，只取最后一段作为字段名更可读
 String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
 int dot = path.lastIndexOf('.');
 String field = dot >= 0 ? path.substring(dot + 1) : path;
 return field + ": " + v.getMessage();
 })
 .collect(Collectors.joining("；"));
 if (detail.isBlank()) {
 detail = "请求参数不合法";
 }
 log.warn("参数约束校验失败: {}", detail);
 return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ErrorBody.of(detail));
 }

 /**
 * 请求路径不存在。
 *
 * <p>接口框架 找不到路由时返回 404 且 body 为 {@code {"detail":"Not Found"}}。
 * Spring 默认会走 {@code /error} 白页或吐出一大坨结构化 JSON，这里统一成前者，
 * 避免前端拦截器拿到意料外的结构。
 */
 @ExceptionHandler(NoResourceFoundException.class)
 public ResponseEntity<ErrorBody> handleNotFound(NoResourceFoundException ex) {
 return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorBody.of("Not Found"));
 }

 /** HTTP 方法用错，例如对只支持 GET 的接口发 POST。 */
 @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
 public ResponseEntity<ErrorBody> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
 return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
 .body(ErrorBody.of("请求方法不被支持：" + ex.getMethod()));
 }

 /**
 * 上传文件超过 Spring 的 multipart 硬上限（application.yml 里配的 64MB）。
 *
 * <p>注意：业务上真正生效的上限是 {@code app.upload.max-mb}（默认 50MB），
 * 由 files 模块校验并给出中文提示。这里的 64MB 只是「防止有人绕过业务校验
 * 直接把超大 body 打进来」的兜底防线。
 */
 @ExceptionHandler(MaxUploadSizeExceededException.class)
 public ResponseEntity<ErrorBody> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
 log.warn("上传文件超过 multipart 硬上限: {}", ex.getMessage());
 return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
 .body(ErrorBody.of("上传文件过大，请压缩后重试"));
 }

 /**
 * 兜底：任何未预料到的异常。
 *
 * <p>早期设计在 chat 的流式分支里用 {@code traceback.print_exc()} 把完整堆栈打到控制台
 * （那是排查云端报错的关键手段），这里保留同样的行为：<b>堆栈只给开发者看，
 * 面向用户的响应里不放内部细节</b>，避免泄露实现信息。
 *
 * <p><b>⚠️ 响应已提交时的处理</b>：SSE 流式接口（{@code /api/chat/stream}）在
 * {@code SseEmitter.complete()} 之后，Tomcat 异步收尾阶段可能再抛一次异常。
 * 此时响应已提交（Content-Type 已是 text/event-stream），若仍返回
 * {@code ResponseEntity}，Spring 会试图把错误体写成 JSON → 因"没有
 * text/event-stream 的转换器"而<b>二次失败</b>，日志刷屏且连接收尾不干净。
 * 所以这里判断 {@code response.isCommitted()}，已提交就<b>不再写任何东西</b>
 * （数据早已送达前端，写也白写），只记一条 debug 级别的日志。
 */
 @ExceptionHandler(Exception.class)
 public ResponseEntity<ErrorBody> handleUnexpected(Exception ex, HttpServletResponse response) {
 if (response != null && response.isCommitted()) {
 log.debug("响应已提交，跳过错误体写入（典型于 SSE 流结束后的异步收尾）: {}",
 ex.getMessage());
 return null;
 }
 log.error("未预期的服务端异常", ex);
 return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
 .body(ErrorBody.of("服务器内部错误：" + ex.getClass().getSimpleName()));
 }

 /** 把字段校验错误转成一行中文说明。 */
 private String describeFieldError(FieldError error) {
 String reason = error.getDefaultMessage();
 return error.getField() + (reason == null ? " 不合法" : ": " + reason);
 }
}
