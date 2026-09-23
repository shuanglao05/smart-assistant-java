package com.ipas.assistant.service.rag;

import com.ipas.assistant.entity.KbChunk;
import com.ipas.assistant.repository.KbChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量回填执行器的单元测试。
 *
 * <p>回填是一次性数据迁移，最怕两件事：<b>漏搬</b>（有些行没转过去）和
 * <b>空转死循环</b>（坏数据永远满足查询条件、每轮都被捞出来又不被处理）。
 * 这两个都不是编译期问题，只有断言能守住。
 */
class EmbeddingBackfillRunnerTest {

    private KbChunkRepository chunkRepository;
    private EmbeddingBackfillRunner runner;

    @BeforeEach
    void setUp() {
        chunkRepository = mock(KbChunkRepository.class);
        runner = new EmbeddingBackfillRunner(chunkRepository);
    }

    @Test
    @DisplayName("回填：把可解析的 JSON 向量转成二进制，转完遇到空页即停止")
    void convertsAllRowsThenStops() {
        KbChunk a = jsonOnly(1L, "[1.0,0.0]");
        KbChunk b = jsonOnly(2L, "[0.0,1.0]");
        when(chunkRepository.findByEmbeddingBinIsNullAndEmbeddingIsNotNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a, b)), new PageImpl<>(List.of()));

        int converted = runner.backfill();

        assertEquals(2, converted, "两条都应被转换");
        verify(chunkRepository, times(1)).updateEmbeddingBin(eq(1L), any(byte[].class));
        verify(chunkRepository, times(1)).updateEmbeddingBin(eq(2L), any(byte[].class));
    }

    @Test
    @DisplayName("回填：JSON 损坏时跳过，且不会死循环（查一轮就跳出）")
    void skipsBrokenJsonWithoutLoopingForever() {
        KbChunk broken = jsonOnly(9L, "这不是 JSON");
        // 坏行永远不会被更新，因此查询会一直返回它 —— 必须靠"本轮零转换"跳出
        when(chunkRepository.findByEmbeddingBinIsNullAndEmbeddingIsNotNull(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(broken)));

        int converted = runner.backfill();

        assertEquals(0, converted);
        verify(chunkRepository, never()).updateEmbeddingBin(anyLong(), any(byte[].class));
        verify(chunkRepository, times(1)).findByEmbeddingBinIsNullAndEmbeddingIsNotNull(any(Pageable.class));
    }

    @Test
    @DisplayName("回填：空表时什么都不做")
    void doesNothingOnEmptyTable() {
        when(chunkRepository.findByEmbeddingBinIsNullAndEmbeddingIsNotNull(any(Pageable.class)))
                .thenReturn(Page.empty());

        assertEquals(0, runner.backfill());
        verify(chunkRepository, never()).updateEmbeddingBin(anyLong(), any(byte[].class));
    }

    private static KbChunk jsonOnly(Long id, String json) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setEmbedding(json);
        return c;
    }
}
