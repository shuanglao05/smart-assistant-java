package com.ipas.assistant.service.rag;

import com.ipas.assistant.entity.KbChunk;
import com.ipas.assistant.repository.KbChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 MySQL 里已有的片段向量<b>一次性回填</b>到 pgvector。
 *
 * <h2>为什么要"搬"而不是"重新嵌入"</h2>
 *
 * <p>向量已经在 MySQL 的 {@code kb_chunks.embedding_bin} 里了，直接读出来写过去即可 ——
 * 秒级完成。若改成"重新调 embedding 模型算一遍"，不仅慢，还可能因为模型版本变化
 * 得到与原库不一致的向量（同一份语料两套向量，检索结果会莫名其妙地漂移）。
 *
 * <h2>怎么触发</h2>
 *
 * <p>默认<b>不执行</b>。设环境变量 {@code RAG_BACKFILL_VECTORS=true} 启动一次即可：
 * <pre>
 * set VECTOR_STORE=pgvector
 * set PGVECTOR_PASSWORD=...
 * set RAG_BACKFILL_VECTORS=true
 * </pre>
 * 跑完把它去掉（虽然是幂等的 upsert，重复跑没坏处，但没必要每次启动都全表扫一遍）。
 *
 * <h2>分批处理</h2>
 *
 * <p>按 500 条分页推进。一次性把 3,455 条（每条 4KB 向量）读进内存不算大，
 * 但接口设计上要能撑住"以后涨到十万级"，所以从一开始就分页。
 */
@Component
@ConditionalOnProperty(name = "app.rag.vector-store", havingValue = "pgvector")
public class PgVectorBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PgVectorBackfillRunner.class);

    /** 每批处理的片段数。 */
    private static final int BATCH_SIZE = 500;

    private final VectorIndexPort vectorIndex;
    private final KbChunkRepository chunkRepository;
    private final boolean enabled;

    public PgVectorBackfillRunner(VectorIndexPort vectorIndex,
                                  KbChunkRepository chunkRepository,
                                  @Value("${app.rag.pgvector.backfill:false}") boolean enabled) {
        this.vectorIndex = vectorIndex;
        this.chunkRepository = chunkRepository;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (!vectorIndex.available()) {
            log.warn("pgvector 不可用，跳过向量回填（MySQL 数据不受影响）");
            return;
        }

        log.info("开始回填向量：MySQL → pgvector");
        long scanned = 0;
        long written = 0;
        long skipped = 0; // 只有旧版 JSON 向量、还没有二进制向量的历史片段

        for (int page = 0; ; page++) {
            Page<KbChunk> batch = chunkRepository.findAll(PageRequest.of(page, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            List<VectorIndexPort.VectorPoint> points = new ArrayList<>(batch.getNumberOfElements());
            for (KbChunk c : batch.getContent()) {
                float[] vec = c.getEmbeddingBin() == null ? null : Vectors.fromBytes(c.getEmbeddingBin());
                if (vec == null) {
                    skipped++;
                    continue;
                }
                points.add(new VectorIndexPort.VectorPoint(
                        c.getId(), c.getUserId(), c.getCollectionId(), c.getFileId(), vec));
            }
            vectorIndex.upsert(points);
            scanned += batch.getNumberOfElements();
            written += points.size();
            log.info("回填进度：已扫描 {} 条，已写入 {} 条", scanned, written);

            if (!batch.hasNext()) {
                break;
            }
        }

        log.info("向量回填完成：扫描 {} 条，写入 {} 条，跳过 {} 条（无二进制向量）",
                scanned, written, skipped);
        if (skipped > 0) {
            log.warn("有 {} 条片段只有旧版 JSON 向量，未回填。"
                    + "先用 RAG_BACKFILL_EMBEDDINGS=true 把它们升级为二进制向量，再重跑本回填即可。", skipped);
        }
    }
}
