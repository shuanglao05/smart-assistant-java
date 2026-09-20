package com.ipas.assistant.controller;

import com.ipas.assistant.dto.ChatRequest;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式聊天接口。对应 的流式入口。
 *
 * <h2>契约（前端零改动，必须逐字对齐）</h2>
 *
 * <p>前端 {@code ChatWindow.tsx} 里：
 * <pre>
 * fetch('/api/chat/stream', {
 * method: 'POST',
 * headers: { 'Content-Type': 'application/json', Authorization: 'Bearer &lt;token&gt;' },
 * body: JSON.stringify({ session_id, message, file_ids, regenerate, ... })
 * })
 * </pre>
 * 然后按 {@code data: {...}} 逐帧解析 SSE 事件。所以：
 * <ul>
 * <li>路径必须是 {@code /api/chat/stream}；</li>
 * <li>鉴权走 {@code Authorization: Bearer}，未带 token 由 Security 过滤器链统一返回 401；</li>
 * <li>正常时返回 SseEmitter（HTTP 200），随后持续推
 * {@code {"token"}} / {@code {"reasoning"}} / {@code {"sources"}} /
 * {@code {"done":true,"provider":...,"model":...,"user_id":...,"assistant_id":...}}；</li>
 * <li>尚未开始推流阶段的错误（会话不存在 / 参数非法 / 模型未配置）以普通 JSON
 * {@code {"detail":"..."}} 返回，前端走 {@code !resp.ok} 分支读取。</li>
 * </ul>
 *
 * <h2>为什么 {@code @Profile("!test")}</h2>
 *
 * <p>本控制器依赖真实大模型与 MySQL 记忆表，必须排除在「不依赖 MySQL」的契约测试之外。
 * 契约测试激活的是 {@code test} profile，因此本 Bean 不会被装配，49 个契约测试零改动、零回归。
 * （与 {@code ChatService} / {@code AgentFactory} 等 Agent 相关 Bean 的排除策略一致。）</p>
 */
@RestController
@RequestMapping("/api/chat")
@Profile("!test")
public class ChatController {

 private final ChatService chatService;

 public ChatController(ChatService chatService) {
 this.chatService = chatService;
 }

 /**
 * POST /api/chat/stream —— 流式聊天（SSE）。
 *
 * <p>{@code @Valid} 让 {@code ChatRequest.session_id} 上的 {@code @NotNull} 生效：
 * 缺字段时由 {@code GlobalExceptionHandler} 转成 422 + {@code {"detail":...}}，
 * 与前端的错误处理分支一致。</p>
 *
 * <p>会话不存在 / 模型未配置等错误在 {@code chatService.stream} 内部<b>同步</b>抛出，
 * 由全局异常处理器转成 4xx JSON（连接尚未以 SSE 形式建立）。一旦返回 SseEmitter，
 * 后续错误就以 {@code {"error":"..."}} 事件形式回传（连接保持 200）。</p>
 */
 @PostMapping("/stream")
 public ResponseEntity<SseEmitter> stream(
 @AuthenticationPrincipal AuthUser me,
 @Valid @RequestBody ChatRequest body) {
 SseEmitter emitter = chatService.stream(me.id(), body);
 // ★ 显式声明 Connection: close（并固定 Content-Type 为 text/event-stream）
 //
 // 【真实 bug】前端开发环境走 Vite dev server 的反向代理（http-proxy）访问本接口。
 // 实测同一段流：直连后端 1 秒左右就正常结束；经代理虽然能收到全部事件（含 done），
 // 但连接【永远不会结束】，浏览器 fetch 的 reader 一直拿不到 done →
 // ChatWindow 里清除 streamingSids 的 finally 永远不执行 →
 // 表现就是"答案都生成完了，输入框右侧却一直停在「停止」按钮"。
 // 声明 close 后，流结束即真正关闭连接，代理与浏览器都能立刻感知到 EOF。
 return ResponseEntity.ok()
 .contentType(MediaType.TEXT_EVENT_STREAM)
 .header(HttpHeaders.CONNECTION, "close")
 .body(emitter);
 }
}
