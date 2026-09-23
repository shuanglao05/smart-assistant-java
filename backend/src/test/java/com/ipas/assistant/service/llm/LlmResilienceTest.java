package com.ipas.assistant.service.llm;

import com.ipas.assistant.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;

import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型调用韧性保护的单元测试。
 *
 * <p>这组断言守的是"云端抖动时用户是几乎无感、还是直接看到失败"。
 * 三个保护（重试 / 熔断 / 限流）都是<b>分支密集</b>的逻辑：
 * 重试次数算错、熔断阈值判断写成 {@code >} 还是 {@code >=}、许可忘了还 ——
 * 这些都不会在编译期暴露，只会在真实抖动时表现成"偶尔失败"或"跑一会儿就全卡住"。
 * 因此这里把每条分支都钉死。
 *
 * <p>退避时间统一传 0，测试才不会因为 sleep 变慢。
 */
class LlmResilienceTest {

    /** 瞬时故障：消息里带 503，会被判定为可重试。 */
    private static RuntimeException transientError() {
        return new RuntimeException("HTTP 503 Service Unavailable");
    }

    /** 非瞬时故障：参数/鉴权类，重试没有意义。 */
    private static RuntimeException clientError() {
        return new RuntimeException("400 Bad Request：参数不合法");
    }

    private static LlmResilience resilience(int maxAttempts, int threshold, long openMs,
                                           int maxConcurrent, long acquireTimeoutMs) {
        return new LlmResilience(maxAttempts, 0L, threshold, openMs, maxConcurrent, acquireTimeoutMs);
    }

    // ==================================================================
    // 退避重试
    // ==================================================================

    @Test
    @DisplayName("瞬时故障会重试，直到成功（用户完全无感）")
    void transientFailureIsRetriedUntilSuccess() {
        LlmResilience res = resilience(3, 5, 60_000, 4, 1_000);
        AtomicInteger calls = new AtomicInteger();

        String result = res.execute("chat", () -> {
            if (calls.incrementAndGet() < 3) {
                throw transientError();
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, calls.get(), "前两次失败后第三次应成功");
        assertEquals(0, res.consecutiveFailures(), "成功后连续失败计数必须清零");
    }

    @Test
    @DisplayName("非瞬时故障不重试：试一次就抛出（参数错误重试一万次也没用）")
    void clientErrorIsNotRetried() {
        LlmResilience res = resilience(3, 5, 60_000, 4, 1_000);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> res.execute("chat", () -> {
            calls.incrementAndGet();
            throw clientError();
        }));

        assertEquals(1, calls.get(), "非瞬时故障只应尝试一次");
        assertEquals(0, res.consecutiveFailures(), "客户端问题不该计入熔断（否则熔断器会变得神经质）");
    }

    @Test
    @DisplayName("瞬时故障重试耗尽：抛出原异常，并计入一次熔断失败")
    void exhaustedRetriesThrowAndCountFailure() {
        LlmResilience res = resilience(2, 5, 60_000, 4, 1_000);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> res.execute("chat", () -> {
            calls.incrementAndGet();
            throw transientError();
        }));

        assertEquals(2, calls.get(), "maxAttempts=2 应尝试两次");
        assertEquals(1, res.consecutiveFailures(), "重试耗尽应计一次失败");
    }

    // ==================================================================
    // 熔断
    // ==================================================================

    @Test
    @DisplayName("熔断：连续瞬时失败达阈值后打开，之后直接快速失败、不再打扰下游")
    void circuitOpensAfterThresholdAndFailsFast() {
        // 阈值 2、每次只试 1 次，方便计数
        LlmResilience res = resilience(1, 2, 60_000, 4, 1_000);
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            assertThrows(RuntimeException.class, () -> res.execute("chat", () -> {
                calls.incrementAndGet();
                throw transientError();
            }));
        }

        assertTrue(res.isCircuitOpen(), "连续失败 2 次后应打开熔断");
        assertEquals(2, calls.get());

        // 熔断期间：即使这次调用本来会成功，也必须被拦下（不打扰下游）
        ApiException ex = assertThrows(ApiException.class, () -> res.execute("chat", () -> {
            calls.incrementAndGet();
            return "ok";
        }));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals(2, calls.get(), "熔断期间不应真的发起调用");
    }

    @Test
    @DisplayName("熔断：冷却时间过后自动放行（半开）")
    void circuitRecoversAfterCooldown() throws Exception {
        LlmResilience res = resilience(1, 1, 30, 4, 1_000);

        assertThrows(RuntimeException.class, () -> res.execute("chat", () -> {
            throw transientError();
        }));
        assertTrue(res.isCircuitOpen(), "阈值 1：一次失败即熔断");

        Thread.sleep(60);
        assertFalse(res.isCircuitOpen(), "冷却结束后应自动闭合");
        assertEquals("ok", res.execute("chat", () -> "ok"), "闭合后应能正常调用");
    }

    // ==================================================================
    // 限流
    // ==================================================================

    @Test
    @DisplayName("限流：并发许可被占满时快速失败（429），而不是让用户干等")
    void rateLimitFailsFastWhenPermitsExhausted() {
        LlmResilience res = resilience(1, 5, 60_000, 1, 0);

        // wrapStream 在"建流"时即占住许可；不订阅就不会有终止信号 → 许可一直被持有
        Flux<Integer> held = res.wrapStream("chat", () -> Flux.just(1));
        assertEquals(0, res.availablePermits(), "许可应已被占住");

        ApiException ex = assertThrows(ApiException.class, () -> res.execute("chat", () -> "ok"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());

        // 订阅并跑完 → doFinally 释放许可，后续调用恢复
        held.blockLast();
        assertEquals(1, res.availablePermits(), "流结束后必须归还许可（否则跑几次就全卡住）");
    }

    @Test
    @DisplayName("限流：流出错也要归还许可（否则错误几次后再也拿不到许可）")
    void permitIsReleasedWhenStreamFails() {
        LlmResilience res = resilience(1, 5, 60_000, 1, 0);

        Flux<Integer> failing = res.wrapStream("chat", () -> Flux.error(transientError()));
        assertThrows(RuntimeException.class, failing::blockLast);

        assertEquals(1, res.availablePermits(), "出错路径也必须归还许可");
        assertEquals(1, res.consecutiveFailures(), "流的瞬时错误应计入熔断失败");
    }

    @Test
    @DisplayName("限流：建流阶段就抛异常时也要归还许可")
    void permitIsReleasedWhenStreamCreationThrows() {
        LlmResilience res = resilience(1, 5, 60_000, 1, 0);

        assertThrows(RuntimeException.class, () -> res.wrapStream("chat", () -> {
            throw clientError();
        }));

        assertEquals(1, res.availablePermits(), "建流失败也必须归还许可");
    }

    // ==================================================================
    // 故障分类
    // ==================================================================

    @Test
    @DisplayName("故障分类：429/5xx/超时类算瞬时；参数错误不算")
    void transientClassification() {
        assertTrue(LlmResilience.isTransient(new RuntimeException("429 Too Many Requests")));
        assertTrue(LlmResilience.isTransient(new RuntimeException("503 Service Unavailable")));
        assertTrue(LlmResilience.isTransient(new SocketTimeoutException("connect timed out")));
        assertFalse(LlmResilience.isTransient(clientError()));
        assertFalse(LlmResilience.isTransient(new RuntimeException("401 Unauthorized")));
        assertFalse(LlmResilience.isTransient(null));
    }

    @Test
    @DisplayName("故障分类：包装在 cause 链里的瞬时故障也要识别出来")
    void transientClassificationLooksAtCauseChain() {
        RuntimeException wrapped = new RuntimeException("调用失败", transientError());

        assertTrue(LlmResilience.isTransient(wrapped), "内层是 503 时，外层包装也应判为瞬时");
    }
}
