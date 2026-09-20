package com.ipas.assistant.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipas.assistant.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 文本向量化服务。对应 的 {@code _embed_one / _embed_batch / embed_texts}。
 *
 * <h2>为什么调 Ollama 的 OpenAI 兼容端点（而非原生 /api/embed）</h2>
 *
 * <p>本项目的 {@code application.yml} 把 {@code app.rag.embed-base-url} 默认配成
 * {@code http://localhost:11434/v1} —— 即 Ollama 的 OpenAI 兼容接口的 base。
 * 所以这里最终请求 {@code <embed-base-url>/embeddings}，请求体是
 * {@code {"model": "bge-m3", "input": ["文本1", "文本2", ...]}}，响应是
 * {@code {"data": [{"embedding": [...]}, ...]}}。</p>
 *
 * <p>关键点：无论走原生 {@code /api/embed} 还是 OpenAI 兼容 {@code /v1/embeddings}，
 * 底层都是 Ollama 的同一个 bge-m3 模型 —— 所以这里产出的向量，
 * 和已入库的 {@code kb_chunks.embedding}（早期实现用 bge-m3 生成的 1024 维向量）
 * <b>维度与语义完全一致</b>，可以直接算余弦。这正是"已入库向量直接复用、不重新向量化"成立的前提。</p>
 *
 * <h2>失败必须优雅降级</h2>
 *
 * <p>本方法是 RAG 检索的"入口"，但 Ollama 可能没启动、bge-m3 没拉取、网络不通。
 * 这些都属于"运行环境"问题，<b>绝不能让工具调用炸掉整个 Agent 循环</b>。
 * 所以任何异常都吞掉并记日志，返回 {@code null} —— 上层 {@code RagService}
 * 据此认为"没有可比较的向量"，检索返回空，工具再给用户一句友好的"知识库未建立索引"。</p>
 *
 * <h2>超时设置</h2>
 *
 * <p>连接 5s（Ollama 没开时快速失败，不要卡住用户），读取 60s（长文本批量向量化可能较慢）。
 * 走回环地址（localhost/127.x）时由 JVM 默认代理选择器直接连，不会误走代理。</p>
 */
@Service
public class EmbeddingService {

 private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

 private final AppProperties properties;
 private final RestTemplate restTemplate;
 private final ObjectMapper objectMapper = new ObjectMapper();

 public EmbeddingService(AppProperties properties) {
 this.properties = properties;
 // 单独配超时：默认 RestTemplate 是无限等待，向量化卡住会拖垮整个对话请求
 var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
 factory.setConnectTimeout(Duration.ofSeconds(5));
 factory.setReadTimeout(Duration.ofSeconds(60));
 this.restTemplate = new RestTemplate(factory);
 // 让 RestTemplate 能把 Map/List 序列化成 JSON（默认已带此转换器，显式确认一下）
 if (this.restTemplate.getMessageConverters().stream()
 .noneMatch(c -> c instanceof MappingJackson2HttpMessageConverter)) {
 this.restTemplate.getMessageConverters().add(new MappingJackson2HttpMessageConverter());
 }
 }

 /** 把一段文本向量化（内部走批量接口，取第一条）。失败返回 null。 */
 public float[] embed(String text) {
 if (text == null || text.isBlank()) {
 return null;
 }
 float[][] r = embed(List.of(text));
 return r == null ? null : r[0];
 }

 /**
 * 批量向量化。对应 {@code embed_texts}（一次传多条，减少往返）。
 *
 * @return 与输入一一对应的向量数组；任何失败返回 null（上层据此降级）
 */
 public float[][] embed(List<String> texts) {
 if (texts == null || texts.isEmpty()) {
 return null;
 }
 String model = properties.rag().embedModel();
 // 拼完整端点：embed-base-url 可能以 / 结尾，也可能正好到 /v1，统一规整成 ".../embeddings"
 String base = properties.rag().embedBaseUrl();
 String url = base.endsWith("/embeddings")
 ? base
 : base.replaceAll("/+$", "") + "/embeddings";
 try {
 Map<String, Object> body = Map.of("model", model, "input", texts);
 String resp = restTemplate.postForObject(url, body, String.class);
 if (resp == null) {
 log.warn("embedding 返回为空（端点 {}）", url);
 return null;
 }
 JsonNode root = objectMapper.readTree(resp);
 JsonNode data = root.get("data");
 if (data == null || !data.isArray()) {
 // Ollama 出错时可能返回 {"error": "..."} 之类，没有任何向量可用
 log.warn("embedding 响应缺少 data 数组（模型 {} 可能未加载）：{}",
 model, resp.length() > 200 ? resp.substring(0, 200) : resp);
 return null;
 }
 float[][] out = new float[data.size()][];
 for (int i = 0; i < data.size(); i++) {
 JsonNode emb = data.get(i).get("embedding");
 out[i] = toFloats(emb);
 }
 return out;
 } catch (RestClientException e) {
 // 连接拒绝 / 超时 / 非 2xx —— Ollama 多半没开或模型没拉取
 log.warn("embedding 调用失败（Ollama 可能未启动或未加载模型 {}）：{}",
 model, e.getMessage());
 return null;
 } catch (Exception e) {
 log.warn("embedding 解析失败：{}", e.getMessage());
 return null;
 }
 }

 /** 把 JSON 数组节点解析成 float[]。维度不符或解析失败返回空数组（上层视其为无效）。 */
 private static float[] toFloats(JsonNode node) {
 if (node == null || !node.isArray()) {
 return new float[0];
 }
 float[] out = new float[node.size()];
 for (int i = 0; i < node.size(); i++) {
 JsonNode n = node.get(i);
 out[i] = n.isNumber() ? n.floatValue() : 0f;
 }
 return out;
 }
}
