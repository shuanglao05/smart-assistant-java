package com.ipas.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * 本机 HTTP 代理探测。对应 的
 * {@code _listening_ports()} / {@code _is_http_proxy()} / {@code detect_proxy()}。
 *
 * <h2>为什么需要这个功能（早期设计注释里的实测背景）</h2>
 *
 * <p>本项目实际使用中，<b>直连阿里云极慢</b>（TLS 握手 30~46 秒、经常超时），
 * 必须走代理才快（约 0.9 秒）。但代理软件（Clash / v2ray 等）有两个特点：
 * <ul>
 * <li>监听端口<b>经常变化</b>（可能随机取高端口如 65262）；</li>
 * <li>未必写入系统环境变量。</li>
 * </ul>
 * 于是后端进程启动时继承到的代理地址可能早已失效，表现为"每次提问要等一两分钟"。
 * 所以提供一个「扫描本机监听端口、逐个探测出可用代理」的能力，
 * 让用户在设置页一键填入。
 *
 * <h2>本实现的实现取舍</h2>
 *
 * <p>探测手法与早期设计一致（列监听端口 → 逐个发 HTTP CONNECT 看是否返回 200），
 * 因为这是唯一可靠区分"代理端口"和"其它服务端口"的办法 ——
 * 只看端口号猜（7890/10809…）会漏掉随机端口的情况。
 *
 * <p>但<b>触发方式改了</b>：早期设计会在云端请求失败时自动扫描并自愈；
 * 这里只做成显式接口，由用户点按钮触发。理由是不让请求路径里藏着
 * "可能耗时几十秒的全端口扫描"，行为更可预期。
 */
@Service
public class ProxyDetector {

 private static final Logger log = LoggerFactory.getLogger(ProxyDetector.class);

 /**
 * 单个端口的探测超时（秒）。
 *
 * <p>比早期设计的 1 秒短一半。原因：探测是<b>串行</b>的，
 * 若本机有 40 个监听端口且都不是代理，总耗时就是 40 × 超时。
 * 未监听的端口会立即收到 RST（不等待超时），所以只有
 * "监听着但不是代理"的端口才会真正耗时 —— 那种情况不多。
 */
 private static final double PROBE_TIMEOUT_SECONDS = 0.5;

 /** 最多探测多少个端口，防止极端情况下扫描过久。 */
 private static final int MAX_PORTS_TO_PROBE = 80;

 /** netstat 子进程的超时（秒）。早期设计给 20 秒。 */
 private static final long NETSTAT_TIMEOUT_SECONDS = 20;

 /**
 * 扫描本机监听端口，找出可作 HTTP 代理访问目标主机的候选。
 *
 * @param targetHost 目标主机（从云端 base_url 解析）
 * @param targetPort 目标端口（通常 443）
 * @return 形如 {@code http://127.0.0.1:7890} 的候选地址列表
 */
 public List<String> findProxyCandidates(String targetHost, int targetPort) {
 List<String> candidates = new ArrayList<>();
 List<Integer> ports = listListeningPorts();
 int probed = 0;
 for (Integer port : ports) {
 if (probed >= MAX_PORTS_TO_PROBE) {
 log.debug("已达单次探测上限 {} 个端口，停止扫描", MAX_PORTS_TO_PROBE);
 break;
 }
 probed++;
 if (isHttpProxy(port, targetHost, targetPort)) {
 candidates.add("http://127.0.0.1:" + port);
 }
 }
 return candidates;
 }

 /**
 * 用 {@code netstat} 列出本机处于 LISTENING 状态的 TCP 端口（仅回环 / 通配地址）。
 *
 * <p>命令与解析方式沿用早期设计：{@code netstat -ano -p tcp}，
 * 取每行的本地地址列（第 2 列），从中截出端口号。
 *
 * <p><b>端口范围为什么上界要到 65535</b>：代理软件常随机取高端口
 * （实测见过 65262、57563）。早期若按 49152 之类的常见"动态端口上界"来卡，
 * 就会漏掉真正的代理端口 —— 早期设计注释专门标注了这一点。
 *
 * <p>任何失败（命令不存在、权限不足、输出格式不符）都返回空列表而不抛异常：
 * 这个功能是"锦上添花"，不该因为它不可用就让设置页报错。
 *
 * <p>⚠️ 这是本项目唯一一处调用外部命令的地方。之所以必须这么做，
 * 是因为 JDK 没有提供"列出本机全部监听端口"的 API
 * （{@code NetworkInterface} 只能枚举网卡，给不出监听端口）。
 */
 private List<Integer> listListeningPorts() {
 Set<Integer> ports = new TreeSet<>();
 Process process = null;
 try {
 ProcessBuilder pb = new ProcessBuilder("netstat", "-ano", "-p", "tcp");
 pb.redirectErrorStream(true);
 process = pb.start();

 try (BufferedReader reader = new BufferedReader(
 new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
 String line;
 while ((line = reader.readLine()) != null) {
 if (!line.toUpperCase().contains("LISTENING")) {
 continue;
 }
 String[] parts = line.trim().split("\\s+");
 if (parts.length < 2 || !parts[1].contains(":")) {
 continue;
 }
 String local = parts[1];
 int idx = local.lastIndexOf(':');
 String ip = local.substring(0, idx);
 String portText = local.substring(idx + 1);
 if (!portText.matches("\\d+")) {
 continue;
 }
 int p = Integer.parseInt(portText);
 // 只关心回环 / 通配地址上的端口：代理软件监听 127.0.0.1，
 // 而 0.0.0.0 表示监听全部网卡（也包含回环）
 boolean isLoopback = ip.equals("127.0.0.1") || ip.equals("0.0.0.0")
 || ip.equals("[::1]") || ip.equals("[::]");
 if (isLoopback && p > 1024 && p <= 65535) {
 ports.add(p);
 }
 }
 }

 if (!process.waitFor(NETSTAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
 log.warn("netstat 执行超时（{}s），放弃端口扫描", NETSTAT_TIMEOUT_SECONDS);
 process.destroyForcibly();
 }
 } catch (Exception e) {
 log.warn("无法列出本机监听端口（netstat 不可用？）：{}", e.getMessage());
 return List.of();
 } finally {
 if (process != null && process.isAlive()) {
 process.destroyForcibly();
 }
 }
 return new ArrayList<>(ports);
 }

 /**
 * 向 {@code 127.0.0.1:port} 发一个 HTTP CONNECT，判断它是否为可用代理。
 *
 * <p>为什么用 CONNECT 而不是别的探测手段：CONNECT 是 HTTP 代理隧道方法，
 * 真正的代理会回 {@code 200 Connection established}，
 * 而普通 HTTP 服务（或其它协议的服务）不会 —— 这比"端口能连上"精确得多。
 *
 * <p>未监听的端口会立即 RST（不等待超时），所以整体扫描通常很快。
 */
 private boolean isHttpProxy(int port, String targetHost, int targetPort) {
 try (Socket socket = new Socket()) {
 socket.connect(new InetSocketAddress("127.0.0.1", port),
 (int) (PROBE_TIMEOUT_SECONDS * 1000));
 socket.setSoTimeout((int) (PROBE_TIMEOUT_SECONDS * 1000));

 String request = "CONNECT " + targetHost + ":" + targetPort + " HTTP/1.1\r\n"
 + "Host: " + targetHost + ":" + targetPort + "\r\n"
 + "Proxy-Connection: keep-alive\r\n\r\n";

 OutputStream out = socket.getOutputStream();
 out.write(request.getBytes(StandardCharsets.UTF_8));
 out.flush();

 byte[] buf = new byte[64];
 int n = socket.getInputStream().read(buf);
 if (n <= 0) {
 return false;
 }
 String resp = new String(buf, 0, n, StandardCharsets.UTF_8);
 return resp.startsWith("HTTP/1.0 200") || resp.startsWith("HTTP/1.1 200");
 } catch (Exception e) {
 // 连不上 / 不是 HTTP 协议 / 超时 —— 统统按"不是代理"处理
 return false;
 }
 }

 /**
 * 从 base_url 解析出探测用的目标主机与端口。
 *
 * <p>默认值取 {@code dashscope.aliyuncs.com:443}（早期设计同款兜底）。
 * base_url 没写协议前缀时补 {@code https://}，否则 {@code URI} 拿不到 host。
 *
 * @return {@code [host, port]}
 */
 public String[] targetHostPort(String baseUrl) {
 String url = (baseUrl == null || baseUrl.isBlank()) ? "https://dashscope.aliyuncs.com" : baseUrl;
 if (!url.contains("://")) {
 url = "https://" + url;
 }
 try {
 URI u = URI.create(url);
 String host = u.getHost() == null ? "dashscope.aliyuncs.com" : u.getHost();
 int port = u.getPort() > 0 ? u.getPort() : 443;
 return new String[]{host, String.valueOf(port)};
 } catch (Exception e) {
 return new String[]{"dashscope.aliyuncs.com", "443"};
 }
 }
}
