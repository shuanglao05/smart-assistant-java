package com.ipas.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ipas.assistant.dto.CalendarDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节假日数据服务。对应 。
 *
 * <h2>为什么必须接外部接口，而不是维护一张节日表</h2>
 *
 * <p>单纯维护"哪天是什么节"（如 10-1 国庆节）只能得到节日名，
 * 无法知道<b>哪天放假、哪天要补班</b> —— 这些由国务院每年发文规定且年年不同
 * （2026 年中秋 9/25-9/27 放假、国庆 10/1-10/7 放假，而 9/20 与 10/10 补班）。
 * 手机日历里那种「休 / 班」角标就来自这份安排，所以只能按年在线拉取。
 *
 * <p>数据源 timor.tech（免费、无需登录、每年更新、含调休补班，限额 10000 次/天，
 * 而我们一年只拉一次）。
 *
 * <h2>三级降级：磁盘缓存 → 在线拉取 → 失败返回空</h2>
 *
 * <p>拉取失败<b>不报错、不抛异常</b>，而是 {@code source = "unavailable"} +
 * 空 days，前端退化为只显示传统节日名。日历页是低频辅助功能，
 * 因为网络波动弹一个红字错误并不划算。
 *
 * <h2>网络必须走 {@link CloudHttpGateway}</h2>
 *
 * <p>早期设计注释里的实测数据：<b>直连 21.6 秒、走代理 3.1 秒</b>。
 * 用户环境有代理时，自己 new 一个 HttpClient 会走系统默认（可能没配代理）
 * 导致接口卡住十几秒。复用网关的共享连接池与代理逻辑，才能拿到同样的加速。
 */
@Service
public class CalendarService {

 private static final Logger log = LoggerFactory.getLogger(CalendarService.class);

 /** 数据源地址（按年）。 */
 private static final String TIMOR_URL = "https://timor.tech/api/holiday/year/%d/";

 /**
 * 缓存有效期 7 天。
 *
 * <p>放假安排一年才变一次，TTL 本可以设得很长；但留短一点，
 * 万一数据源某次返回了错误数据，一周后能自动纠正回来。
 */
 private static final long CACHE_TTL_SECONDS = 7L * 24 * 3600;

 /**
 * 拉取超时 20 秒。
 *
 * <p>与早期设计 {@code timeout=20.0} 一致。日历是打开页面时的次要请求，
 * 不能让用户为一个角标干等三分钟。
 */
 private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(20);

 /**
 * 伪装成浏览器的 User-Agent。
 *
 * <p>部分免费公开接口会按 UA 区分客户端，Java 默认的
 * {@code Java-http-client/21} 可能被拒。早期设计显式写了 Mozilla UA，
 * 这里保持一致 —— 少了这一行就会"浏览器能打开、代码里 403"。
 */
 private static final Map<String, String> BROWSER_HEADERS =
 Map.of("User-Agent", "Mozilla/5.0");

 private final CloudHttpGateway httpGateway;
 private final RuntimeSettingsService settingsService;
 private final DataDirProvider dataDirProvider;
 private final ObjectMapper objectMapper;

 public CalendarService(CloudHttpGateway httpGateway,
 RuntimeSettingsService settingsService,
 DataDirProvider dataDirProvider,
 ObjectMapper objectMapper) {
 this.httpGateway = httpGateway;
 this.settingsService = settingsService;
 this.dataDirProvider = dataDirProvider;
 this.objectMapper = objectMapper;
 }

 // ==================================================================
 // 主流程
 // ==================================================================

 /**
 * 取某年的放假 / 调休补班安排。
 *
 * @return {@code {year, source, days}}；source 标明数据来自缓存 / 在线 / 不可用
 */
 public CalendarDtos.Holidays holidays(Long userId, int year) {
 // ① 磁盘缓存命中就直接返回（一年只拉一次，绝大多数请求走这条路）
 Map<String, CalendarDtos.HolidayDay> cached = readCache(year);
 if (cached != null) {
 return CalendarDtos.Holidays.of(year, CalendarDtos.SOURCE_CACHE, cached);
 }

 // ② 在线拉取
 Map<String, CalendarDtos.HolidayDay> days = fetch(year);
 if (days == null) {
 // ③ 失败降级：空 days + unavailable，前端不报错只少显示角标
 log.warn("拉取 {} 年节假日数据失败，返回空结果（前端将退化为只显示节日名）", year);
 return CalendarDtos.Holidays.of(year, CalendarDtos.SOURCE_UNAVAILABLE, new LinkedHashMap<>());
 }

 writeCache(year, days);
 return CalendarDtos.Holidays.of(year, CalendarDtos.SOURCE_TIMOR, days);
 }

 // ==================================================================
 // 磁盘缓存
 // ==================================================================

 /** 缓存文件路径：{@code <dataDir>/cache/holiday-{year}.json}。 */
 private Path cachePath(int year) {
 return cacheDir().resolve("holiday-" + year + ".json");
 }

 private Path cacheDir() {
 return dataDirProvider.cacheDir();
 }

 /**
 * 读缓存；不存在、损坏、年份不符、过期都视为未命中（返回 null）。
 *
 * <p><b>任何异常都吞掉</b>：缓存读不出来最多是慢一点（走在线拉取），
 * 绝不能因为一个坏文件就让接口 500。
 */
 private Map<String, CalendarDtos.HolidayDay> readCache(int year) {
 Path p = cachePath(year);
 if (!Files.exists(p)) {
 return null;
 }
 try {
 JsonNode obj = objectMapper.readTree(Files.readString(p, StandardCharsets.UTF_8));
 if (obj.path("year").asInt(-1) != year) {
 return null;
 }
 long age = System.currentTimeMillis() / 1000 - obj.path("ts").asLong(0);
 if (age < 0 || age > CACHE_TTL_SECONDS) {
 return null;
 }
 return parseDays(obj.path("days"));
 } catch (Exception e) {
 log.warn("读取节假日缓存失败（将改为在线拉取）：{}", e.getMessage());
 return null;
 }
 }

 /** 写缓存；失败只记日志（缓存写不进去不影响本次返回）。 */
 private void writeCache(int year, Map<String, CalendarDtos.HolidayDay> days) {
 try {
 Files.createDirectories(cacheDir());
 ObjectNode obj = objectMapper.createObjectNode();
 obj.put("year", year);
 obj.put("ts", System.currentTimeMillis() / 1000);
 ObjectNode daysNode = obj.putObject("days");
 days.forEach((date, d) -> {
 ObjectNode one = daysNode.putObject(date);
 one.put("off", d.off());
 one.put("work", d.work());
 one.put("name", d.name());
 one.put("wage", d.wage());
 });
 Files.writeString(cachePath(year), objectMapper.writeValueAsString(obj),
 StandardCharsets.UTF_8);
 } catch (Exception e) {
 log.warn("写入节假日缓存失败（已忽略）：{}", e.getMessage());
 }
 }

 /**
 * 解析 {@code days} 节点成 {@code 日期 → 当天属性}。
 *
 * <p>用 {@link LinkedHashMap} 保持顺序，让"同一份数据两次请求完全一致"成立。
 */
 private Map<String, CalendarDtos.HolidayDay> parseDays(JsonNode daysNode) {
 Map<String, CalendarDtos.HolidayDay> out = new LinkedHashMap<>();
 if (daysNode == null || !daysNode.isObject()) {
 return out;
 }
 var it = daysNode.fields();
 while (it.hasNext()) {
 var entry = it.next();
 JsonNode d = entry.getValue();
 out.put(entry.getKey(), new CalendarDtos.HolidayDay(
 d.path("off").asBoolean(false),
 d.path("work").asBoolean(false),
 d.path("name").asText(""),
 (int) d.path("wage").asLong(1)));
 }
 return out;
 }

 // ==================================================================
 // 在线拉取与归一化
 // ==================================================================

 /**
 * 在线拉取某年数据；任何异常都返回 null（由上层降级）。
 *
 * <p>走 {@link CloudHttpGateway} 而不是自带 client，
 * 是为了复用共享连接池与代理配置（见类注释里的实测数据）。
 */
 private Map<String, CalendarDtos.HolidayDay> fetch(int year) {
 try {
 // 传 0L：代理 / 直连是【进程级】策略，不按用户区分。
 // 这与早期设计 _cloud_http_client() 的行为一致 —— 它也是模块级共享 client，
 // 只看环境变量里的代理，不看当前登录用户。
 var settings = settingsService.load(0L);
 String url = String.format(TIMOR_URL, year);
 String body = httpGateway.get(url, null, FETCH_TIMEOUT, settings, BROWSER_HEADERS);
 JsonNode data = objectMapper.readTree(body);
 // timor 用 code=0 表示成功；非 0 是"接口正常返回但没数据"
 if (!data.isObject() || data.path("code").asInt(-1) != 0) {
 return null;
 }
 return normalize(data.path("holiday"));
 } catch (Exception e) {
 log.warn("在线拉取 {} 年节假日失败：{}", year, e.getMessage());
 return null;
 }
 }

 /**
 * 把 timor 的 {@code {"10-01": {...}}} 归一化成
 * {@code {"2026-10-01": {off, work, name, wage}}}。
 *
 * <p>timor 字段语义：
 * <ul>
 * <li>{@code holiday=true} → 放假（off，显示「休」）；</li>
 * <li>{@code holiday=false} → 调休补班（work，显示「班」）；</li>
 * <li>{@code name} → 节日名或「某某补班」；</li>
 * <li>{@code wage} → 薪资倍数。</li>
 * </ul>
 *
 * <p>用 {@link LinkedHashMap} 保持数据源的返回顺序，
 * 让"同一份数据两次请求完全一致"成立，也方便比对排查。
 */
 private Map<String, CalendarDtos.HolidayDay> normalize(JsonNode raw) {
 Map<String, CalendarDtos.HolidayDay> out = new LinkedHashMap<>();
 if (raw == null || !raw.isObject()) {
 return out;
 }
 var it = raw.fields();
 while (it.hasNext()) {
 var entry = it.next();
 JsonNode info = entry.getValue();
 if (!info.isObject()) {
 continue;
 }
 String date = info.path("date").asText("");
 if (date.isEmpty()) {
 continue;
 }
 boolean isOff = info.path("holiday").asBoolean(false);
 String name = info.path("name").asText("");
 int wage = (int) info.path("wage").asLong(1);
 out.put(date, new CalendarDtos.HolidayDay(isOff, !isOff, name, wage));
 }
 return out;
 }
}
