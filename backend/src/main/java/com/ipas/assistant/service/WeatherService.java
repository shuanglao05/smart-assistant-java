package com.ipas.assistant.service;

import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.dto.WeatherDtos;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 天气查询。对应 里的
 * {@code get_weather()} / {@code get_weather_forecast()} / {@code _amap_weather()} /
 * {@code _amap_forecast()} / {@code _openweather_weather()}。
 *
 * <h2>为什么天气逻辑放在 Service 而不是 Agent 工具里</h2>
 *
 * <p>早期设计里天气是「Agent 工具」（{@code @tool} 装饰的函数），
 * 但 {@code /api/weather} 这个给前端天气卡片用的 REST 接口
 * 也在<b>直接调那两个工具函数</b>。
 *
 * <p>本实现把它们抽成 Service，两条入口（REST 接口 + 第三阶段的 Agent 工具）
 * 都调它。这样「天气卡片显示的」和「AI 回答的」天气保证是同一套逻辑与文案 ——
 * 若各写一份，很容易出现"卡片说要下雨、AI 说晴天"这种自相矛盾。
 *
 * <h2>数据源优先级</h2>
 *
 * <p>高德优先（中文城市名、国内访问稳定、免费额度够用），
 * 没配高德 Key 才回退 OpenWeatherMap（英文城市名检索）。
 * 两个都没配时返回一句提示文本（<b>不是抛异常</b>）——
 * 因为早期设计也是把提示当正常结果返回，前端直接把它显示在卡片里，
 * 用户能立刻明白"要配 Key"。若抛 4xx，前端只能显示一个笼统的错误。
 */
@Service
public class WeatherService {

 private static final Duration TIMEOUT = Duration.ofSeconds(10);
 private static final String AMAP_URL = "https://restapi.amap.com/v3/weather/weatherInfo";
 private static final String OPENWEATHER_URL = "https://api.openweathermap.org/data/2.5/weather";

 private static final String[] WEEKDAY_CN = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

 /**
 * 中文城市名 → 高德 adcode。
 *
 * <p>用 adcode 查询比用城市名可靠得多：高德对「北京」这种简称有时能解析、
 * 有时返回空，而 adcode 是唯一的行政编码，不存在歧义。
 */
 private static final Map<String, String> CITY_ADCODE = Map.ofEntries(
 Map.entry("北京", "110000"), Map.entry("上海", "310000"), Map.entry("广州", "440100"),
 Map.entry("深圳", "440300"), Map.entry("杭州", "330100"), Map.entry("南京", "320100"),
 Map.entry("苏州", "320500"), Map.entry("成都", "510100"), Map.entry("重庆", "500000"),
 Map.entry("武汉", "420100"), Map.entry("西安", "610100"), Map.entry("天津", "120000"),
 Map.entry("长沙", "430100"), Map.entry("郑州", "410100"), Map.entry("青岛", "370200"),
 Map.entry("大连", "210200"), Map.entry("厦门", "350200"), Map.entry("福州", "350100"),
 Map.entry("合肥", "340100"), Map.entry("济南", "370100"), Map.entry("沈阳", "210100"),
 Map.entry("哈尔滨", "230100"), Map.entry("长春", "220100"), Map.entry("石家庄", "130100"),
 Map.entry("太原", "140100"), Map.entry("昆明", "530100"), Map.entry("贵阳", "520100"),
 Map.entry("南宁", "450100"), Map.entry("桂林", "450300"), Map.entry("海口", "460100"),
 Map.entry("三亚", "460200"), Map.entry("兰州", "620100"), Map.entry("西宁", "630100"),
 Map.entry("银川", "640100"), Map.entry("乌鲁木齐", "650100"), Map.entry("拉萨", "540100"),
 Map.entry("呼和浩特", "150100"), Map.entry("南昌", "360100"), Map.entry("无锡", "320200"),
 Map.entry("宁波", "330200"), Map.entry("温州", "330300"), Map.entry("东莞", "441900"),
 Map.entry("佛山", "440600"), Map.entry("珠海", "440400"), Map.entry("常州", "320400"),
 Map.entry("徐州", "320300"), Map.entry("烟台", "370600"), Map.entry("洛阳", "410300"),
 Map.entry("香港", "810000"), Map.entry("澳门", "820000"));

 /**
 * 中文城市名 → 英文名（OpenWeatherMap 用英文检索更可靠）。
 *
 * <p>高德用 adcode，这个表是给备用数据源用的。
 */
 private static final Map<String, String> CITY_ZH_EN = Map.ofEntries(
 Map.entry("北京", "Beijing"), Map.entry("上海", "Shanghai"), Map.entry("广州", "Guangzhou"),
 Map.entry("深圳", "Shenzhen"), Map.entry("杭州", "Hangzhou"), Map.entry("南京", "Nanjing"),
 Map.entry("苏州", "Suzhou"), Map.entry("成都", "Chengdu"), Map.entry("重庆", "Chongqing"),
 Map.entry("武汉", "Wuhan"), Map.entry("西安", "Xi'an"), Map.entry("天津", "Tianjin"),
 Map.entry("长沙", "Changsha"), Map.entry("郑州", "Zhengzhou"), Map.entry("青岛", "Qingdao"),
 Map.entry("大连", "Dalian"), Map.entry("厦门", "Xiamen"), Map.entry("福州", "Fuzhou"),
 Map.entry("合肥", "Hefei"), Map.entry("济南", "Jinan"), Map.entry("沈阳", "Shenyang"),
 Map.entry("哈尔滨", "Harbin"), Map.entry("石家庄", "Shijiazhuang"), Map.entry("太原", "Taiyuan"),
 Map.entry("昆明", "Kunming"), Map.entry("贵阳", "Guiyang"), Map.entry("南宁", "Nanning"),
 Map.entry("桂林", "Guilin"), Map.entry("海口", "Haikou"), Map.entry("三亚", "Sanya"),
 Map.entry("兰州", "Lanzhou"), Map.entry("西宁", "Xining"), Map.entry("银川", "Yinchuan"),
 Map.entry("乌鲁木齐", "Urumqi"), Map.entry("拉萨", "Lhasa"),
 Map.entry("呼和浩特", "Hohhot"), Map.entry("无锡", "Wuxi"), Map.entry("宁波", "Ningbo"),
 Map.entry("温州", "Wenzhou"), Map.entry("东莞", "Dongguan"), Map.entry("佛山", "Foshan"),
 Map.entry("珠海", "Zhuhai"), Map.entry("香港", "Hong Kong"),
 Map.entry("澳门", "Macao"), Map.entry("台北", "Taipei"));

 private final AppProperties properties;

 /**
 * 天气查询用的 HTTP 客户端。
 *
 * <p>与云端模型那套<b>分开</b>：天气是公网普通 HTTPS，不需要代理自愈、
 * 也不需要 180 秒长超时。用独立的短超时客户端更合适 ——
 * 前端天气卡片点一下要立刻有反馈，不能跟着代理配置一起变慢。
 */
 private final HttpClient http = HttpClient.newBuilder()
 .connectTimeout(Duration.ofSeconds(8))
 .followRedirects(HttpClient.Redirect.NORMAL)
 .build();

 public WeatherService(AppProperties properties) {
 this.properties = properties;
 }

 /**
 * 天气查询入口（对应 {@code GET /api/weather}）。
 *
 * @param city 城市名（中文或英文）
 * @param mode {@code now} 实况 / {@code forecast} 预报
 * @param days 预报天数 1~4（仅 forecast 生效）
 */
 @Transactional(readOnly = true)
 public WeatherDtos.WeatherResult query(String city, String mode, int days) {
 String result = "forecast".equals(mode) ? forecast(city, days) : current(city);
 return new WeatherDtos.WeatherResult(city, mode, result);
 }

 /**
 * 实时天气。对应 {@code get_weather}。
 *
 * <p>返回的是<b>可直接展示的文本</b>而不是结构化数据 —— 与早期设计一致，
 * 因为同一段文本既给前端卡片显示、也给模型读，保持单一来源。
 */
 public String current(String city) {
 String amapKey = properties.tools().amapApiKey();
 if (amapKey != null && !amapKey.isBlank()) {
 return amapWeather(city, amapKey);
 }
 String owKey = properties.tools().openweathermapApiKey();
 if (owKey != null && !owKey.isBlank()) {
 return openWeather(city, owKey);
 }
 return "未配置天气 API Key，请在 application-local.yml 中设置 app.tools.amap-api-key"
 + "（推荐，高德）或 app.tools.openweathermap-api-key";
 }

 /** 未来几天预报。对应 {@code get_weather_forecast}。 */
 public String forecast(String city, int days) {
 String amapKey = properties.tools().amapApiKey();
 if (amapKey == null || amapKey.isBlank()) {
 return "未配置天气 API Key，天气预报需要配置高德 app.tools.amap-api-key";
 }
 return amapForecast(city, days, amapKey);
 }

 // ==================================================================
 // 高德
 // ==================================================================

 private String amapWeather(String city, String key) {
 String adcode = resolveAdcode(city);
 JsonNode data = getJson(AMAP_URL + "?key=" + enc(key) + "&city=" + enc(adcode) + "&extensions=base");
 if (data == null) {
 return "天气查询失败";
 }

 if (!"1".equals(data.path("status").asText()) || !data.path("lives").isArray()
 || data.path("lives").isEmpty()) {
 // 高德在参数不合法时偶尔也返回 info=OK，把它显示给用户会造成误导，
 // 所以只在 info 有意义时才附在提示里
 String info = data.path("info").asText("");
 String detail = (info.isBlank() || "OK".equals(info)) ? "" : "（" + info + "）";
 return "未查询到「" + city + "」的天气" + detail + "，请确认城市名是否正确";
 }

 JsonNode live = data.path("lives").get(0);
 return live.path("city").asText(city)
 + " 当前 " + text(live, "temperature") + "°C，"
 + text(live, "weather") + "，"
 + "湿度 " + text(live, "humidity") + "%，"
 + text(live, "winddirection") + "风 " + text(live, "windpower") + "级";
 }

 private String amapForecast(String city, int days, String key) {
 String adcode = resolveAdcode(city);
 JsonNode data = getJson(AMAP_URL + "?key=" + enc(key) + "&city=" + enc(adcode) + "&extensions=all");
 if (data == null) {
 return "天气预报查询失败";
 }

 if (!"1".equals(data.path("status").asText()) || !data.path("forecasts").isArray()
 || data.path("forecasts").isEmpty()) {
 String info = data.path("info").asText("");
 String detail = (info.isBlank() || "OK".equals(info)) ? "" : "（" + info + "）";
 return "未查询到「" + city + "」的天气预报" + detail + "，请确认城市名是否正确";
 }

 // 免费接口最多给 4 天（含今天），超出部分做了夹取而不是报错
 int n = Math.max(1, Math.min(days, 4));
 JsonNode first = data.path("forecasts").get(0);
 String cityName = first.path("city").asText(city);
 JsonNode casts = first.path("casts");

 StringBuilder sb = new StringBuilder();
 int count = Math.min(casts.size(), n);
 sb.append(cityName).append("未来 ").append(count).append(" 天天气（白天/夜间）：");
 for (int i = 0; i < count; i++) {
 JsonNode c = casts.get(i);
 String date = c.path("date").asText("");
 String label;
 if (i == 0) {
 label = "今天";
 } else if (i == 1) {
 label = "明天";
 } else {
 // "2026-09-18" -> "09/18 周五"
 String md = date.length() >= 10 ? date.substring(5).replace('-', '/') : date;
 label = (md + " " + weekdayCn(date)).trim();
 }
 sb.append("\n- ").append(label).append("：白天 ")
 .append(c.path("dayweather").asText()).append(" ")
 .append(c.path("daytemp").asText()).append("°C / 夜间 ")
 .append(c.path("nightweather").asText()).append(" ")
 .append(c.path("nighttemp").asText()).append("°C，")
 .append(c.path("daywind").asText()).append("风 ")
 .append(c.path("daypower").asText()).append("级");
 }
 return sb.toString();
 }

 /**
 * 把城市名 / 英文名 / adcode 统一解析成 adcode。
 *
 * <p>表里没有的输入原样返回，交给高德按名称解析 ——
 * 这样县市级城市（表里没收录）也有机会查到，而不是直接失败。
 */
 private String resolveAdcode(String city) {
 String c = city == null ? "" : city.trim();
 if (c.matches("\\d+")) {
 return c;
 }
 String direct = CITY_ADCODE.get(c);
 if (direct != null) {
 return direct;
 }
 String lower = c.toLowerCase();
 for (Map.Entry<String, String> e : CITY_ZH_EN.entrySet()) {
 if (e.getValue().toLowerCase().equals(lower)) {
 return CITY_ADCODE.getOrDefault(e.getKey(), c);
 }
 }
 return c;
 }

 /**
 * 把日期字符串转成「周几」。
 *
 * <p>解析失败返回空串而不是抛异常 —— 日期格式由上游决定，
 * 万一变了不该让整个预报接口挂掉。
 */
 private static String weekdayCn(String date) {
 try {
 LocalDate d = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
 return WEEKDAY_CN[d.getDayOfWeek().getValue() - 1];
 } catch (Exception e) {
 return "";
 }
 }

 // ==================================================================
 // OpenWeatherMap（备用）
 // ==================================================================

 private String openWeather(String city, String key) {
 String cityEn = CITY_ZH_EN.getOrDefault(city == null ? "" : city.trim(), city);
 JsonNode data = getJson(OPENWEATHER_URL + "?q=" + enc(cityEn)
 + "&appid=" + enc(key) + "&units=metric&lang=zh_cn");
 if (data == null) {
 return "天气查询失败";
 }
 if (data.has("main")) {
 return cityEn + " 当前 " + data.path("main").path("temp").asText() + "°C，"
 + data.path("weather").path(0).path("description").asText() + "，"
 + "湿度 " + data.path("main").path("humidity").asText() + "%";
 }
 return "未找到城市 '" + city + "' 的信息，请确认城市名是否正确";
 }

 // ==================================================================
 // HTTP 辅助
 // ==================================================================

 /**
 * 发 GET 并解析 JSON。任何失败都返回 {@code null}，
 * 由调用方转成给用户看的提示文本。
 *
 * <p>为什么不抛异常：天气是"锦上添花"的功能，第三方接口挂了
 * 不该让整个请求 500。返回一句可读的失败提示，前端照常显示，
 * 用户至少知道是网络/配置问题而不是自己操作错了。
 */
 private JsonNode getJson(String url) {
 try {
 HttpRequest request = HttpRequest.newBuilder(URI.create(url))
 .timeout(TIMEOUT)
 .header("Accept", "application/json")
 .GET()
 .build();
 HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
 if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
 return null;
 }
 var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
 return mapper.readTree(resp.body());
 } catch (Exception e) {
 return null;
 }
 }

 private static String enc(String s) {
 return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
 }

 private static String text(JsonNode node, String field) {
 String v = node.path(field).asText("");
 return v.isBlank() ? "?" : v;
 }
}
