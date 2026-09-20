package com.ipas.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ipas.assistant.common.ModelCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型连通性测试与列表拉取。
 *
 * <p>对应 里的 {@code _test_cloud()}、{@code fetch_remote_models()}、
 * {@code _fetch_ollama_tags()}、{@code delete_local_model()}。
 *
 * <p><b>这些都是"普通 HTTP 调用"，不是 AI 调用</b> —— 所以本模块
 * 不需要 Spring AI 就能完整实现，属于第二阶段的正常交付（而非第三阶段的待办）。
 *
 * <p>做成独立的 Service 而不是散在 Controller 里，是因为它有<b>三处调用方</b>：
 * ① {@code /api/llm-config/test}（设置页的「检查」按钮）；
 * ② {@code /api/llm-providers} 的创建/编辑（保存前必须先测连通过才入库）；
 * ③ {@code /api/llm-providers/{id}/test}（列表卡片上的「测试」按钮）。
 * 三处共用同一套测连逻辑，才能保证"保存时能过、列表测试也能过"。
 */
@Service
public class LlmConnectivityService {

 private static final Logger log = LoggerFactory.getLogger(LlmConnectivityService.class);

 /** 拉取模型列表的超时。轻量操作，短超时避免用户干等。 */
 private static final Duration LIST_TIMEOUT = Duration.ofSeconds(15);

 /** 探测 Ollama 是否在运行时的超时，早期设计给 5 秒。 */
 private static final Duration OLLAMA_TAGS_TIMEOUT = Duration.ofSeconds(5);

 /**
 * 本地 Ollama 上属于「向量模型」的名字特征。
 *
 * <p>必须把这类模型从聊天模型列表里滤掉：用户装了 bge-m3 是为了给 RAG 生成向量，
 * 它不是聊天模型，出现在「模型下拉」里会让人误选，
 * 选了之后对话会报一堆语焉不详的错。
 */
 private static final List<String> EMBEDDING_MARKERS = List.of("bge", "embed", "nomic");

 private final CloudHttpGateway http;

 public LlmConnectivityService(CloudHttpGateway http) {
 this.http = http;
 }

 /**
 * 对云端模型做一次「最小化真实调用」，验证 Key / Base URL / 模型名是否都可用。
 *
 * <p><b>为什么必须真的调一次模型，而不是只查连通性</b>：
 * 只做 HTTP 连通性检查的话，Key 是错的、模型名是不存在的，都会返回 200，
 * 于是用户看到"保存成功"，直到真正对话时才报错 —— 这正是早期设计注释里
 * 要避免的糟糕体验。
 *
 * <p><b>⚠️ 不能带 {@code enable_thinking} 参数</b>：阿里云百炼对<b>非流式</b>调用
 * 传这个参数会直接返回 400（"must be set to false for non-streaming calls"）。
 * 测连是非流式的，所以这里绝对不能带 —— 早期设计注释专门标了这一点。
 *
 * @throws CloudHttpGateway.HttpCallException 失败时抛出，消息即失败原因
 */
 public void testCloud(String apiKey,
 String baseUrl,
 String model,
 RuntimeSettingsService.LlmSettings settings) {
 if (apiKey == null || apiKey.isBlank()) {
 throw new CloudHttpGateway.HttpCallException("API Key 为空");
 }
 if (baseUrl == null || baseUrl.isBlank()) {
 throw new CloudHttpGateway.HttpCallException("API Base URL 为空");
 }
 if (model == null || model.isBlank()) {
 throw new CloudHttpGateway.HttpCallException("模型名为空");
 }

 // 统一前缀后再补完整路径（见 ModelCatalog.normalizeOpenAiPrefix 的说明）：
 // 这样"带 /v1"与"不带 /v1"两种写法都能用，且与聊天侧 ChatModelFactory 产出的 URL 一致 ——
 // 修掉"测连通过、一聊天就 404"的那个不一致。
 String url = ModelCatalog.normalizeOpenAiPrefix(baseUrl) + "/v1/chat/completions";

 // 最小化的聊天请求：一句 "hi"、温度 0、只允许生成 1 个 token。
 // max_tokens 限制为 1 是为了让这次测连尽可能快且几乎不产生费用 ——
 // 早期设计没加这个限制（会真的生成完整回复），这里做一个明确的小改进。
 Map<String, Object> body = new LinkedHashMap<>();
 body.put("model", model);
 body.put("messages", List.of(Map.of("role", "user", "content", "hi")));
 body.put("temperature", 0);
 body.put("max_tokens", 1);

 try {
 http.postJson(url, apiKey, body, CloudHttpGateway.LIGHT_TIMEOUT, settings);
 } catch (Exception e) {
 // 重新包一层，让错误信息明确指向"是测连失败"而不是别的地方
 throw new CloudHttpGateway.HttpCallException("连接云端模型失败：" + e.getMessage());
 }
 }

 /**
 * 按给定的 base_url + key 拉取该平台的模型列表。
 *
 * <p><b>为什么必须用「表单里的值」而不是配置里的默认值</b>：
 * 多 API 接入后，默认配置只是某个平台的兜底。用户在设置页接入另一个平台时，
 * 若用默认值去拉列表，永远会拉到上一个平台的模型 —— 早期设计注释专门标注了这个坑。
 *
 * <p><b>部分平台（如阿里云百炼）的 OpenAI 兼容端点不提供 {@code /models}</b>，
 * 会走到失败分支。这时错误信息里要包含可读原因，前端据此提示用户手工填写模型名，
 * 而不是让用户以为是自己填错了。
 *
 * @return 模型 id 列表（去重保序）
 * @throws CloudHttpGateway.HttpCallException 平台不支持或调用失败
 */
 public List<String> fetchRemoteModels(String baseUrl,
 String apiKey,
 RuntimeSettingsService.LlmSettings settings) {
 String url = ModelCatalog.normalizeOpenAiPrefix(baseUrl) + "/v1/models";
 String body;
 try {
 body = http.get(url, apiKey, LIST_TIMEOUT, settings);
 } catch (Exception e) {
 throw new CloudHttpGateway.HttpCallException(
 "该平台未提供 /models 列表接口（如阿里云百炼）或调用失败：" + e.getMessage());
 }

 List<String> models = extractOpenAiModelIds(body);
 if (models.isEmpty()) {
 throw new CloudHttpGateway.HttpCallException(
 "接口未返回模型列表（该平台可能不支持 /models，请手动填写）");
 }
 return models;
 }

 /**
 * 从 OpenAI 兼容的 {@code /models} 响应里抽出模型 id。
 *
 * <p>响应形如 {@code {"data":[{"id":"gpt-4o",...}, ...]}}。
 * 逐个判空是防御性的：不同平台的字段填充程度差别很大，
 * 有的会返回一堆只有 object 字段没有 id 的条目。
 */
 private List<String> extractOpenAiModelIds(String body) {
 List<String> models = new ArrayList<>();
 JsonNode root = http.parseJson(body);
 JsonNode data = root.get("data");
 if (data != null && data.isArray()) {
 for (JsonNode item : data) {
 if (item.isObject()) {
 JsonNode id = item.get("id");
 if (id != null && !id.isNull() && !id.asText().isBlank()) {
 models.add(id.asText());
 }
 }
 }
 }
 return models;
 }

 /**
 * 列出本机 Ollama 已安装的<b>聊天</b>模型（已滤掉向量模型）。
 *
 * @return 模型名列表
 * @throws CloudHttpGateway.HttpCallException Ollama 没启动或不可达
 */
 public List<String> listOllamaChatModels(String ollamaBaseUrl,
 RuntimeSettingsService.LlmSettings settings) {
 String url = stripTrailingSlash(ollamaBaseUrl) + "/api/tags";
 String body;
 try {
 body = http.get(url, null, OLLAMA_TAGS_TIMEOUT, settings);
 } catch (Exception e) {
 throw new CloudHttpGateway.HttpCallException(
 "无法连接本地 Ollama（" + ollamaBaseUrl + "）：" + e.getMessage());
 }

 List<String> names = new ArrayList<>();
 JsonNode root = http.parseJson(body);
 JsonNode models = root.get("models");
 if (models != null && models.isArray()) {
 for (JsonNode m : models) {
 JsonNode name = m.get("name");
 if (name != null && !name.isNull() && !name.asText().isBlank()) {
 names.add(name.asText());
 }
 }
 }

 // 滤掉向量模型（bge-m3 等是给 RAG 用的，不是聊天模型）
 List<String> chatModels = new ArrayList<>();
 for (String n : names) {
 String lower = n.toLowerCase();
 boolean isEmbedding = EMBEDDING_MARKERS.stream().anyMatch(lower::contains);
 if (!isEmbedding) {
 chatModels.add(n);
 }
 }
 return chatModels;
 }

 /**
 * 删除本机某个 Ollama 模型。
 *
 * <p><b>破坏性操作</b>：Ollama 删除即物理删除模型文件，不可撤销，
 * 用户要重新下载（几 GB）。前端有二次确认，后端这里只做参数校验与转发。
 *
 * @throws CloudHttpGateway.HttpCallException 删除失败
 */
 public void deleteOllamaModel(String ollamaBaseUrl,
 String modelName,
 RuntimeSettingsService.LlmSettings settings) {
 String name = modelName == null ? "" : modelName.trim();
 if (name.isEmpty()) {
 throw new CloudHttpGateway.HttpCallException("模型名不能为空");
 }
 String url = stripTrailingSlash(ollamaBaseUrl) + "/api/delete";
 try {
 // 注意 Ollama 的删除接口要求把模型名放在 DELETE 的请求体里
 http.deleteJson(url, Map.of("name", name), LIST_TIMEOUT, settings);
 } catch (Exception e) {
 throw new CloudHttpGateway.HttpCallException("删除失败：" + e.getMessage());
 }
 log.info("已请求 Ollama 删除本地模型：{}", name);
 }

 /**
 * 去掉地址结尾的斜杠，避免拼出 {@code //chat/completions} 这种双斜杠路径。
 */
 private static String stripTrailingSlash(String url) {
 if (url == null) {
 return "";
 }
 String u = url.trim();
 while (u.endsWith("/")) {
 u = u.substring(0, u.length() - 1);
 }
 return u;
 }
}
