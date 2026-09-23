package com.ipas.assistant.service.llm;

import com.ipas.assistant.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 大模型调用的韧性保护：<b>限流 + 熔断 + 退避重试</b>。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>优化之前，模型调用只有"超时"这一道防线：云端返回 429（限流）或 5xx（服务抖动）时，
 * 请求直接失败、用户看到一句含糊的错误；而如果云端持续不可用，每个请求都会老老实实等满超时，
 * 把线程与连接池一起拖住 —— 这就是典型的"雪崩"。
 *
 * <h2>三个保护各解决什么</h2>
 * <ol>
 * <li><b>退避重试</b>：瞬时故障（429 / 5xx / 连接超时）多半下一次就好了，
 * 指数退避重试能把它吞掉，用户完全无感。</li>
 * <li><b>熔断</b>：连续失败到一定次数后，<b>直接快速失败</b>一段时间，
 * 不再让每个请求都去等超时 —— 既保护下游，也不让用户干等。</li>
 * <li><b>限流</b>：限制同时进行的模型调用数。本地 Ollama 尤其怕并发：
 * 同时来 10 个请求时每个都会变慢，最后一起超时；限流后是"排队变慢"而不是"一起失败"。</li>
 * </ol>
 *
 * <h2>两个刻意的设计决定</h2>
 *
 * <p><b>① 只有"瞬时故障"才计入熔断。</b>参数错误（400）、鉴权失败（401）这类是<b>客户端问题</b>，
 * 再试一万次也一样，把它们计入熔断只会让熔断器变得神经质；而且那类错误本该立刻返回给用户。
 *
 * <p><b>② 流式调用不重试。</b>流已经吐了一部分 token 再重试，会在界面拼出"两段回答"。
 * 所以流式路径只做限流与熔断判断，不做重试（要重试请用户点"重新生成"）。
 *
 * <p>本类刻意做成<b>纯逻辑组件</b>（不依赖任何模型 SDK、可注入假操作），
 * 于是重试次数、熔断开合、限流排队这些行为都能被单元测试穷举验证 ——
 * 若写死在模型调用处，这些分支就只能靠"等云端真的抖动"来碰运气了。
 */
@Component
public class LlmResilience {

    private static final Logger log = LoggerFactory.getLogger(LlmResilience.class);

    /** 最多尝试几次（含首次）。1 = 不重试。 */
    private final int maxAttempts;
    /** 退避基数：第 n 次失败后等待 base * 2^(n-1) 毫秒。 */
    private final long backoffMs;
    /** 连续多少次瞬时失败后打开熔断。 */
    private final int circuitThreshold;
    /** 熔断打开后保持多久才允许再试（半开放行）。 */
    private final long circuitOpenMs;
    /** 取"并发许可"的最长等待时间；超时说明并发已满，快速失败比让用户干等更好。 */
    private final long acquireTimeoutMs;

    /** 并发许可。用信号量而不是计数器：阻塞等待 + 超时退出都由它负责。 */
    private final Semaphore permits;

    /** 连续瞬时失败次数（成功即清零）。 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 熔断打开到什么时候（毫秒时间戳）；小于等于当前时间表示"闭合"。 */
    private volatile long circuitOpenUntilMs = 0L;

    public LlmResilience(
            @Value("${app.llm.resilience.max-attempts:3}") int maxAttempts,
            @Value("${app.llm.resilience.backoff-ms:500}") long backoffMs,
            @Value("${app.llm.resilience.circuit-threshold:5}") int circuitThreshold,
            @Value("${app.llm.resilience.circuit-open-ms:30000}") long circuitOpenMs,
            @Value("${app.llm.resilience.max-concurrent:4}") int maxConcurrent,
            @Value("${app.llm.resilience.acquire-timeout-ms:10000}") long acquireTimeoutMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMs = Math.max(0, backoffMs);
        this.circuitThreshold = Math.max(1, circuitThreshold);
        this.circuitOpenMs = Math.max(0, circuitOpenMs);
        this.acquireTimeoutMs = Math.max(0, acquireTimeoutMs);
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
    }

    // ==================================================================
    // 同步调用：限流 + 熔断 + 退避重试
    // ==================================================================

    /**
     * 执行一次同步模型调用，带完整保护。
     *
     * @param label  用于日志的标识（如 {@code chat} / {@code query-rewrite}）
     * @param action 真正要做的调用
     * @throws ApiException 503（熔断中）/ 429（并发已满），或原始异常（重试耗尽 / 非瞬时故障）
     */
    public <T> T execute(String label, Supplier<T> action) {
        ensureCircuitClosed(label);
        acquirePermit(label);
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    T value = action.get();
                    recordSuccess();
                    return value;
                } catch (RuntimeException e) {
                    boolean transientFailure = isTransient(e);
                    if (!transientFailure) {
                        // 客户端问题（参数/鉴权）：立刻抛出，不计入熔断
                        throw e;
                    }
                    if (attempt == maxAttempts) {
                        // 瞬时故障重试到头：计入熔断，然后把真实原因交给调用方
                        recordFailure(label, e);
                        throw e;
                    }
                    log.warn("{} 调用失败（第 {}/{} 次，将退避重试）：{}",
                            label, attempt, maxAttempts, e.getMessage());
                    sleepBackoff(attempt);
                }
            }
            // 循环必然在上面的分支里 return 或 throw，这里只是让编译器满意
            throw new IllegalStateException("unreachable");
        } finally {
            permits.release();
        }
    }

    // ==================================================================
    // 流式调用：限流 + 熔断判断（不重试）
    // ==================================================================

    /**
     * 包装一个流式调用：订阅前做熔断与限流检查，流结束时释放许可。
     *
     * <p><b>刻意不重试</b>：流已经吐出一部分内容再重来，界面上会拼出两段回答。
     * 用户若需要，点"重新生成"即可 —— 那是显式意图，比系统偷偷重试更可控。
     *
     * @param label   用于日志的标识
     * @param factory 真正要做的流式调用（延迟到检查通过后才执行）
     */
    public <T> Flux<T> wrapStream(String label, Supplier<Flux<T>> factory) {
        ensureCircuitClosed(label);
        acquirePermit(label);

        Flux<T> flux;
        try {
            flux = factory.get();
        } catch (RuntimeException e) {
            // 建流阶段就失败：立刻还回许可，避免许可泄漏（泄漏几次之后就再也拿不到许可了）
            permits.release();
            if (isTransient(e)) {
                recordFailure(label, e);
            }
            throw e;
        }

        return flux
                .doOnComplete(this::recordSuccess)
                .doOnError(e -> {
                    if (isTransient(e)) {
                        recordFailure(label, e);
                    }
                })
                // doFinally 在所有终止信号（完成/出错/取消）上<b>恰好触发一次</b>，
                // 因此它是"还许可"最安全的位置：用 doOnComplete/doOnError 会漏掉"用户点了停止"的取消路径
                .doFinally(signal -> permits.release());
    }

    // ==================================================================
    // 熔断状态
    // ==================================================================

    /** 熔断是否处于打开状态（此时应快速失败，不去打扰下游）。 */
    public boolean isCircuitOpen() {
        return System.currentTimeMillis() < circuitOpenUntilMs;
    }

    /** 当前连续瞬时失败次数（测试与观测用）。 */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** 当前可用许可数（测试与观测用）。 */
    public int availablePermits() {
        return permits.availablePermits();
    }

    private void ensureCircuitClosed(String label) {
        long until = circuitOpenUntilMs;
        if (System.currentTimeMillis() < until) {
            long remainMs = until - System.currentTimeMillis();
            log.warn("{} 调用被熔断拦截（还需 {}ms 冷却）", label, remainMs);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "模型服务连续失败，已暂时熔断，请稍后重试");
        }
    }

    private void acquirePermit(String label) {
        try {
            if (!permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("{} 调用等待并发许可超时（{}ms），快速失败", label, acquireTimeoutMs);
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                        "当前请求过多（模型调用排队已满），请稍后重试");
            }
        } catch (InterruptedException e) {
            // 恢复中断标志：吞掉中断会让上层线程池无法优雅停机
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "请求被中断，请重试");
        }
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
    }

    private void recordFailure(String label, Throwable e) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= circuitThreshold) {
            circuitOpenUntilMs = System.currentTimeMillis() + circuitOpenMs;
            // 打开后清零：冷却期结束若再失败，需要重新累积到阈值才再次熔断
            consecutiveFailures.set(0);
            log.error("{} 连续失败 {} 次，熔断打开 {}ms（原因：{}）",
                    label, failures, circuitOpenMs, e.getMessage());
        }
    }

    private void sleepBackoff(int attempt) {
        // 指数退避：第 1 次失败等 base，第 2 次等 2*base …… 避免在服务刚抖动时继续加压
        long wait = backoffMs * (1L << (attempt - 1));
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================================================================
    // 故障分类
    // ==================================================================

    /**
     * 判断一个异常是否为"瞬时故障"（值得重试、且应计入熔断）。
     *
     * <p>判定顺序（沿 cause 链回看）：
     * <ol>
     * <li>命中 Spring AI 的 {@code NonTransientAiException} → <b>否</b>（框架已明确说是永久性错误）；</li>
     * <li>命中 Spring AI 的 {@code TransientAiException} → <b>是</b>；</li>
     * <li>是连接/超时类异常（{@code ConnectException} / {@code SocketTimeoutException}）→ <b>是</b>；</li>
     * <li>异常信息里出现 429 / 5xx / timeout 等字样 → <b>是</b>。</li>
     * </ol>
     *
     * <p>用"类名 + 关键字"而不是只判类型，是因为不同云端 SDK 抛出的异常层次并不统一，
     * 只看类型会漏判（比如被包装成 RestClientException 的 503）。
     */
    static boolean isTransient(Throwable e) {
        if (e == null) {
            return false;
        }
        Throwable cur = e;
        boolean sawTimeoutOrConnect = false;
        boolean sawTransientFlag = false;
        while (cur != null) {
            String name = cur.getClass().getName();
            if (name.endsWith("NonTransientAiException")) {
                return false;
            }
            if (name.endsWith("TransientAiException")) {
                sawTransientFlag = true;
            }
            if (cur instanceof SocketTimeoutException || cur instanceof ConnectException
                    || cur instanceof IOException && contains(cur.getMessage(), "timed out")) {
                sawTimeoutOrConnect = true;
            }
            if (matchesTransientPattern(cur.getMessage())) {
                return true;
            }
            cur = cur.getCause();
        }
        return sawTransientFlag || sawTimeoutOrConnect;
    }

    /** 429 / 5xx / 超时 / 连接被重置等字样 → 视为瞬时故障。 */
    private static boolean matchesTransientPattern(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase();
        return m.contains("429")
                || m.contains("500")
                || m.contains("502")
                || m.contains("503")
                || m.contains("504")
                || m.contains("timeout")
                || m.contains("timed out")
                || m.contains("connection reset")
                || m.contains("temporarily unavailable")
                || m.contains("rate limit")
                || m.contains("too many requests");
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.toLowerCase().contains(needle);
    }
}
