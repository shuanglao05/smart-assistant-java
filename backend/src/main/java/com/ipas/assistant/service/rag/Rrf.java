package com.ipas.assistant.service.rag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）—— 把多路检索结果合成一个排序。
 *
 * <h2>为什么需要融合</h2>
 *
 * <p>单一检索通道各有盲区：<b>向量检索</b>擅长语义（"怎么请年假" ≈ "休假流程"），
 * 但对精确字面不敏感（配置项名、编号、专有名词经常召不回）；<b>关键词检索</b>刚好相反。
 * 两路并行取回后，需要一个办法把它们合成一个排序 —— 这就是 RRF。
 *
 * <h2>为什么用 RRF 而不是"把分数加权平均"</h2>
 *
 * <p>两路的分数量纲完全不同：向量是余弦相似度（-1~1），关键词是 BM25 之类的分值（正数、无上界）。
 * 直接加权平均必须先做归一化，而归一化方式又是个新的"拍脑袋"参数，且会随数据分布漂移。
 *
 * <p>RRF 只使用<b>名次</b>，完全不看原始分数，天然绕开量纲问题：
 * <pre>
 *   score(id) = Σ 1 / (K + rank_i(id) + 1)      // 对每一条"出现过 id 的通道"求和
 * </pre>
 *
 * <p>{@code K} 是平滑常数（原论文取 60）：它的作用是削弱"第一名与第二名"的巨大差距，
 * 让"在多路里都排中上"的结果，能胜过"只在单路里排第一"的结果 —— 这正是融合想要的效果。
 *
 * <h2>附带好处</h2>
 * 没被某一路检回的条目只是"少加了一项"，不会因此被淘汰，因此融合结果是各路的<b>并集</b>，
 * 召回面比任何单路都大。
 */
public final class Rrf {

    /** 平滑常数。60 是 RRF 原始论文给出的经验值，也是多数实现的默认值。 */
    public static final int K = 60;

    private Rrf() {
        // 工具类，不允许实例化
    }

    /**
     * 计算 RRF 分数。
     *
     * @param rankedLists 若干条"有序 id 列表"，每条内部按各自相关性降序；允许为空列表
     * @return id → RRF 分数（出现的通道越多、名次越靠前，分数越高）
     */
    public static Map<Long, Double> fuseScores(List<List<Long>> rankedLists) {
        Map<Long, Double> scores = new HashMap<>();
        if (rankedLists == null) {
            return scores;
        }
        for (List<Long> list : rankedLists) {
            if (list == null) {
                continue;
            }
            for (int rank = 0; rank < list.size(); rank++) {
                Long id = list.get(rank);
                if (id == null) {
                    continue;
                }
                // rank 从 0 开始，公式里用 rank+1 更直观（第 1 名的倒数是 1/(K+1)）
                scores.merge(id, 1.0 / (K + rank + 1), Double::sum);
            }
        }
        return scores;
    }

    /**
     * 融合并按分数降序取前 {@code limit} 个 id。
     *
     * <p>分数相同时按 id 升序排列 —— 排序必须有<b>确定的</b>次序，
     * 否则同一份数据两次检索可能给出不同顺序，排查问题时会非常困惑。
     */
    public static List<Long> fuse(List<List<Long>> rankedLists, int limit) {
        return topByScore(fuseScores(rankedLists), limit);
    }

    /**
     * 按分数降序取前 {@code limit} 个 id。
     *
     * <p>单独暴露出来，是因为调用方常常需要同时拿到"分数表"和"排序后的 id 列表"
     * （例如既要用顺序、又要用分数做门控）。若只提供 {@link #fuse}，调用方就得
     * 自己再写一遍同样的排序规则 —— 两处规则一旦不一致，就会出现莫名其妙的顺序差异。
     *
     * <p>分数相同时按 id 升序排列：排序必须<b>确定</b>，否则同一份数据两次检索
     * 可能给出不同顺序，排查问题时极其困惑。
     */
    public static List<Long> topByScore(Map<Long, Double> scores, int limit) {
        return scores.entrySet().stream()
                .sorted((a, b) -> {
                    int byScore = Double.compare(b.getValue(), a.getValue());
                    return byScore != 0 ? byScore : Long.compare(a.getKey(), b.getKey());
                })
                .limit(Math.max(0, limit))
                .map(Map.Entry::getKey)
                .toList();
    }
}
