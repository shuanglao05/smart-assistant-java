package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.common.ModelCatalog;
import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.dto.LlmConfigDtos;
import com.ipas.assistant.dto.LlmOptionDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端大模型配置的业务逻辑。对应 。
 *
 * <h2>与 早期实现最重要的一处结构差异（"改"的核心）</h2>
 *
 * <p>早期设计 {@code configure_cloud()} 的流程是：
 * <pre>
 * ① 记下旧值（old）
 * ② 直接改 config 模块的全局变量（立即生效）
 * ③ 用新值测连；失败则把全局变量回滚成 old，返回 400
 * ④ 成功则把 8 个配置项写回 backend/.env
 * </pre>
 * 「先改内存、失败再回滚」是被迫的 —— 因为要让配置<b>立即生效</b>，
 * 而它的生效机制就是读那些全局变量。
 *
 * <p>本实现不需要这套回滚机制：
 * <pre>
 * ① 算出"假如保存会是什么值"
 * ② 用这组值测连；失败直接 400（<b>什么都没写，无需回滚</b>）
 * ③ 成功才落库
 * </pre>
 * 少了一份状态、少了一条"回滚时漏还原某个变量"的隐患路径。
 * <b>副作用是等价的</b>：失败时数据库里仍是旧配置，下次请求读到的还是旧值。
 *
 * <p>另一个差异：写完配置<b>不需要再手动重置 HTTP 连接池</b>。
 * 早期设计要调 {@code reset_http_client()} 因为代理是绑在 client 上的；
 * 本项目的 {@link CloudHttpGateway} 每次取用时会自己比对代理配置并重建
 * （见该类注释），所以这一步被自动覆盖了。
 */
@Service
public class LlmConfigService {

 private static final Logger log = LoggerFactory.getLogger(LlmConfigService.class);

 /** Base URL 留空且从未配置过时的回落地址（原 {@code DEFAULT_OPENAI_BASE_URL}）。 */
 static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";

 private final AppProperties properties;
 private final RuntimeSettingsService settingsService;
 private final LlmConnectivityService connectivity;
 private final LlmOptionService optionService;
 private final ProxyDetector proxyDetector;
 private final CloudHttpGateway http;

 public LlmConfigService(AppProperties properties,
 RuntimeSettingsService settingsService,
 LlmConnectivityService connectivity,
 LlmOptionService optionService,
 ProxyDetector proxyDetector,
 CloudHttpGateway http) {
 this.properties = properties;
 this.settingsService = settingsService;
 this.connectivity = connectivity;
 this.optionService = optionService;
 this.proxyDetector = proxyDetector;
 this.http = http;
 }

 /**
 * 当前云端配置（不含 Key）。对应 {@code GET /api/llm-config}。
 *
 * <p>回传 {@code envProxy} 与 {@code effectiveProxy} 是为了让用户在设置页能看清
 * "环境变量里是什么、实际用的是哪个" —— 这两个值经常不一致
 * （环境变量是进程启动时继承的，改系统代理不会更新），
 * 不展示出来就没法解释"我明明配了代理怎么没生效"。
 */
 @Transactional(readOnly = true)
 public LlmConfigDtos.ConfigResponse getConfig(Long userId) {
 RuntimeSettingsService.LlmSettings s = settingsService.load(userId);
 ModelCatalog.ThinkingInfo thinking =
 ModelCatalog.classifyThinking(s.baseUrl(), s.model());

 return new LlmConfigDtos.ConfigResponse(
 s.baseUrl() == null ? "" : s.baseUrl(),
 s.model(),
 s.models(),
 s.enableThinking(),
 s.thinkingBudget(),
 thinking.supported(),
 thinking.hint(),
 s.trustEnv(),
 s.proxyUrl(),
 http.envProxy(),
 http.effectiveProxy(s));
 }

 /**
 * 保存云端配置：<b>先测连、通过才落库</b>。对应 {@code configure_cloud()}。
 *
 * <p>返回更新后的完整模型清单，让前端保存后立刻刷新下拉框
 * （早期设计也是返回 {@code config.llm_options()}）。
 */
 @Transactional
 public List<LlmOptionDtos.LlmOption> saveConfig(Long userId,
 LlmConfigDtos.ConfigRequest payload) {
 RuntimeSettingsService.LlmSettings current = settingsService.load(userId);

 // ---- 1) 算出"假如保存会是什么值"（不落库）----
 String apiKey = notBlank(payload.cloudApiKey())
 ? payload.cloudApiKey().trim()
 : current.apiKey();
 if (apiKey == null || apiKey.isBlank()) {
 throw ApiException.badRequest("API Key 不能为空（请先填写平台申请的 Key）");
 }

 // Base URL：填了就用；留空则"保持当前不变"；当前也没配过才回落 OpenAI 官方
 String baseUrl;
 if (notBlank(payload.cloudBaseUrl())) {
 baseUrl = payload.cloudBaseUrl().trim();
 } else if (notBlank(current.baseUrl())) {
 baseUrl = current.baseUrl();
 } else {
 baseUrl = DEFAULT_OPENAI_BASE_URL;
 }

 String model = notBlank(payload.cloudModel())
 ? payload.cloudModel().trim()
 : current.model();

 List<String> models = current.models();
 if (payload.cloudModels() != null) {
 List<String> cleaned = RuntimeSettingsService.parseModelList(
 RuntimeSettingsService.joinModelList(payload.cloudModels()));
 // 空数组视为"不改"（与早期设计 `if models:` 的判断一致）
 if (!cleaned.isEmpty()) {
 models = cleaned;
 }
 }

 // ---- 2) 真实测连：失败直接 400，什么都没写 ----
 try {
 connectivity.testCloud(apiKey, baseUrl, model, current);
 } catch (CloudHttpGateway.HttpCallException e) {
 throw ApiException.badRequest(e.getMessage());
 }

 // ---- 3) 测连通过才落库 ----
 // 注意云端的「深度思考」开关与「思考预算」不参与测连
 // （测连是非流式调用，带 enable_thinking 会被百炼拒绝，见 testCloud 注释），
 // 所以它们的值是"测连通过后"才写入的。
 Map<String, String> toSave = new HashMap<>();
 toSave.put(RuntimeSettingsService.KEY_CLOUD_API_KEY, apiKey);
 toSave.put(RuntimeSettingsService.KEY_CLOUD_BASE_URL, baseUrl);
 toSave.put(RuntimeSettingsService.KEY_CLOUD_MODEL, model);
 toSave.put(RuntimeSettingsService.KEY_CLOUD_MODELS,
 RuntimeSettingsService.joinModelList(models));

 if (payload.enableThinking() != null) {
 toSave.put(RuntimeSettingsService.KEY_CLOUD_ENABLE_THINKING,
 payload.enableThinking() ? "true" : "false");
 }
 if (payload.thinkingBudget() != null) {
 // 负数一律归零（0 = 用平台默认），与早期设计 max(0, int(...)) 一致
 toSave.put(RuntimeSettingsService.KEY_CLOUD_THINKING_BUDGET,
 String.valueOf(Math.max(0, payload.thinkingBudget())));
 }
 if (payload.trustEnv() != null) {
 toSave.put(RuntimeSettingsService.KEY_CLOUD_TRUST_ENV,
 payload.trustEnv() ? "true" : "false");
 }
 if (payload.proxyUrl() != null) {
 // proxy_url 传空串 = 清空显式代理（回落到环境变量或直连）
 toSave.put(RuntimeSettingsService.KEY_CLOUD_PROXY_URL,
 payload.proxyUrl().trim());
 }

 settingsService.save(userId, toSave);
 log.info("已更新云端模型配置 userId={} baseUrl={} model={}", userId, baseUrl, model);

 return optionService.buildOptions(userId);
 }

 /**
 * 连通性检查。对应 {@code POST /api/llm-config/test}。
 *
 * <p><b>恒返回 200</b>，用 {@code ok} 表达成败 —— 前端把 {@code message}
 * 直接显示在设置页的提示条上。
 *
 * <p>{@code thinkingSupported} / {@code thinkingHint} 无论成败都会返回：
 * 用户点「检查」往往就是想确认"这个模型开深度思考有没有意义"，
 * 测连失败时这条信息同样有价值。
 */
 @Transactional(readOnly = true)
 public LlmConfigDtos.TestResponse test(Long userId, LlmConfigDtos.TestRequest payload) {
 RuntimeSettingsService.LlmSettings current = settingsService.load(userId);

 String key = notBlank(payload.cloudApiKey()) ? payload.cloudApiKey().trim() : current.apiKey();
 String base = notBlank(payload.cloudBaseUrl())
 ? payload.cloudBaseUrl().trim()
 : (notBlank(current.baseUrl()) ? current.baseUrl() : DEFAULT_OPENAI_BASE_URL);
 String model = notBlank(payload.cloudModel()) ? payload.cloudModel().trim() : current.model();

 ModelCatalog.ThinkingInfo thinking = ModelCatalog.classifyThinking(base, model);

 if (key == null || key.isBlank()) {
 return new LlmConfigDtos.TestResponse(false,
 "尚未填写 API Key，后端也没有已保存的 Key", false, "");
 }

 try {
 connectivity.testCloud(key, base, model, current);
 } catch (CloudHttpGateway.HttpCallException e) {
 return new LlmConfigDtos.TestResponse(
 false, e.getMessage(), thinking.supported(), thinking.hint());
 }
 return new LlmConfigDtos.TestResponse(
 true, "连接成功（" + model + "）", thinking.supported(), thinking.hint());
 }

 /**
 * 按「当前表单填的 base_url + key」拉取模型列表。
 * 对应 {@code POST /api/llm-config/models/fetch}。
 *
 * <p>为什么参数从请求里来而不是读已保存配置：用户正在接入一个新平台，
 * 还没保存；若用已保存的配置去拉，永远拉到上一个平台的模型。
 */
 @Transactional(readOnly = true)
 public LlmConfigDtos.ModelsResponse fetchModels(
 Long userId, LlmConfigDtos.ModelsFetchRequest payload) {
 if (!notBlank(payload.baseUrl()) || !notBlank(payload.apiKey())) {
 throw ApiException.badRequest("请先填写 API Host 与 API Key");
 }
 RuntimeSettingsService.LlmSettings settings = settingsService.load(userId);
 try {
 return new LlmConfigDtos.ModelsResponse(
 connectivity.fetchRemoteModels(payload.baseUrl().trim(),
 payload.apiKey().trim(), settings));
 } catch (CloudHttpGateway.HttpCallException e) {
 throw ApiException.badRequest(e.getMessage());
 }
 }

 /**
 * 仅更新可选模型清单（不重测 Key）。对应 {@code POST /api/llm-config/models}。
 *
 * <p>单独开这个接口的原因：模型清单是"展示用"的配置，
 * 用户整理下拉列表时不该被要求重新填一遍 Key 并再测一次连。
 */
 @Transactional
 public List<LlmOptionDtos.LlmOption> saveModels(
 Long userId, LlmConfigDtos.ModelsUpdateRequest payload) {
 List<String> models = RuntimeSettingsService.parseModelList(
 RuntimeSettingsService.joinModelList(payload.cloudModels()));
 if (models.isEmpty()) {
 throw ApiException.badRequest("模型清单不能为空");
 }
 settingsService.put(userId, RuntimeSettingsService.KEY_CLOUD_MODELS,
 RuntimeSettingsService.joinModelList(models));
 return optionService.buildOptions(userId);
 }

 /**
 * 扫描本机监听端口，找出可用的 HTTP 代理候选。
 * 对应 {@code POST /api/llm-config/detect-proxy}。
 *
 * <p>这是个可能耗时几秒的操作（串行探测每个监听端口），
 * 所以放在显式接口里由用户触发，而不是藏在请求失败的自动重试路径中。
 */
 @Transactional(readOnly = true)
 public LlmConfigDtos.DetectProxyResponse detectProxy(Long userId) {
 RuntimeSettingsService.LlmSettings s = settingsService.load(userId);
 String[] target = proxyDetector.targetHostPort(s.baseUrl());
 List<String> candidates = proxyDetector.findProxyCandidates(
 target[0], Integer.parseInt(target[1]));

 return new LlmConfigDtos.DetectProxyResponse(
 candidates,
 target[0] + ":" + target[1],
 s.proxyUrl(),
 http.envProxy(),
 http.effectiveProxy(s));
 }

 /**
 * 列出本机 Ollama 已安装的聊天模型。对应 {@code GET /api/llm-config/local-models}。
 *
 * <p>与 {@code /api/llm-options} 里的本地项不同，这个接口在 Ollama 不可达时
 * <b>返回 400 并说明原因</b>，而不是静默回落 —— 因为它服务于
 * "管理本机模型"（含删除）这个明确意图，让用户知道连不上比给个假列表有用。
 */
 @Transactional(readOnly = true)
 public LlmConfigDtos.LocalModelsResponse localModels(Long userId) {
 RuntimeSettingsService.LlmSettings s = settingsService.load(userId);
 AppProperties.Llm.Ollama ollama = properties.llm().ollama();
 try {
 return new LlmConfigDtos.LocalModelsResponse(
 connectivity.listOllamaChatModels(ollama.baseUrl(), s),
 ollama.baseUrl(),
 ollama.model());
 } catch (CloudHttpGateway.HttpCallException e) {
 throw ApiException.badRequest(e.getMessage());
 }
 }

 /** 删除本机某个 Ollama 模型（破坏性操作）。对应 {@code DELETE /api/llm-config/local-models}。 */
 @Transactional(readOnly = true)
 public Map<String, Object> deleteLocalModel(
 Long userId, LlmConfigDtos.LocalModelDeleteRequest payload) {
 RuntimeSettingsService.LlmSettings s = settingsService.load(userId);
 AppProperties.Llm.Ollama ollama = properties.llm().ollama();
 try {
 connectivity.deleteOllamaModel(ollama.baseUrl(), payload.name(), s);
 } catch (CloudHttpGateway.HttpCallException e) {
 throw ApiException.badRequest(e.getMessage());
 }
 return Map.of("ok", true, "deleted", payload.name().trim());
 }

 /** 读取上下文窗口当前值。对应 {@code GET /api/llm-config/context-window}。 */
 @Transactional(readOnly = true)
 public LlmConfigDtos.ContextWindowResponse getContextWindow(Long userId) {
 return LlmConfigDtos.ContextWindowResponse.of(
 settingsService.load(userId).ollamaNumCtx());
 }

 /**
 * 修改本地 Ollama 的上下文窗口（num_ctx）。
 * 对应 {@code POST /api/llm-config/context-window}。
 *
 * <p>早期设计在这里还会清空 Agent 缓存，因为 num_ctx 是在<b>构建 Agent 时</b>读取的 ——
 * 不清缓存的话，已经存在的会话仍在用旧的 num_ctx，用户改了设置却看不出变化。
 * 第三阶段实现 Agent 缓存后，本方法末尾需要补上同样的清理动作，
 * 这里先把语义记下来（那段代码目前还不存在，无法提前调用）。
 */
 @Transactional
 public LlmConfigDtos.ContextWindowUpdateResponse setContextWindow(
 Long userId, LlmConfigDtos.ContextWindowUpdateRequest payload) {
 if (payload.ollamaNumCtx() == null) {
 throw ApiException.badRequest("请提供 ollama_num_ctx");
 }
 int n = payload.ollamaNumCtx();
 if (n < LlmConfigDtos.CONTEXT_WINDOW_MIN || n > LlmConfigDtos.CONTEXT_WINDOW_MAX) {
 throw ApiException.badRequest(
 "上下文窗口需在 " + LlmConfigDtos.CONTEXT_WINDOW_MIN
 + " ~ " + LlmConfigDtos.CONTEXT_WINDOW_MAX + " 之间");
 }
 settingsService.put(userId, RuntimeSettingsService.KEY_OLLAMA_NUM_CTX, String.valueOf(n));
 return new LlmConfigDtos.ContextWindowUpdateResponse(true, n);
 }

 private static boolean notBlank(String s) {
 return s != null && !s.isBlank();
 }
}
