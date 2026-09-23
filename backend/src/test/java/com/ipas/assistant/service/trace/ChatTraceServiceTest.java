package com.ipas.assistant.service.trace;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.dto.TraceDtos;
import com.ipas.assistant.entity.ChatTrace;
import com.ipas.assistant.entity.Conversation;
import com.ipas.assistant.repository.ChatTraceRepository;
import com.ipas.assistant.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行过程记录服务的单元测试。
 *
 * <p>这里守两条底线：<b>写失败绝不能影响对话</b>（追踪只是附属数据），
 * 以及<b>越权查不到别人的会话</b>（不泄露存在性）。另外 token 是估算值，
 * 估算口径也要固定下来，免得以后有人"顺手"改成别的算法。
 */
class ChatTraceServiceTest {

    private ChatTraceRepository traceRepository;
    private ConversationRepository conversationRepository;
    private ChatTraceService service;

    @BeforeEach
    void setUp() {
        traceRepository = mock(ChatTraceRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        service = new ChatTraceService(traceRepository, conversationRepository, 30);
    }

    @Test
    @DisplayName("记录阶段：耗时被夹成非负，阶段名原样落库")
    void recordClampsNegativeDuration() {
        service.record(1L, 2L, ChatTraceService.STAGE_PREPARE, -5, null);

        ArgumentCaptor<ChatTrace> captor = ArgumentCaptor.forClass(ChatTrace.class);
        verify(traceRepository).save(captor.capture());
        assertEquals(0, captor.getValue().getDurationMs(), "负数耗时应被夹成 0");
        assertEquals(ChatTraceService.STAGE_PREPARE, captor.getValue().getStage());
        assertEquals(2L, captor.getValue().getExchangeId());
    }

    @Test
    @DisplayName("收尾记录：写入 token 估算；提示词长度未知时不编造数字")
    void recordDoneEstimatesTokens() {
        service.recordDone(1L, 2L, 1200, "ollama", "qwen3:8b", 200, 100);

        ArgumentCaptor<ChatTrace> captor = ArgumentCaptor.forClass(ChatTrace.class);
        verify(traceRepository).save(captor.capture());
        assertEquals(100, captor.getValue().getPromptTokens());    // 200 字符 / 2
        assertEquals(50, captor.getValue().getCompletionTokens()); // 100 字符 / 2
        assertEquals(1200, captor.getValue().getDurationMs());

        reset(traceRepository);
        service.recordDone(1L, 2L, 10, "ollama", "m", 0, 8);
        verify(traceRepository).save(captor.capture());
        assertNull(captor.getValue().getPromptTokens(), "未知长度时不应填一个假数");
        assertEquals(4, captor.getValue().getCompletionTokens());
    }

    @Test
    @DisplayName("写失败被吞掉：追踪是附属数据，绝不能影响对话")
    void writeFailureIsSwallowed() {
        when(traceRepository.save(any(ChatTrace.class)))
                .thenThrow(new RuntimeException("数据库挂了"));

        assertDoesNotThrow(() -> service.record(1L, 2L, ChatTraceService.STAGE_ROUTE, 1, "模式"));
        assertDoesNotThrow(() -> service.recordDone(1L, 2L, 1, "p", "m", 1, 1));
    }

    @Test
    @DisplayName("时间线：提取总耗时与首字延迟，并返回全部阶段")
    void timelineExtractsMetrics() {
        Conversation conv = new Conversation();
        conv.setId(1L);
        when(conversationRepository.findByIdAndUserId(1L, 9L)).thenReturn(Optional.of(conv));
        when(traceRepository.findByConversationIdAndExchangeIdOrderByIdAsc(1L, 2L)).thenReturn(List.of(
                row(ChatTraceService.STAGE_PREPARE, 30),
                row(ChatTraceService.STAGE_FIRST_TOKEN, 1800),
                row(ChatTraceService.STAGE_DONE, 6200)));

        TraceDtos.TraceTimeline timeline = service.timeline(9L, 1L, 2L);

        assertEquals(6200, timeline.totalMs(), "总耗时取自 DONE 阶段");
        assertEquals(1800, timeline.firstTokenMs(), "首字延迟取自 FIRST_TOKEN 阶段");
        assertEquals(3, timeline.stages().size());
        assertEquals(1L, timeline.sessionId());
        assertEquals(2L, timeline.messageId());
    }

    @Test
    @DisplayName("时间线：会话不属于当前用户 → 404（不泄露存在性）")
    void timelineRejectsForeignSession() {
        when(conversationRepository.findByIdAndUserId(1L, 9L)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.timeline(9L, 1L, 2L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("时间线：没有记录时返回空列表而不是报错（例如服务重启前的历史消息）")
    void timelineIsEmptyWhenNoRecords() {
        Conversation conv = new Conversation();
        conv.setId(1L);
        when(conversationRepository.findByIdAndUserId(1L, 9L)).thenReturn(Optional.of(conv));
        when(traceRepository.findByConversationIdAndExchangeIdOrderByIdAsc(1L, 2L))
                .thenReturn(List.of());

        TraceDtos.TraceTimeline timeline = service.timeline(9L, 1L, 2L);

        assertEquals(0, timeline.totalMs());
        assertEquals(0, timeline.firstTokenMs());
        assertEquals(0, timeline.stages().size());
    }

    @Test
    @DisplayName("token 估算口径：2 字符 1 token，且至少为 1")
    void tokenEstimation() {
        assertEquals(0, ChatTraceService.estimateTokens(0));
        assertEquals(0, ChatTraceService.estimateTokens(-10));
        assertEquals(1, ChatTraceService.estimateTokens(1));
        assertEquals(50, ChatTraceService.estimateTokens(100));
    }

    private static ChatTrace row(String stage, int durationMs) {
        ChatTrace t = new ChatTrace();
        t.setStage(stage);
        t.setDurationMs(durationMs);
        return t;
    }
}
