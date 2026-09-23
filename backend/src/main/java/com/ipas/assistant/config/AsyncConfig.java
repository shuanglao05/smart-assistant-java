package com.ipas.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务配置。
 *
 * <h2>{@code @EnableAsync} 的作用</h2>
 *
 * <p>打开 Spring 的 {@code @Async} 支持，让标注了 {@code @Async} 的方法在独立线程里执行。
 * 目前只有一处用到：文档索引（见 {@code KnowledgeIndexWorker}）。
 *
 * <h2>为什么要自定义线程池，而不用默认的</h2>
 *
 * <p>不指定执行器时，Spring 会退化成一个 {@code SimpleAsyncTaskExecutor} ——
 * 它<b>每来一个任务就新建一个线程、且不复用</b>。对索引这种"每次几十秒"的长任务，
 * 用户连续上传几个文件就会瞬间冒出十几个线程，把机器压垮。
 *
 * <h2>为什么核心线程数只有 2</h2>
 *
 * <p>索引的瓶颈是<b>嵌入服务（Ollama）</b>，不是本机 CPU：一次嵌入调用要等模型算完，
 * 并发开大并不会让总吞吐变高，反而会让每个请求都变慢、还容易把本地模型拖到超时。
 * 所以这里刻意保守：同时最多跑 2 个，排队 100 个足够（超出的会被拒绝，
 * 触发拒绝策略报错，让用户看到"队列已满"而不是无限堆积）。
 *
 * <p>队列容量选 100：索引任务本身会落库记录状态（PENDING/FAILED），
 * 即便排队失败用户也能看到并重试，不需要靠一个超大队列来"兜底"。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 文档索引用线程池。
     *
     * <p>线程名统一带 {@code kb-index-} 前缀，日志里一眼就能看出"这是后台索引线程"
     * （与处理 HTTP 请求的 {@code http-nio-*} 线程区分开），排查问题时非常关键。
     */
    @Bean("indexExecutor")
    public ThreadPoolTaskExecutor indexExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);              // 常驻 2 个：嵌入服务是瓶颈，再多人也是排队
        ex.setMaxPoolSize(4);               // 突发时最多扩到 4 个
        ex.setQueueCapacity(100);           // 排队上限；再满则由调用线程执行（见下）
        ex.setThreadNamePrefix("kb-index-"); // 日志可辨识
        // 关闭时等待在跑的任务结束（最多 30 秒）：避免"应用停了、索引写了一半"
        // —— 那种情况下 files.index_status 会永远停在 INDEXING，用户以为卡死。
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }
}
