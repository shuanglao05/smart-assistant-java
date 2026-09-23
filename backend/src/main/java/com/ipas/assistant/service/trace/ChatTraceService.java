package com.ipas.assistant.service.trace;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.common.Times;
import com.ipas.assistant.dto.TraceDtos;
import com.ipas.assistant.entity.ChatTrace;
import com.ipas.assistant.repository.ChatTraceRepository;
import com.ipas.assistant.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行过程记录（可观测性）。
 *
 * <h2>为什么值得做</h2>
 *
 * <p>没有它时，"这句为什么答得不准"只能靠猜：不知道检索到几段、走了哪条链路、
 * 首字等了多久。这类信息在流式对话里天然被冲散 —— 记录下来，每一轮回答才能自证过程。
 *
 * <h2>写入策略：同步 + 失败静默（刻意不用异步）</h2>
 *
 * <p>一轮对话只写 5~6 行小记录，同步写入不过几毫秒；而异步写入有两个坏处：
 * 一是进程退出时可能丢掉最后几轮的记录（正是最需要排查的那些），
 * 二是需要再引一个线程池与失败重试，复杂度换不来收益。
 * 所以这里同步写，但<b>整体包在 try/catch 里</b>：追踪是附属数据，
 * 写失败绝不能影响对话本身。
 */
@Service
public class ChatTraceService {

    private static final Logger log = LoggerFactory.getLogger(ChatTraceService.class);

    // ---- 阶段名（对外可见的字符串，前端按它们画时间线）----
    /** 同步准备：校验归属、落库用户消息、预建助手行。 */
    public static final String STAGE_PREPARE = "PREPARE";
    /** 路由：判定走确定性知识问答还是 Agent。 */
    public static final String STAGE_ROUTE = "ROUTE";
    /** 检索：确定性链路的知识库检索（含查询改写）。 */
    public static final String STAGE_RETRIEVE = "RETRIEVE";
    /** 构建 Agent（仅 Agent 链路）。 */
    public static final String STAGE_AGENT_BUILD = "AGENT_BUILD";
    /** 首字延迟：从请求开始到第一个正文 token —— 最有价值的体验指标。 */
    public static final String STAGE_FIRST_TOKEN = "FIRST_TOKEN";
    /** 收尾：总耗时。 */
    public static final String STAGE_DONE = "DONE";

    /** detail 列长 1000，留余量。 */
    private static final int MAX_DETAIL_LEN = 950;

    /** 中文大致 2 个字符 ≈ 1 个 token，用于估算 token 量（真实 usage 在流式路径拿不到）。 */
    private static final int CHARS_PER_TOKEN = 2;

    private final ChatTraceRepository traceRepository;
    private final ConversationRepository conversationRepository;

    /** 记录保留天数。追踪数据只增不减会拖垮表，故定期清理。 */
    private final int retentionDays;

    public ChatTraceService(ChatTraceRepository traceRepository,
                            ConversationRepository conversationRepository,
                            @Value("${app.chat.trace-retention-days:30}") int retentionDays) {
        this.traceRepository = traceRepository;
        this.conversationRepository = conversationRepository;
        this.retentionDays = retentionDays;
    }

    /**
     * 记录一个执行阶段。
     *
     * @param conversationId 会话 id
     * @param exchangeId     本轮助手消息 id（前端查时间线的锚点）
     * @param stage          阶段名（用本类的常量）
     * @param durationMs     耗时；FIRST_TOKEN 传"从请求开始到首字"，DONE 传总耗时
     * @param detail         补充信息，可为 null
     */
    public void record(Long conversationId, Long exchangeId, String stage, long durationMs, String detail) {
        try {
            ChatTrace t = new ChatTrace();
            t.setConversationId(conversationId);
            t.setExchangeId(exchangeId);
            t.setStage(stage);
            t.setDurationMs(clampDuration(durationMs));
            t.setDetail(truncate(detail));
            traceRepository.save(t);
        } catch (Exception e) {
            // 追踪写失败绝不影响对话（它只是附属的可观测性数据）
            log.debug("写入执行阶段失败（已忽略）：{}", e.getMessage());
        }
    }

    /**
     * 记录收尾阶段：总耗时 + token 估算。
     *
     * @param promptChars   提示词/证据的字符数；&lt;=0 表示未知（不记 promptTokens）
     * @param answerChars   回答正文的字符数
     */
    public void recordDone(Long conversationId, Long exchangeId, long totalMs,
                           String provider, String model, int promptChars, int answerChars) {
        try {
            ChatTrace t = new ChatTrace();
            t.setConversationId(conversationId);
            t.setExchangeId(exchangeId);
            t.setStage(STAGE_DONE);
            t.setDurationMs(clampDuration(totalMs));
            if (promptChars > 0) {
                t.setPromptTokens(estimateTokens(promptChars));
            }
            t.setCompletionTokens(estimateTokens(answerChars));
            t.setDetail(truncate("provider=" + provider + " model=" + model
                    + " 回答" + answerChars + "字"));
            traceRepository.save(t);
        } catch (Exception e) {
            log.debug("写入收尾阶段失败（已忽略）：{}", e.getMessage());
        }
    }

    /**
     * 取某轮回答的执行时间线。
     *
     * <p>先校验会话归属（不属于该用户时 404，不泄露存在性）。
     */
    @Transactional(readOnly = true)
    public TraceDtos.TraceTimeline timeline(Long userId, Long sessionId, Long messageId) {
        conversationRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ApiException.notFound("会话不存在"));
        List<ChatTrace> rows = traceRepository
                .findByConversationIdAndExchangeIdOrderByIdAsc(sessionId, messageId);
        int totalMs = 0;
        int firstTokenMs = 0;
        for (ChatTrace t : rows) {
            if (STAGE_DONE.equals(t.getStage())) {
                totalMs = t.getDurationMs() == null ? 0 : t.getDurationMs();
            } else if (STAGE_FIRST_TOKEN.equals(t.getStage())) {
                firstTokenMs = t.getDurationMs() == null ? 0 : t.getDurationMs();
            }
        }
        return new TraceDtos.TraceTimeline(sessionId, messageId, totalMs, firstTokenMs,
                rows.stream().map(TraceDtos.TraceStage::from).toList());
    }

    /**
     * 定期清理过期记录（每天凌晨 4:30）。
     *
     * <p>追踪数据不是业务数据，保留一段时间即可；不清理的话这张表会无限增长。
     * 清理失败只记日志 —— 它不该影响任何正常功能。
     */
    @Scheduled(cron = "0 30 4 * * *")
    public void cleanup() {
        try {
            LocalDateTime cutoff = Times.nowUtc().minusDays(Math.max(1, retentionDays));
            int removed = traceRepository.deleteByCreatedAtBefore(cutoff);
            if (removed > 0) {
                log.info("清理执行过程记录 {} 条（早于 {}）", removed, cutoff);
            }
        } catch (Exception e) {
            log.warn("清理执行过程记录失败（已忽略）：{}", e.getMessage());
        }
    }

    /** 由字符数估算 token 数（中文约 2 字符 1 token；流式路径拿不到真实 usage）。 */
    public static int estimateTokens(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return Math.max(1, chars / CHARS_PER_TOKEN);
    }

    private static int clampDuration(long ms) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, ms));
    }

    private static String truncate(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String d = detail.strip();
        return d.length() > MAX_DETAIL_LEN ? d.substring(0, MAX_DETAIL_LEN) : d;
    }
}
