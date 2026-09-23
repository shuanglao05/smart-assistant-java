package com.ipas.assistant.service;

import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.entity.AppSetting;
import com.ipas.assistant.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行时配置服务的单元测试（重点是新增的缓存）。
 *
 * <p>缓存这件事最怕两个相反的错：
 * <ul>
 * <li><b>缓存没生效</b> —— 白加一层，性能没改善（靠"查库次数"断言兜住）；</li>
 * <li><b>缓存该失效时没失效</b> —— 用户改了配置却要等 30 秒才生效，
 * 表现是"设置页保存了但没变化"，这类问题极难被用户理解和排查。
 * 所以下面把"写入必须立刻失效"钉成断言。</li>
 * </ul>
 */
class RuntimeSettingsServiceTest {

    private AppSettingRepository repository;
    private RuntimeSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppSettingRepository.class);
        when(repository.findByUserIdAndSettingKeyIn(anyLong(), anyList())).thenReturn(List.of());
        when(repository.findByUserIdAndSettingKey(anyLong(), anyString())).thenReturn(Optional.empty());
        service = new RuntimeSettingsService(repository, defaults());
    }

    /** 用真实 record 而不是 mock：配置默认值参与合并逻辑，用真实对象语义更准。 */
    private static AppProperties defaults() {
        return new AppProperties(
                new AppProperties.Security("k", "HS256", 60),
                "./data",
                new AppProperties.Llm("ollama",
                        new AppProperties.Llm.Ollama("http://localhost:11434", "qwen3:8b", false, 8192),
                        new AppProperties.Llm.Cloud("", "", "", false, 0),
                        "vlm"),
                new AppProperties.Rag("http://localhost:11434/v1", "bge-m3", 500, 80, 4, 32, 30,
                        0.35, 6000, true, 0.35, 1),
                new AppProperties.History(12, 8, 6, 6000, 300, 3000),
                new AppProperties.Upload(50, 500000),
                new AppProperties.Tools("", ""),
                new AppProperties.Cors(List.of()),
                new AppProperties.Chat(true, true));
    }

    @Test
    @DisplayName("缓存生效：连续两次 load 只查库一次")
    void loadHitsCache() {
        RuntimeSettingsService.LlmSettings first = service.load(1L);
        RuntimeSettingsService.LlmSettings second = service.load(1L);

        assertSame(first, second, "命中缓存时应返回同一个快照对象");
        verify(repository, times(1)).findByUserIdAndSettingKeyIn(eq(1L), anyList());
    }

    @Test
    @DisplayName("写入立即失效：save 之后必须重新查库，并且读到新值")
    void saveInvalidatesCache() {
        service.load(1L); // 先填充缓存
        service.save(1L, Map.of(RuntimeSettingsService.KEY_CLOUD_API_KEY, "new-key"));
        when(repository.findByUserIdAndSettingKeyIn(eq(1L), anyList()))
                .thenReturn(List.of(AppSetting.of(1L, RuntimeSettingsService.KEY_CLOUD_API_KEY, "new-key")));

        RuntimeSettingsService.LlmSettings after = service.load(1L);

        assertEquals("new-key", after.apiKey(), "保存后必须立刻读到新值（否则就是'改完不生效'）");
        verify(repository, times(2)).findByUserIdAndSettingKeyIn(eq(1L), anyList());
    }

    @Test
    @DisplayName("写入立即失效：put 走 save，同样会失效")
    void putInvalidatesCache() {
        service.load(1L);
        service.put(1L, RuntimeSettingsService.KEY_OLLAMA_NUM_CTX, "4096");
        when(repository.findByUserIdAndSettingKeyIn(eq(1L), anyList()))
                .thenReturn(List.of(AppSetting.of(1L, RuntimeSettingsService.KEY_OLLAMA_NUM_CTX, "4096")));

        assertEquals(4096, service.load(1L).ollamaNumCtx(), "put 之后也必须立刻生效");
    }

    @Test
    @DisplayName("缓存按用户隔离：两个用户各自缓存，不会互相串")
    void cacheIsPerUser() {
        service.load(1L);
        service.load(2L);

        verify(repository).findByUserIdAndSettingKeyIn(eq(1L), anyList());
        verify(repository).findByUserIdAndSettingKeyIn(eq(2L), anyList());
        verify(repository, times(2)).findByUserIdAndSettingKeyIn(anyLong(), anyList());
    }

    @Test
    @DisplayName("未配置任何项时全部回落到 application.yml 的默认值")
    void fallsBackToDefaults() {
        RuntimeSettingsService.LlmSettings s = service.load(9L);

        // 注意：这里的 model 是【云端】模型名（对应 CLOUD_MODEL），不是本地 Ollama 的模型名 ——
        // 本地模型名由 ChatModelFactory 直接读 app.llm.ollama.model，不经过这个快照。
        // 默认两者都为空，含义是"只用本地 Ollama"。
        assertEquals("", s.apiKey(), "默认没有云端 Key");
        assertEquals("", s.model(), "默认没有云端模型名");
        assertEquals(8192, s.ollamaNumCtx(), "num_ctx 应回落到 yml 默认值");
        assertFalse(s.enableThinking(), "默认不开云端深度思考");
        assertTrue(s.models().isEmpty(), "默认没有云端模型清单");
    }
}
