package com.ipas.assistant.service;

import com.ipas.assistant.common.FileIndexRequested;
import com.ipas.assistant.common.IndexStatus;
import com.ipas.assistant.entity.FileItem;
import com.ipas.assistant.repository.FileItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 文档索引的后台执行器。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>同步索引会让上传接口阻塞几十秒到几分钟（大文档要反复调用嵌入服务），
 * 必然触发浏览器 / 网关超时。所以上传接口落库后<b>立刻返回</b>，
 * 真正的索引交到这里在后台线程跑。
 *
 * <h2>三个注解各干什么</h2>
 *
 * <ul>
 * <li>{@code @TransactionalEventListener(AFTER_COMMIT)} —— <b>等上传事务提交后</b>才执行。
 * 这一步是正确性的关键：如果直接监听普通事件，线程可能在文件记录还没提交时就去查它，
 * 结果查不到、或索引写完又被回滚。交给框架保证"已提交"最稳。</li>
 * <li>{@code @Async("indexExecutor")} —— 在专用线程池里跑，不占用 HTTP 请求线程，
 * 也不阻塞上传接口的返回。</li>
 * <li>两者叠加即可实现"提交后异步执行"，无需手写线程与事务判断。</li>
 * </ul>
 *
 * <h2>失败策略</h2>
 *
 * <p>任何异常都被拦下并写进 {@code files.index_error}，<b>绝不向上抛</b> ——
 * 这是后台线程，抛出去也没人接，只会污染日志。用户看到 FAILED 与原因后可点重试。
 */
@Component
public class KnowledgeIndexWorker {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexWorker.class);

    /** index_error 列长度是 500，留出余量避免超长写入把状态更新本身搞失败。 */
    private static final int MAX_ERROR_LEN = 450;

    private final FileItemRepository fileRepository;
    private final KbService kbService;

    public KnowledgeIndexWorker(FileItemRepository fileRepository, KbService kbService) {
        this.fileRepository = fileRepository;
        this.kbService = kbService;
    }

    /**
     * 处理"请求建立索引"事件：回查文件 → 向量化落库 → 回写状态。
     *
     * <p>数据来源说明：这里用的是文件记录里的 {@code content}（上传那一刻抽取并落库的正文）。
     * 对"上传即索引"这条路是成立的 —— content 就是刚从上传字节里抽出来的，最新鲜。
     * （"重建索引"是另一条路：它要求从磁盘重新抽取，因此不走本执行器，见 {@code FileService.reindex}。）
     */
    @Async("indexExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIndexRequested(FileIndexRequested req) {
        Long userId = req.userId();
        Long fileId = req.fileId();
        try {
            // 回查文件：确认它还在。用户可能在排队期间就把文件删了 —— 那就什么都不做，
            // 否则会写出一批"指向已删除文件"的孤儿片段，污染检索结果。
            FileItem f = fileRepository.findByIdAndUserId(fileId, userId).orElse(null);
            if (f == null) {
                log.info("跳过异步索引：文件 {} 已不存在（可能在排队期间被删除）", fileId);
                return;
            }

            // 没有可索引正文（例如扫描版 PDF 抽不出文字）→ 直接标 SKIPPED，
            // 不让它永远停在 PENDING 让用户误以为"还在跑"。
            String content = f.getContent();
            if (content == null || content.isBlank()) {
                fileRepository.updateIndexStatus(userId, fileId, IndexStatus.SKIPPED, null, 0);
                log.info("异步索引跳过：文件 {} 无可切分正文", fileId);
                return;
            }

            fileRepository.updateIndexStatus(userId, fileId, IndexStatus.INDEXING, null, 0);

            int n = kbService.indexFile(userId, fileId, content, f.getCollectionId());
            if (n == 0) {
                // 切不出片段（内容太短等）也不算失败，标 SKIPPED 更贴合事实
                fileRepository.updateIndexStatus(userId, fileId, IndexStatus.SKIPPED, null, 0);
                log.info("异步索引完成但无片段：文件 {}", fileId);
                return;
            }

            fileRepository.updateIndexStatus(userId, fileId, IndexStatus.READY, null, n);
            log.info("异步索引完成：文件 {}，共 {} 个片段", fileId, n);
        } catch (Exception e) {
            // 失败必须落到字段上，让前端能显示"失败 + 原因 + 重试"，
            // 而不是只在日志里一闪而过（用户看不到后台线程的日志）。
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.length() > MAX_ERROR_LEN) {
                msg = msg.substring(0, MAX_ERROR_LEN);
            }
            try {
                fileRepository.updateIndexStatus(userId, fileId, IndexStatus.FAILED, msg, 0);
            } catch (Exception ignore) {
                // 连状态都写不进去（例如数据库也断了）时，只能记日志；
                // 绝不能因为"记录失败状态失败"再抛异常出来。
                log.warn("回写索引失败状态时出错：文件 {}", fileId);
            }
            log.warn("异步索引失败：文件 {}，原因：{}", fileId, msg);
        }
    }
}
