package com.ipas.assistant.service.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合的单元测试。
 *
 * <p>融合是混合检索里最"数学"的一段：写错了不会报错，只会让排序悄悄变差（例如
 * 倒数算错、名次从 1 开始而不是 0、或者把分数加权平均当成了 RRF）。
 * 这些都必须靠断言固定下来。
 */
class RrfTest {

    @Test
    @DisplayName("名次越靠前分数越高（第 1 名的倒数大于第 2 名）")
    void higherRankScoresHigher() {
        Map<Long, Double> scores = Rrf.fuseScores(List.of(List.of(10L, 20L)));

        assertTrue(scores.get(10L) > scores.get(20L), "第 1 名应高于第 2 名");
        assertEquals(1.0 / (Rrf.K + 1), scores.get(10L), 1e-12, "第 1 名 = 1/(K+1)");
        assertEquals(1.0 / (Rrf.K + 2), scores.get(20L), 1e-12, "第 2 名 = 1/(K+2)");
    }

    @Test
    @DisplayName("两路都命中的条目分数相加，会超过只在单路排第一的条目")
    void appearingInBothListsWins() {
        // 通道一：A 第一，B 第二；通道二：B 第一（A 不在其中）
        List<List<Long>> lists = List.of(List.of(1L, 2L), List.of(2L));

        Map<Long, Double> scores = Rrf.fuseScores(lists);

        // B = 1/(K+2) + 1/(K+1)；A = 1/(K+1) → B 更高
        assertTrue(scores.get(2L) > scores.get(1L), "两路都命中的应排在前面");
    }

    @Test
    @DisplayName("融合结果是并集：只被一路检回的条目也会保留")
    void unionOfAllLists() {
        List<Long> fused = Rrf.fuse(List.of(List.of(1L), List.of(2L)), 10);

        assertEquals(2, fused.size());
        assertTrue(fused.containsAll(List.of(1L, 2L)));
    }

    @Test
    @DisplayName("按分数降序排好，并截断到 limit")
    void sortedAndLimited() {
        // 通道一给出 1,2,3；通道二给出 3（于是 3 分最高）
        List<Long> fused = Rrf.fuse(List.of(List.of(1L, 2L, 3L), List.of(3L)), 2);

        assertEquals(2, fused.size());
        assertEquals(3L, fused.get(0), "两路都命中的 3 应排第一");
    }

    @Test
    @DisplayName("分数相同时按 id 升序，保证结果确定（不会两次不一样）")
    void tiesAreBrokenDeterministically() {
        // 两条各自只出现在一路、且名次相同 → 分数完全一样
        List<Long> a = Rrf.fuse(List.of(List.of(5L), List.of(3L)), 10);
        List<Long> b = Rrf.fuse(List.of(List.of(5L), List.of(3L)), 10);

        assertEquals(a, b, "同样输入必须得到同样顺序");
        assertEquals(List.of(3L, 5L), a, "同分按 id 升序");
    }

    @Test
    @DisplayName("空输入不抛异常（某一路没结果时很常见）")
    void emptyInputsAreSafe() {
        assertTrue(Rrf.fuseScores(List.of()).isEmpty());
        assertTrue(Rrf.fuse(List.of(List.of()), 5).isEmpty());
        assertTrue(Rrf.fuseScores(null).isEmpty());
    }
}
