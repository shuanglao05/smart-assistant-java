package com.ipas.assistant.service.rag;

import java.util.List;

/**
 * 向量索引端口：把"向量近邻检索"这件事从业务代码里抽出来，换引擎不必改调用方。
 *
 * <h2>为什么只存"向量 + 主键"</h2>
 *
 * <p>本项目采用的是常见做法：外部向量库<b>只存 {@code chunk_id} 与向量</b>，
 * 片段的正文与元数据仍然留在 MySQL 的 {@code kb_chunks}。
 * 检索时先用向量库拿到"最相关的若干 id"，再回 MySQL 取正文。
 *
 * <p>这样做而不是"整张表搬过去"，是因为本项目的<b>关键词通道</b>（MySQL FULLTEXT ngram）、
 * <b>上下文扩窗</b>（按同文件序号找邻居）、<b>证据拼装与来源标注</b>全都读 MySQL 的文本。
 * 文本留在 MySQL，这些逻辑一行都不用改；只把"算相似度"这半边换掉。
 *
 * <h2>与 MySQL 内置检索的关系</h2>
 *
 * <p>MySQL 那条路（加载候选后在内存里算余弦）仍然是<b>默认</b>且<b>必须保留</b>的：
 * 外部向量库是可选加速，不是依赖。所以本端口的所有实现都必须满足
 * "不可用时能干净地退让"（见 {@link #available()}），让调用方回落到 MySQL，
 * 而不是让整个检索挂掉。
 *
 * <h2>一致性定位（重要）</h2>
 *
 * <p>本索引是<b>可重建的派生物</b>，不是真相源：
 * <ul>
 * <li>真相源是 MySQL 的 {@code kb_chunks}（以及最终来源——磁盘上的原始文件）；</li>
 * <li>因此向量库与 MySQL 短时不一致（少几条、多几条）是<b>可接受</b>的，
 * 调用方按 id 回 MySQL 取正文时跳过查不到的即可，不必报错；</li>
 * <li>真出现大面积不一致，正确的处理是<b>全量重建</b>，而不是逐条修。</li>
 * </ul>
 */
public interface VectorIndexPort {

    /**
     * 索引当前是否可用（比如 PG 没启动、表没建好）。
     *
     * <p>调用方拿到 {@code false} 时必须回落到 MySQL 检索 —— 外部依赖缺席不该让功能失效。
     */
    boolean available();

    /**
     * 写入或更新向量（按 {@code chunkId} 幂等 upsert）。
     *
     * <p>幂等很重要：重新索引同一文档时会先删后写，若写入失败需要能安全重试。
     */
    void upsert(List<VectorPoint> points);

    /** 删除某文档的全部向量（重新索引、或删除文档时调用）。 */
    void deleteByFile(Long userId, Long fileId);

    /** 删除某用户的全部向量（清空知识库时调用）。 */
    void deleteByUser(Long userId);

    /** 清空整张索引表（全量重建前调用）。 */
    void clear();

    /** 当前索引里的向量条数（对账用：和 MySQL 的片段数比对，能立刻发现不一致）。 */
    long count(Long userId);

    /**
     * 向量检索：在指定知识库范围内按余弦相似度取 Top-K。
     *
     * @param collectionId 知识库 id；<b>null 表示不限定集合</b>（对应"检索全部库"）
     * @return 按相似度降序的命中（相似度越大越像，取值约 -1~1）
     */
    List<Neighbor> search(Long userId, Long collectionId, float[] query, int topK);

    /**
     * 一条待写入的向量。
     *
     * <p>带上 userId / collectionId / fileId 是刻意的：检索时要按它们过滤，
     * 放在索引里做<strong>库内过滤</strong>远比"先全量取回再在应用里筛"快且省内存。
     * fileId 则是为了"删某个文档时能一次干净删掉"。
     */
    record VectorPoint(long chunkId, Long userId, Long collectionId, Long fileId, float[] vector) {
    }

    /** 一条检索命中：片段 id + 余弦相似度。正文要拿这个 id 回 MySQL 取。 */
    record Neighbor(long chunkId, double similarity) {
    }
}
