package com.ipas.assistant.controller;

import com.ipas.assistant.dto.TraceDtos;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.trace.ChatTraceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行过程（trace）查询接口。
 *
 * <h2>路径为什么挂在 /api/sessions 下</h2>
 *
 * <p>时间线的锚点是"某一轮回答"，而助手消息天然属于某个会话。挂在
 * {@code /api/sessions/{sid}/messages/{mid}/trace} 下与既有的
 * {@code GET/DELETE .../messages/{mid}} 保持同一层级，语义清楚，前端也只需拼接已有的 id。
 *
 * <h2>这是新增接口，不是既有契约变更</h2>
 *
 * <p>它只增加了一个读取端点，既有接口的请求 / 响应一个字段都没动，
 * 所以不需要前端配合改造即可上线；前端要展示时再调用即可。
 */
@RestController
@RequestMapping("/api/sessions")
public class TraceController {

    private final ChatTraceService traceService;

    public TraceController(ChatTraceService traceService) {
        this.traceService = traceService;
    }

    /**
     * GET /api/sessions/{sessionId}/messages/{messageId}/trace —— 某轮回答的执行时间线。
     *
     * <p>返回各阶段耗时（含首字延迟与总耗时）与 token 估算值。
     * 会话不属于当前用户时 404（不泄露存在性）。
     * 若该轮没有记录（例如服务重启前的历史消息），返回空的 stages 列表而不是报错。
     */
    @GetMapping("/{sessionId}/messages/{messageId}/trace")
    public ResponseEntity<TraceDtos.TraceTimeline> trace(
            @AuthenticationPrincipal AuthUser me,
            @PathVariable Long sessionId,
            @PathVariable Long messageId) {
        return ResponseEntity.ok(traceService.timeline(me.id(), sessionId, messageId));
    }
}
