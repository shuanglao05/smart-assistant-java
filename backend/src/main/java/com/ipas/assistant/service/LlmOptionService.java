package com.ipas.assistant.service;

import com.ipas.assistant.common.ModelCatalog;
import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.dto.LlmOptionDtos;
import com.ipas.assistant.entity.LlmProvider;
import com.ipas.assistant.repository.LlmProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 构造「可选模型清单」。对应 的 {@code /api/llm-options}
 * 结合 {@code config.llm_options()}。
 *
 * <h2>清单由三部分拼成（顺序不能乱）</h2>
 * <ol>
 * <li><b>本地 Ollama 的全部已安装模型</b> —— 逐个列出，
 * 因为用户可能装了 qwen3:8b 和 qwen3:14b 两个；</li>
 * <li><b>默认云端配置里的模型清单</b> —— 仅当 Key 与 Base URL 都已配置时才列出。
 * 没配置却把模型列出来，用户选了才发现用不了，体验很糟；</li>
 * <li><b>每个已接入 provider 的模型</b> —— 带 {@code providerId}，
 * 前端据此把模型和凭据对应起来。</li>
 * </ol>
 *
 * <p>第 1 步与第 2 步都涉及"本地 Ollama"这一项，必须避免重复：
 * 早期设计在拼第 2 步时显式跳过 {@code provider == "ollama"} 的条目
 * （见 的 {@code for o in config.llm_options(): if o.get("provider") != "ollama"}）。
 * 这里用同样的方式处理 —— 否则下拉里会出现两条一模一样的「本地 · qwen3:8b」。
 */
@Service
public class LlmOptionService {

 private static final Logger log = LoggerFactory.getLogger(LlmOptionService.class);

 private final AppProperties properties;
 private final RuntimeSettingsService settingsService;
 private final LlmProviderRepository providerRepository;
 private final LlmConnectivityService connectivity;

 public LlmOptionService(AppProperties properties,
 RuntimeSettingsService settingsService,
 LlmProviderRepository providerRepository,
 LlmConnectivityService connectivity) {
 this.properties = properties;
 this.settingsService = settingsService;
 this.providerRepository = providerRepository;
 this.connectivity = connectivity;
 }

 /**
 * 构造当前用户的可选模型清单。
 *
 * <p>注意本方法会<b>尝试连接本机 Ollama</b>（5 秒超时）以枚举已安装模型。
 * Ollama 没启动时会静默回落到配置里的单个模型，不会让接口失败 ——
 * 这个接口在前端是高频调用的（打开模型下拉就会调），不能因为
 * 本地服务没开就整个坏掉。
 */
 @Transactional(readOnly = true)
 public List<LlmOptionDtos.LlmOption> buildOptions(Long userId) {
 RuntimeSettingsService.LlmSettings settings = settingsService.load(userId);
 AppProperties.Llm.Ollama ollama = properties.llm().ollama();

 List<LlmOptionDtos.LlmOption> options = new ArrayList<>(localOllamaOptions(ollama, settings));

 // ---- 默认云端配置里的模型（仅在已配置凭据时才列出）----
 boolean cloudConfigured = !settings.apiKey().isBlank() && !settings.baseUrl().isBlank();
 if (cloudConfigured) {
 List<String> models = new ArrayList<>(settings.models());
 // 当前正在用的模型必须出现在清单里（用户可能手动填过清单没包含它）
 if (!settings.model().isBlank() && !models.contains(settings.model())) {
 models.add(0, settings.model());
 }
 String platform = ModelCatalog.classifyPlatform(settings.baseUrl(), "cloud");
 for (String m : models) {
 Integer cw = ModelCatalog.contextWindowOf(m, "cloud", settings.ollamaNumCtx());
 options.add(new LlmOptionDtos.LlmOption(
 "cloud", null, m, "云端 · " + m, true,
 "OpenAI 兼容云端大模型（需配置 Key）",
 platform, cw, ModelCatalog.formatContextWindow(cw)));
 }
 }

 // ---- 各已接入 provider 的模型 ----
 for (LlmProvider p : providerRepository.findByUserIdOrderByIdAsc(userId)) {
 List<String> models = RuntimeSettingsService.parseModelList(p.getModels());
 if (models.isEmpty()) {
 // 清单为空时至少列出默认模型，否则这个 provider 在下拉里会"消失"
 models = List.of(p.getModel() == null ? "" : p.getModel());
 }
 String platform = ModelCatalog.classifyPlatform(p.getBaseUrl(), "cloud");
 for (String m : models) {
 if (m.isBlank()) {
 continue;
 }
 Integer cw = ModelCatalog.contextWindowOf(m, "cloud", settings.ollamaNumCtx());
 options.add(new LlmOptionDtos.LlmOption(
 "cloud", p.getId(), m, p.getName() + " · " + m, true,
 p.getName() + "（已接入）",
 platform, cw, ModelCatalog.formatContextWindow(cw)));
 }
 }

 return options;
 }

 /**
 * 本地 Ollama 的模型项。
 *
 * <p>失败回落策略（与早期设计 {@code _local_ollama_options} 一致）：
 * 调 {@code /api/tags} 失败 → 用配置里的单个 {@code OLLAMA_MODEL} 兜底，
 * 保证下拉里至少有一个可选项。若这里返回空列表，用户会发现「本地模型」
 * 分组整个消失，以为功能坏了。
 */
 private List<LlmOptionDtos.LlmOption> localOllamaOptions(
 AppProperties.Llm.Ollama ollama,
 RuntimeSettingsService.LlmSettings settings) {

 List<String> models;
 try {
 models = connectivity.listOllamaChatModels(ollama.baseUrl(), settings);
 } catch (Exception e) {
 log.debug("列举本地 Ollama 模型失败，回落到配置的默认模型：{}", e.getMessage());
 models = List.of();
 }
 if (models.isEmpty()) {
 models = List.of(ollama.model());
 }

 List<LlmOptionDtos.LlmOption> out = new ArrayList<>();
 for (String m : models) {
 out.add(new LlmOptionDtos.LlmOption(
 "ollama",
 null,
 m,
 "本地 · " + m,
 true,
 "Ollama 本地推理，无需联网",
 "本地",
 settings.ollamaNumCtx(),
 ModelCatalog.formatContextWindow(settings.ollamaNumCtx())));
 }
 return out;
 }
}
