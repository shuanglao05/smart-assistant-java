package com.ipas.assistant.service;

import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.entity.AppSetting;
import com.ipas.assistant.repository.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 运行时配置读写 —— 本模块是「配置落库」方案的核心。
 *
 * <h2>它替代了早期设计的什么</h2>
 *
 * <p>早期设计 里 {@code CLOUD_API_KEY}、{@code CLOUD_BASE_URL}、
 * {@code OLLAMA_NUM_CTX} 这些是<b>模块级全局变量</b>：设置页保存时先改内存里的值
 * （立即生效），再回写 {@code backend/.env}（持久化）。
 *
 * <p>Java 侧行不通：{@link AppProperties} 是启动时绑定的不可变 record，
 * 而配置文件打包在 jar 里、运行时回写没有意义。所以改成
 * <b>「数据库持久化 + 读取时合并默认值」</b>：
 *
 * <pre>
 * 读取某项配置 = 数据库里有 → 用库里的
 * 数据库里没有 → 回落到 application.yml / 环境变量的默认值
 * </pre>
 *
 * <p>这个「回落」设计带来一个重要好处：<b>用户什么都没配时，行为与
 * 只用 application.yml 启动完全一致</b>。所以配置表可以是空的，
 * 系统照常工作（早期设计也是这样——.env 没写 CLOUD_* 就只走本地 Ollama）。
 *
 * <h2>比原方案好的地方</h2>
 * <ol>
 * <li>不再写文件，避免并发写同一个 {@code .env} 造成内容损坏
 * （早期设计 {@code _update_env_file} 是「读全文 → 改几行 → 写回全文」，
 * 两个请求同时保存会互相覆盖）；</li>
 * <li>天然按 {@code user_id} 隔离，多用户各自一套配置
 * （早期设计注释里明确承认「当前云端 API 是全局共享配置，所有用户共用」，
 * 那是当时的妥协）；</li>
 * <li>配置变成可查询的数据，排查「当前到底用的哪套配置」时直接查表即可。</li>
 * </ol>
 */
@Service
public class RuntimeSettingsService {

 // ---- 配置项名常量（集中在此，避免各处手写字符串拼错）----
 public static final String KEY_CLOUD_API_KEY = "cloud.api-key";
 public static final String KEY_CLOUD_BASE_URL = "cloud.base-url";
 public static final String KEY_CLOUD_MODEL = "cloud.model";
 public static final String KEY_CLOUD_MODELS = "cloud.models";
 public static final String KEY_CLOUD_ENABLE_THINKING = "cloud.enable-thinking";
 public static final String KEY_CLOUD_THINKING_BUDGET = "cloud.thinking-budget";
 public static final String KEY_CLOUD_TRUST_ENV = "cloud.trust-env";
 public static final String KEY_CLOUD_PROXY_URL = "cloud.proxy-url";
 public static final String KEY_OLLAMA_NUM_CTX = "ollama.num-ctx";
 /**
 * RAG 全局默认检索片段数（Top-K）。对应 {@code RAG_TOP_K}——
 * 早期设计设置页改了它之后会回写 {@code .env} 持久化。Java 侧改用"配置落库"，
 * 这一项存进 app_settings，读取时回落到 {@code application.yml} 的默认值。
 */
 public static final String KEY_RAG_TOP_K = "rag.top-k";

 /**
 * 数据目录位置（对应 .env 里的 {@code DATA_DIR}）。
 *
 * <p><b>为什么是"全局"而不是按用户</b>：早期设计 {@code DATA_DIR} 来自环境变量 / .env，
 * 是<b>进程级</b>的 —— 上传文件、缓存都落在同一个目录，不区分用户。
 * 本项目的 app_settings 是按 user_id 隔离的，所以这里约定用
 * {@link #GLOBAL_USER_ID} 这一固定 id 存放"不属于任何用户的全局配置"。
 */
 public static final String KEY_SYSTEM_DATA_DIR = "system.data-dir";

 /**
 * 全局配置的固定 user_id。
 *
 * <p>app_settings 表按 user_id 隔离，但有些配置（代理、数据目录）本质是进程级的。
 * 用 0 这个业务上不可能出现的 id 承载它们，避免"到底该读哪个用户的"这种歧义。
 */
 public static final Long GLOBAL_USER_ID = 0L;

 /** 一次性读全所需的配置项，避免逐项查库。 */
 private static final List<String> ALL_KEYS = List.of(
 KEY_CLOUD_API_KEY, KEY_CLOUD_BASE_URL, KEY_CLOUD_MODEL, KEY_CLOUD_MODELS,
 KEY_CLOUD_ENABLE_THINKING, KEY_CLOUD_THINKING_BUDGET,
 KEY_CLOUD_TRUST_ENV, KEY_CLOUD_PROXY_URL, KEY_OLLAMA_NUM_CTX);

 private final AppSettingRepository repository;
 private final AppProperties defaults;

 public RuntimeSettingsService(AppSettingRepository repository, AppProperties defaults) {
 this.repository = repository;
 this.defaults = defaults;
 }

 /**
 * 当前生效的「大模型相关配置」快照。
 *
 * <p>一次查库拿下全部配置项，然后在内存里逐项与默认值合并 ——
 * 比"每项一次查询"少 8 次数据库往返，而这个快照在每次对话构建 Agent 时都会用到。
 */
 @Transactional(readOnly = true)
 public LlmSettings load(Long userId) {
 Map<String, String> stored = new HashMap<>();
 for (AppSetting s : repository.findByUserIdAndSettingKeyIn(userId, ALL_KEYS)) {
 stored.put(s.getSettingKey(), s.getSettingValue());
 }

 AppProperties.Llm llm = defaults.llm();
 AppProperties.Llm.Cloud cloud = llm.cloud();

 return new LlmSettings(
 pick(stored, KEY_CLOUD_API_KEY, cloud.apiKey()),
 pick(stored, KEY_CLOUD_BASE_URL, cloud.baseUrl()),
 pick(stored, KEY_CLOUD_MODEL, cloud.model()),
 parseModelList(pick(stored, KEY_CLOUD_MODELS, null)),
 pickBoolean(stored, KEY_CLOUD_ENABLE_THINKING, cloud.enableThinking()),
 pickInt(stored, KEY_CLOUD_THINKING_BUDGET, cloud.thinkingBudget()),
 pickBoolean(stored, KEY_CLOUD_TRUST_ENV, false),
 pick(stored, KEY_CLOUD_PROXY_URL, ""),
 pickInt(stored, KEY_OLLAMA_NUM_CTX, llm.ollama().numCtx()));
 }

 /**
 * 批量写入配置（upsert）。
 *
 * <p>不整体删除重插，而是逐项「有则改、无则插」——
 * 这样只改代理地址时不会把 Key 等其它项一并抹掉，
 * 也避免自增 id 无意义地增长。
 *
 * @param values 配置项名 → 值。值为 null 表示"清空该项、回落到默认"（会删除该行）
 */
 @Transactional
 public void save(Long userId, Map<String, String> values) {
 for (Map.Entry<String, String> e : values.entrySet()) {
 String key = e.getKey();
 String value = e.getValue();

 if (value == null) {
 // null 表示"恢复默认"，直接删掉这一行，读取时就会回落到配置文件的默认值
 repository.deleteByUserIdAndSettingKey(userId, key);
 continue;
 }

 Optional<AppSetting> existing = repository.findByUserIdAndSettingKey(userId, key);
 if (existing.isPresent()) {
 existing.get().setSettingValue(value);
 } else {
 repository.save(AppSetting.of(userId, key, value));
 }
 }
 }

 /** 便捷写入单项。 */
 @Transactional
 public void put(Long userId, String key, String value) {
 save(userId, Map.of(key, value));
 }

 /**
 * 读取当前生效的 RAG 全局默认 Top-K（对应 {@code RAG_TOP_K}）。
 *
 * <p>数据库里有 {@code rag.top-k} → 用库里的；否则回落到
 * {@code application.yml} 的 {@code app.rag.top-k}（默认 4）。
 * 设置页改了 Top-K 会经 {@link #put} 落库，下一次检索立即生效——
 * 这正是早期设计"回写 .env 立即生效"的 Java 等价物。
 */
 @Transactional(readOnly = true)
 public int getRagTopK(Long userId) {
 return repository.findByUserIdAndSettingKey(userId, KEY_RAG_TOP_K)
 .map(s -> {
 try {
 return Integer.parseInt(s.getSettingValue().trim());
 } catch (NumberFormatException e) {
 // 库里存了非数字（手工改库）→ 回落默认，不要让检索 500
 return defaults.rag().topK();
 }
 })
 .orElse(defaults.rag().topK());
 }

 /**
 * 读取当前生效的数据目录（全局配置）。
 *
 * <p>库里有 {@code system.data-dir} → 用它；否则回落到 {@code application.yml}
 * 的 {@code app.data-dir}（默认 {@code ./data}）。
 * 早期设计把 DATA_DIR 写在 .env 里、改完要重启；这里改为落库，
 * 由 {@code DataDirProvider} 每次读取，<b>改完立即生效</b>。
 *
 * @return 数据目录路径字符串；为空表示未配置（调用方应回落到默认值）
 */
 @Transactional(readOnly = true)
 public String getGlobalDataDir() {
 return repository.findByUserIdAndSettingKey(GLOBAL_USER_ID, KEY_SYSTEM_DATA_DIR)
 .map(s -> s.getSettingValue() == null ? "" : s.getSettingValue().trim())
 .orElse("");
 }

 // ==================================================================
 // 取值辅助：有库里的用库里的，没有就用默认值
 // ==================================================================

 private static String pick(Map<String, String> stored, String key, String fallback) {
 String v = stored.get(key);
 // 空串与"未设置"同义：早期设计把 CLOUD_BASE_URL 清空就是"回落到本地 Ollama"，
 // 所以这里不能把空串当成有效值返回。
 if (v == null || v.isBlank()) {
 return fallback == null ? "" : fallback;
 }
 return v;
 }

 private static boolean pickBoolean(Map<String, String> stored, String key, boolean fallback) {
 String v = stored.get(key);
 if (v == null || v.isBlank()) {
 return fallback;
 }
 return "1".equals(v) || "true".equalsIgnoreCase(v)
 || "yes".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
 }

 private static int pickInt(Map<String, String> stored, String key, int fallback) {
 String v = stored.get(key);
 if (v == null || v.isBlank()) {
 return fallback;
 }
 try {
 return Integer.parseInt(v.trim());
 } catch (NumberFormatException e) {
 // 库里存了非数字（手工改库造成）→ 回落到默认值，不要让整个接口 500
 return fallback;
 }
 }

 /**
 * 解析逗号分隔的模型清单（去空、去重、保序）。
 *
 * <p>去重为什么要保序：模型下拉的顺序是用户自己排的，
 * 用 {@code HashSet} 会把顺序打乱，用户每次保存后看到顺序都变，体验很差。
 */
 public static List<String> parseModelList(String raw) {
 if (raw == null || raw.isBlank()) {
 return new ArrayList<>();
 }
 Set<String> seen = new LinkedHashSet<>();
 for (String part : raw.split("[,\\n]")) {
 String m = part.trim();
 if (!m.isEmpty()) {
 seen.add(m);
 }
 }
 return new ArrayList<>(seen);
 }

 /** 把模型清单拼回存储格式。 */
 public static String joinModelList(List<String> models) {
 if (models == null) {
 return "";
 }
 return models.stream()
 .filter(m -> m != null && !m.isBlank())
 .map(String::trim)
 .reduce((a, b) -> a + "," + b)
 .orElse("");
 }

 /**
 * 当前生效的大模型配置快照。
 *
 * @param apiKey 云端 Key
 * @param baseUrl 云端 OpenAI 兼容端点（空 = 只用本地 Ollama）
 * @param model 默认模型名
 * @param models 可选模型清单（已去重保序）
 * @param enableThinking 云端深度思考开关（只对流式调用生效）
 * @param thinkingBudget 思维链 token 上限；0 = 平台默认
 * @param trustEnv 是否绕过代理直连
 * @param proxyUrl 显式代理地址；空 = 不指定
 * @param ollamaNumCtx 本地 Ollama 的上下文窗口（num_ctx）
 */
 public record LlmSettings(
 String apiKey,
 String baseUrl,
 String model,
 List<String> models,
 boolean enableThinking,
 int thinkingBudget,
 boolean trustEnv,
 String proxyUrl,
 int ollamaNumCtx
 ) {
 }
}
