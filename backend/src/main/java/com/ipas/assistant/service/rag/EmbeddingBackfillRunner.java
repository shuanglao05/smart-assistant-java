package com.ipas.assistant.service.rag;

import com.ipas.assistant.entity.KbChunk;
import com.ipas.assistant.repository.KbChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 一次性把历史片段向量从「JSON 文本」转成「float32 二进制」。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>向量改存二进制后，新写入的数据自然是二进制；但库里可能还留着改版前写入的 JSON 行。
 * 它们仍能被检索到（读取侧做了双读兼容），只是继续以老格式占空间、且每次读取都要做文本解析。
 * 本执行器把这类行就地转换过去，转换完成后双读的"回退分支"就再也不会被走到。
 *
 * <p><b>它是纯搬运，不调用模型</b>：JSON 里的数值原样重排为字节，因此不需要 Ollama，
 * 也不需要联网，几万条也就几秒。
 *
 * <h2>为什么默认关闭、要显式打开</h2>
 *
 * <p>这是一次性的数据迁移动作，不该在每次启动时都去扫全表 —— 那样白白消耗启动时间。
 * 所以用 {@code app.rag.backfill-embeddings=true} 显式开启，跑完一次后关掉即可。
 * 命令行/环境变量写法：{@code RAG_BACKFILL_EMBEDDINGS=true}。
 *
 * <h2>幂等</h2>
 *
 * <p>只处理"二进制为空、JSON 非空"的行；转换过的行不再满足条件。
 * 因此重复启动、中途退出再启动都不会重复搬运。
 */
@Component
@ConditionalOnProperty(name = "app.rag.backfill-embeddings", havingValue = "true")
public class EmbeddingBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillRunner.class);

    /** 每批处理行数：分批是为了让内存占用恒定，而不是"一次性把历史数据全读进来"。 */
    private static final int BATCH_SIZE = 500;

    /** 安全上限：万一出现"永远查得到、又永远改不掉"的诡异情况，也要能停下来。 */
    private static final int MAX_BATCHES = 100_000;

    private final KbChunkRepository chunkRepository;

    public EmbeddingBackfillRunner(KbChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("检测到 app.rag.backfill-embeddings=true，开始把历史 JSON 向量转换为二进制……");
        backfill();
    }

    /**
     * 执行回填，返回成功转换的行数。
     *
     * <p>固定取第 0 页的原因：每处理完一批，这批行的 {@code embedding_bin} 已非空，
     * 不再满足查询条件，于是"第 0 页"自然向后推进。这样就不需要维护 offset
     * （维护 offset 反而容易因为行在变动而跳过数据）。
     */
    int backfill() {
        int converted = 0;
        int skipped = 0;
        for (int round = 0; round < MAX_BATCHES; round++) {
            Page<KbChunk> page = chunkRepository.findByEmbeddingBinIsNullAndEmbeddingIsNotNull(
                    PageRequest.of(0, BATCH_SIZE));
            if (page.isEmpty()) {
                break;
            }

            int convertedThisRound = 0;
            for (KbChunk c : page.getContent()) {
                float[] vec = Vectors.fromJson(c.getEmbedding());
                if (vec == null) {
                    // JSON 损坏的行转不了：跳过并计数。它们仍会满足查询条件，
                    // 所以下面用"本轮一条都没转成"来判定并跳出，避免死循环。
                    skipped++;
                    continue;
                }
                chunkRepository.updateEmbeddingBin(c.getId(), Vectors.toBytes(vec));
                converted++;
                convertedThisRound++;
            }

            if (convertedThisRound == 0) {
                log.warn("本轮没有可转换的片段（可能有 {} 条 JSON 已损坏），停止回填以免空转", skipped);
                break;
            }
            log.info("向量回填进行中：已转换 {} 条，跳过 {} 条", converted, skipped);
        }
        log.info("向量回填结束：转换 {} 条，跳过 {} 条", converted, skipped);
        return converted;
    }
}
