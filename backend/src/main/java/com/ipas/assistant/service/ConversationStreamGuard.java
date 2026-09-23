package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话级"生成中"互斥闸门：同一会话同时只允许一条流式生成。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>流式聊天里有几处状态是<b>每会话一份</b>的：后端记忆按 {@code thread_id = 会话id} 隔离、
 * 来源收集器按会话 id 索引。同一个会话如果有两条流并行，它们会互相踩踏：
 * <ul>
 * <li>两条回答交替写入消息表 → 界面上出现"你一句我一句"的两个气泡；</li>
 * <li>来源收集器被后一条流覆盖 → 引用来源标注串台；</li>
 * <li>"重新生成"会删掉 id 更大的助手消息 → 可能删掉正在生成的那条。</li>
 * </ul>
 *
 * <p>触发条件很日常：用户双击发送、或前端请求超时后自动重试。
 *
 * <h2>为什么单独抽成一个组件</h2>
 *
 * <p>放回聊天服务里也能跑，但那会让这段<b>纯逻辑</b>无法被单元测试覆盖 ——
 * 聊天服务依赖真实大模型与数据库，被排除在自动化测试之外。抽出来之后，
 * 互斥语义（抢占、释放、超时兜底）都能直接单测，不依赖 Spring 容器。
 *
 * <h2>两道保险</h2>
 * <ol>
 * <li><b>抢占</b>：获取时若发现旧占位已超过 {@link #LEASE_TTL_MS}，直接接管 ——
 * 宁可放行，也不能把会话永久锁死；</li>
 * <li><b>清扫</b>：外部定时调用 {@link #sweep()}，把超时占位定期清掉。</li>
 * </ol>
 */
@Component
public class ConversationStreamGuard {

    private static final Logger log = LoggerFactory.getLogger(ConversationStreamGuard.class);

    /**
     * 占位的最长有效时长：超过即视为"那条流已异常终止（收尾回调未触发）"。
     *
     * <p>必须<b>大于</b>SSE 连接超时（聊天侧是 10 分钟），否则一个正在正常生成的长回答
     * 会被误判为泄漏、被放行第二条流，反而制造出并发。故取 15 分钟。
     */
    static final long LEASE_TTL_MS = 15 * 60 * 1000L;

    /** 会话 id → 占位开始时间（毫秒）。用并发 Map：占位与释放发生在不同线程。 */
    private final ConcurrentMap<Long, Long> running = new ConcurrentHashMap<>();

    /**
     * 获取某会话的生成名额；已被占用则抛 409。
     *
     * @param conversationId 会话 id；为 null 时直接放行（防御性：正常到不了这里，
     *                       会话 id 由请求体上的必填校验保证）
     * @throws ApiException 409，当该会话已有回答正在生成且占位未超时
     */
    public void acquire(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        if (!tryAcquire(conversationId, System.currentTimeMillis())) {
            log.warn("会话 {} 已有回答正在生成，拒绝并发请求", conversationId);
            throw ApiException.conflict("该会话正在生成回答，请稍候或先点击停止");
        }
    }

    /**
     * 尝试获取占位（原子、可测试）。
     *
     * <p>用 {@code putIfAbsent} 而非"先查再写"：后者在并发下两个线程可能都查到"没人占用"
     * 而双双通过，起不到互斥作用；{@code putIfAbsent} 是原子的，天然只有一个赢家。
     *
     * @param nowMs 当前时间（显式传入，便于测试构造"超时"场景）
     * @return true = 抢到；false = 已被占用且未超时
     */
    boolean tryAcquire(Long conversationId, long nowMs) {
        Long prev = running.putIfAbsent(conversationId, nowMs);
        if (prev == null) {
            return true;
        }
        if (nowMs - prev > LEASE_TTL_MS) {
            // 旧占位已超时（收尾回调没触发导致泄漏）→ 接管。宁可放行也不锁死会话。
            running.put(conversationId, nowMs);
            log.warn("会话 {} 的旧生成占位已超时（{}ms），强制接管", conversationId, nowMs - prev);
            return true;
        }
        return false;
    }

    /**
     * 释放占位。收尾的三条路径（正常结束 / 异常 / 断连或超时）都会调用。
     *
     * <p>{@code remove} 本身幂等，多条路径可能同时触发，不需要额外判重。
     */
    public void release(Long conversationId) {
        if (conversationId != null) {
            running.remove(conversationId);
        }
    }

    /** 定时清扫超时占位（由外部定时任务调用），返回清掉的条数。 */
    public int sweep() {
        return sweep(System.currentTimeMillis());
    }

    /** 清扫实现（显式传入时间，便于测试）。 */
    int sweep(long nowMs) {
        int before = running.size();
        running.entrySet().removeIf(e -> nowMs - e.getValue() > LEASE_TTL_MS);
        return before - running.size();
    }

    /** 当前被占用的会话数（供定时任务日志与测试断言使用）。 */
    int runningCount() {
        return running.size();
    }
}
