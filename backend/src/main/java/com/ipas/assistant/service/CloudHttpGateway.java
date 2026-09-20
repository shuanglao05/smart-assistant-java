package com.ipas.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 云端 / 本地模型的 HTTP 调用网关。
 *
 * <p>这是早期设计那段"用共享 {@code httpx.Client} + 手动保活 + 代理自愈"的逻辑在 Java 侧的对应物。
 *
 * <h2>一、为什么必须共享同一个 HttpClient（性能关键）</h2>
 *
 * <p>早期设计注释里记录的实测数据：走代理时<b>冷建连 20~40 秒、热请求 0.9 秒</b>。
 * 根因是 {@code httpx} 连接池默认 {@code keepalive_expiry=5s}，用户"打字→发送"的间隔
 * 通常超过 5 秒，于是每次提问都在重新握手，表现为"又慢又时好时坏"。
 *
 * <p>Java 的 {@code HttpClient} 本身就有连接池，但<b>必须复用同一个实例</b> ——
 * 每次 new 一个新的 HttpClient，它自带的连接池就是空的，等于每次冷启动。
 * 这就是本类存在的首要理由。
 *
 * <h2>二、代理配置改变时必须重建</h2>
 *
 * <p>代理是<b>建 HttpClient 时绑定</b>的，实例建好之后改不了。
 * 用户在设置页把代理从 A 改成 B 之后，如果还复用旧实例，请求仍会走 A ——
 * 表现为"改完代理没生效，非要重启后端"。所以这里记住"当前实例是用哪个代理建的"，
 * 每次取用时比对，不一致就重建。
 *
 * <h2>三、与早期设计的一处有意差异</h2>
 *
 * <p>早期设计有一段"预热失败后扫描本机所有监听端口、逐个发 CONNECT 探测出可用代理"的
 * 自愈逻辑（因为它的代理软件端口常变且不进环境变量）。本类<b>不自动做这件事</b>，
 * 而是把它做成一个显式接口（{@code POST /api/llm-config/detect-proxy}），
 * 由用户在设置页点「自动检测」触发。
 *
 * <p>理由：自动扫描会在每次请求失败时都去扫一遍全端口，耗时且行为不可预期；
 * 显式触发让用户知道发生了什么、也能看到候选项。功能没少，只是从"隐式魔法"
 * 变成"可控操作"。
 */
@Service
public class CloudHttpGateway {

 private static final Logger log = LoggerFactory.getLogger(CloudHttpGateway.class);

 /**
 * 单次请求超时。
 *
 * <p>取值 180 秒与早期设计一致（原 {@code httpx.Timeout(180.0, connect=30.0)}）。
 * 之所以要这么长：推理型模型（开深度思考）单次生成本来就可能超过 1 分钟，
 * 设太短会把正常请求判成失败。
 */
 private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);

 /** 建连超时。早期设计给 30 秒，因为走代理建连本身就慢。 */
 private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

 /** 测连/拉列表这类轻量操作用更短的超时，避免用户点一下按钮干等三分钟。 */
 public static final Duration LIGHT_TIMEOUT = Duration.ofSeconds(20);

 /** 环境变量里可能存代理的名字（大小写都查，Windows 上常见大写）。 */
 private static final List<String> PROXY_ENV_NAMES = List.of(
 "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy",
 "ALL_PROXY", "all_proxy");

 private final ObjectMapper objectMapper;

 /**
 * 当前共享的 HttpClient（volatile：会被请求线程读写，且需要跨线程可见）。
 *
 * <p>{@code @Service} 单例 + volatile 字段 = 全局唯一实例，
 * 正好对应那个模块级的 {@code _CLOUD_HTTP}。
 */
 private volatile HttpClient sharedClient;

 /** 当前共享 client 是用哪个代理建的（null 表示直连）。用于检测配置变化后重建。 */
 private volatile String clientProxyUsed;

 public CloudHttpGateway(ObjectMapper objectMapper) {
 this.objectMapper = objectMapper;
 }

 // ==================================================================
 // 代理解析
 // ==================================================================

 /**
 * 读取环境变量里的代理地址。
 *
 * <p>注意这是<b>进程启动时继承</b>的值，用户改了系统代理或代理软件换了端口之后，
 * 这里不会自动更新 —— 早期设计注释专门强调了这一点，也是它做"自动检测"的原因。
 * 本方法主要用于界面上展示"环境变量里是什么"，不是实际生效值。
 */
 public String envProxy() {
 for (String name : PROXY_ENV_NAMES) {
 String v = System.getenv(name);
 if (v != null && !v.isBlank()) {
 return v.trim();
 }
 }
 return "";
 }

 /**
 * 当前实际生效的代理地址；返回空串表示直连。
 *
 * <p>优先级（与早期设计 {@code cloud_proxy_url()} 一致）：
 * <ol>
 * <li>{@code trustEnv = true}（用户明确要求绕过代理直连）→ 直连，忽略下面全部；</li>
 * <li>设置里显式填的 {@code proxyUrl} → 用它；</li>
 * <li>环境变量里的代理 → 用它；</li>
 * <li>都没有 → 直连。</li>
 * </ol>
 */
 public String effectiveProxy(RuntimeSettingsService.LlmSettings settings) {
 if (settings.trustEnv()) {
 return "";
 }
 if (settings.proxyUrl() != null && !settings.proxyUrl().isBlank()) {
 return settings.proxyUrl().trim();
 }
 return envProxy();
 }

 /**
 * 取共享 HttpClient；代理配置变了会重建。
 */
 private HttpClient clientFor(RuntimeSettingsService.LlmSettings settings) {
 String proxy = effectiveProxy(settings);
 HttpClient current = sharedClient;
 if (current != null && java.util.Objects.equals(clientProxyUsed, proxy)) {
 return current;
 }
 synchronized (this) {
 // 双重检查：并发时只重建一次
 if (sharedClient != null && java.util.Objects.equals(clientProxyUsed, proxy)) {
 return sharedClient;
 }
 HttpClient built = HttpClient.newBuilder()
 .connectTimeout(CONNECT_TIMEOUT)
 .followRedirects(HttpClient.Redirect.NORMAL)
 .proxy(proxySelectorFor(proxy))
 .build();
 sharedClient = built;
 clientProxyUsed = proxy;
 log.info("已重建云端 HTTP 客户端，代理={}", proxy.isEmpty() ? "直连" : proxy);
 return built;
 }
 }

 /**
 * 把代理地址串转成 {@link ProxySelector}。
 *
 * <p>直连时不能简单地不设置 proxy（那会用系统默认），
 * 而要显式给一个"永远返回直连"的选择器 —— 否则用户勾了"绕过代理直连"
 * 却仍然走了系统代理，设置失效且很难察觉。
 *
 * <p>地址解析上做容错：代理串没写协议前缀（如 {@code 127.0.0.1:7890}）时补上
 * {@code http://}，否则 {@code URI} 解析会拿不到 host。
 *
 * <p><b>⚠️ 回环地址必须绕过代理</b>（见 {@link #isLoopback}）。
 * 这不是优化，而是正确性问题：本项目的<b>默认模型来源就是本机 Ollama</b>
 * （{@code http://localhost:11434}）。若把访问它的请求也丢给代理，
 * 而代理又到不了本机端口，用户会遇到"Ollama 明明在跑却连不上"，
 * 且很难联想到是代理造成的。这也是所有 HTTP 客户端
 * （{@code no_proxy} / {@code NO_PROXY}）的通行约定。
 */
 private ProxySelector proxySelectorFor(String proxy) {
 if (proxy == null || proxy.isBlank()) {
 return new ProxySelector() {
 @Override
 public List<Proxy> select(URI uri) {
 return List.of(Proxy.NO_PROXY);
 }

 @Override
 public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
 // 直连不存在"代理连不上"，无需处理
 }
 };
 }

 String normalized = proxy.contains("://") ? proxy : "http://" + proxy;
 InetSocketAddress proxyAddress;
 try {
 URI u = URI.create(normalized);
 int port = u.getPort() > 0 ? u.getPort() : 80;
 String host = u.getHost() == null ? "127.0.0.1" : u.getHost();
 proxyAddress = new InetSocketAddress(host, port);
 } catch (Exception e) {
 // 地址填错时退回系统默认而不是抛异常：让请求本身去报真实的网络错误，
 // 比在这里报"代理地址格式不对"更贴近用户想解决的问题
 log.warn("代理地址无法解析，将按系统默认处理: {} ({})", proxy, e.getMessage());
 return ProxySelector.getDefault();
 }

 return new ProxySelector() {
 @Override
 public List<Proxy> select(URI uri) {
 if (uri != null && isLoopback(uri.getHost())) {
 // 访问本机服务（Ollama / mock / 本地网关）时直连
 return List.of(Proxy.NO_PROXY);
 }
 return List.of(new Proxy(Proxy.Type.HTTP, proxyAddress));
 }

 @Override
 public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
 log.warn("代理连接失败 {}（将按网络错误上报给调用方）: {}", sa, ioe.getMessage());
 }
 };
 }

 /**
 * 判断主机名是否为回环地址。
 *
 * <p>覆盖这几种写法：{@code localhost}、{@code 127.x.x.x}（整个 127/8 段都是回环）、
 * IPv6 的 {@code ::1} 与 {@code [::1]}（{@code URI.getHost()} 对 IPv6 会带方括号）。
 */
 private static boolean isLoopback(String host) {
 if (host == null || host.isEmpty()) {
 return false;
 }
 String h = host.toLowerCase();
 return h.equals("localhost")
 || h.startsWith("127.")
 || h.equals("::1")
 || h.equals("[::1]")
 || h.equals("0.0.0.0");
 }

 // ==================================================================
 // 请求
 // ==================================================================

 /**
 * 发 GET 请求。
 *
 * @param url 完整地址
 * @param bearerToken Bearer 令牌；null 表示不带鉴权头
 * @param timeout 超时
 * @return 响应体文本
 * @throws HttpCallException 网络失败或非 2xx（异常消息里带可读原因）
 */
 public String get(String url,
 String bearerToken,
 Duration timeout,
 RuntimeSettingsService.LlmSettings settings) {
 return get(url, bearerToken, timeout, settings, Map.of());
 }

 /**
 * 发 GET 请求（可带自定义请求头）。
 *
 * <p><b>为什么需要这个重载</b>：部分公开接口（如节假日数据源 timor.tech）
 * 会按 {@code User-Agent} 区分 client —— Java 默认的 {@code Java-http-client/xx}
 * 可能被当成爬虫拒绝。早期设计就是显式写了浏览器的 UA 才拿得到数据，
 * 这里必须保留同样的行为，否则"本地能 curl 通、代码里却拉不到"。
 *
 * @param extraHeaders 额外请求头；可为 {@code Map.of()}
 */
 public String get(String url,
 String bearerToken,
 Duration timeout,
 RuntimeSettingsService.LlmSettings settings,
 Map<String, String> extraHeaders) {
 HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
 .GET()
 .timeout(timeout)
 .header("Accept", "application/json");
 if (bearerToken != null && !bearerToken.isBlank()) {
 b.header("Authorization", "Bearer " + bearerToken);
 }
 if (extraHeaders != null) {
 extraHeaders.forEach(b::header);
 }
 return send(b.build(), settings);
 }

 /**
 * 发 POST JSON 请求。
 */
 public String postJson(String url,
 String bearerToken,
 Object body,
 Duration timeout,
 RuntimeSettingsService.LlmSettings settings) {
 HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
 .timeout(timeout)
 .header("Content-Type", "application/json")
 .header("Accept", "application/json")
 .POST(HttpRequest.BodyPublishers.ofString(toJson(body)));
 if (bearerToken != null && !bearerToken.isBlank()) {
 b.header("Authorization", "Bearer " + bearerToken);
 }
 return send(b.build(), settings);
 }

 /**
 * 发 DELETE 请求（带 JSON 体）。
 *
 * <p>为什么 DELETE 还带 body：Ollama 的删除模型接口
 * （{@code DELETE /api/delete}）就是要 JSON 体传模型名。
 * 这不符合 HTTP 的常见习惯，但那是 Ollama 的既定设计，只能照做。
 * 前端调用的也是这个形状（axios 的 {@code delete(url, { data }) }）。
 */
 public String deleteJson(String url,
 Object body,
 Duration timeout,
 RuntimeSettingsService.LlmSettings settings) {
 HttpRequest request = HttpRequest.newBuilder(URI.create(url))
 .timeout(timeout)
 .header("Content-Type", "application/json")
 .method("DELETE", HttpRequest.BodyPublishers.ofString(toJson(body)))
 .build();
 return send(request, settings);
 }

 /**
 * 执行请求并把失败翻译成可读的中文原因。
 *
 * <p>错误信息里<b>尽量带上平台返回的原始内容</b>：云端返回的错误体通常写明
 * 「Key 无效」还是「模型不存在」，这是用户唯一能据此改正的线索。
 * 只说"请求失败"等于什么都没说 —— 早期设计注释里也强调了
 * "避免保存成功、对话才报错"的体验。
 */
 private String send(HttpRequest request, RuntimeSettingsService.LlmSettings settings) {
 try {
 HttpResponse<String> resp = clientFor(settings)
 .send(request, HttpResponse.BodyHandlers.ofString());
 int code = resp.statusCode();
 if (code >= 200 && code < 300) {
 return resp.body();
 }
 throw new HttpCallException("HTTP " + code + "：" + shorten(resp.body()));
 } catch (HttpCallException e) {
 throw e;
 } catch (java.net.http.HttpTimeoutException e) {
 throw new HttpCallException("请求超时（" + REQUEST_TIMEOUT.toSeconds() + "s）："
 + request.uri().getHost());
 } catch (IOException e) {
 throw new HttpCallException(describeIoFailure(e, request.uri()));
 } catch (InterruptedException e) {
 // 恢复中断标记，避免吞掉中断信号（这是并发编程的基本礼仪）
 Thread.currentThread().interrupt();
 throw new HttpCallException("请求被中断");
 }
 }

 /**
 * 把 IO 异常翻译成人能看懂的中文原因。
 *
 * <p><b>为什么必须专门写这个</b>：JDK 抛的 {@code ConnectException}
 * 在目标端口不通时<b>消息是 null</b>，直接拼进去就成了「网络错误：null」
 * —— 用户看到这行字完全不知道该检查什么，等于没提示。
 * 实测就是这么暴露出来的（连本机未启动的 Ollama 时返回了 "网络错误：null"）。
 *
 * <p>这里按异常类型给出<b>可操作的</b>提示：该去检查服务有没有启动、
 * 还是域名写错、还是被 TLS 拦了。
 */
 private static String describeIoFailure(IOException e, URI uri) {
 String host = uri.getHost() == null ? uri.toString() : uri.getHost();
 int port = uri.getPort();

 if (e instanceof java.net.ConnectException) {
 return "无法连接到 " + host + (port > 0 ? ":" + port : "")
 + "（服务未启动、端口不通，或被防火墙/代理拦截）";
 }
 if (e instanceof java.net.UnknownHostException) {
 return "域名解析失败：" + host + "（地址是否拼错，或本机 DNS 不可用）";
 }
 if (e instanceof javax.net.ssl.SSLException) {
 return "TLS 握手失败（" + host + "）：" + nullSafeMessage(e);
 }
 if (e instanceof java.net.SocketException) {
 return "连接被中断（" + host + "）：" + nullSafeMessage(e);
 }
 return "网络错误（" + host + "）：" + nullSafeMessage(e);
 }

 /**
 * 取异常的可读消息，逐级回退：自身消息 → 原因消息 → 异常类名。
 *
 * <p>三级回退是必要的：JDK 的网络异常经常只有 {@code message == null}
 * 而真正的原因在 {@code cause} 里；极端情况下连 cause 都没有，
 * 至少给出异常类名，让排查的人知道是哪种失败。
 */
 private static String nullSafeMessage(Throwable e) {
 if (e.getMessage() != null && !e.getMessage().isBlank()) {
 return e.getMessage();
 }
 Throwable cause = e.getCause();
 if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
 return cause.getMessage();
 }
 if (cause != null) {
 return cause.getClass().getSimpleName();
 }
 return e.getClass().getSimpleName();
 }

 /** 把过长的响应体截断，避免错误信息里塞进几 MB 的内容。 */
 private static String shorten(String body) {
 if (body == null) {
 return "(空响应)";
 }
 String t = body.replaceAll("\\s+", " ").trim();
 return t.length() > 500 ? t.substring(0, 500) + "…" : t;
 }

 private String toJson(Object body) {
 try {
 return objectMapper.writeValueAsString(body);
 } catch (Exception e) {
 throw new HttpCallException("请求体序列化失败：" + e.getMessage());
 }
 }

 /**
 * 解析响应为 JsonNode，方便取字段。
 *
 * @throws HttpCallException 响应不是合法 JSON
 */
 public JsonNode parseJson(String body) {
 try {
 return objectMapper.readTree(body == null ? "{}" : body);
 } catch (Exception e) {
 throw new HttpCallException("响应不是合法 JSON：" + shorten(body));
 }
 }

 /**
 * 供 {@link com.ipas.assistant.common.GlobalExceptionHandler} 之外的上层捕获的
 * HTTP 调用异常。
 *
 * <p>做成受检之外的 RuntimeException：调用链很深（Service → Gateway），
 * 逐层声明 throws 会把代码淹没。它的消息已经是给用户看的中文，可以直接展示。
 */
 public static class HttpCallException extends RuntimeException {
 public HttpCallException(String message) {
 super(message);
 }
 }

 /** 供测试与诊断使用：当前共享 client 是否已建立。 */
 public boolean hasSharedClient() {
 return sharedClient != null;
 }

 /** 供测试使用：当前实例绑定的代理。 */
 public String currentClientProxy() {
 return clientProxyUsed;
 }

 /** 组装请求头（少量场景需要自定义，如 Ollama 不带鉴权）。 */
 public static Map<String, String> jsonHeaders() {
 return Map.of("Content-Type", "application/json", "Accept", "application/json");
 }
}
