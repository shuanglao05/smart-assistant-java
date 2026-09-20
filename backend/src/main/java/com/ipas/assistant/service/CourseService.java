package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.dto.CourseDtos;
import com.ipas.assistant.entity.Course;
import com.ipas.assistant.entity.LlmProvider;
import com.ipas.assistant.repository.CourseRepository;
import com.ipas.assistant.repository.LlmProviderRepository;
import com.ipas.assistant.service.llm.ChatModelFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeType;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 课表业务逻辑。对应 。
 */
@Service
public class CourseService {

 /**
 * 课表解析提示词。与原文 {@code _IMPORT_PROMPT} <b>逐字一致</b> ——
 * 这种提示词是靠实际效果调出来的，改动措辞会让解析结果漂移，不要"顺手优化"。
 */
 private static final String IMPORT_PROMPT = (
 "你是课表解析助手。下面是从网页或文本中提取的内容，请解析出所有课程，"
 + "只输出一个 JSON 数组，不要任何多余文字。每个元素的字段：\n"
 + "{\"name\": \"课程名\", \"teacher\": \"老师或空\", \"location\": \"地点或空\", "
 + "\"weekday\": 1到7的整数(1=周一), \"start_section\": 起始节次整数, "
 + "\"end_section\": 结束节次整数, \"weeks\": \"周次字符串(如 1-16 / 1-16单周 / 1-16双周；每周都上则空字符串)\"}\n"
 + "节次按每天第几节编号（第1节=1，依次递增）。内容如下：\n\n");

 /** 喂给模型的正文上限（原文 {@code raw[:8000]}）：防止超长页面撑爆上下文。 */
 private static final int MAX_RAW_CHARS = 8000;

 /** 抓网页的超时（原文 timeout=20）。 */
 private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(20);

 /** 抓网页时伪装成浏览器（原文同款，不少站点会挡掉非浏览器的 UA）。 */
 private static final Map<String, String> BROWSER_HEADERS =
 Map.of("User-Agent", "Mozilla/5.0");

 private static final Pattern SCRIPT_STYLE =
 Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
 private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");
 private static final Pattern SPACES = Pattern.compile("[ \\t\\r\\f\\v]+");
 private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n+");
 /** 模型常把 JSON 包在代码块里，先剥掉 markdown 围栏再找数组。 */
 private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?");

 private final CourseRepository courseRepository;
 private final ChatModelFactory chatModelFactory;
 private final RuntimeSettingsService settingsService;
 private final AppProperties properties;
 private final CloudHttpGateway httpGateway;
 private final ObjectMapper objectMapper;
 private final LlmProviderRepository providerRepository;

 public CourseService(CourseRepository courseRepository,
 ChatModelFactory chatModelFactory,
 RuntimeSettingsService settingsService,
 AppProperties properties,
 CloudHttpGateway httpGateway,
 ObjectMapper objectMapper,
 LlmProviderRepository providerRepository) {
 this.courseRepository = courseRepository;
 this.chatModelFactory = chatModelFactory;
 this.settingsService = settingsService;
 this.properties = properties;
 this.httpGateway = httpGateway;
 this.objectMapper = objectMapper;
 this.providerRepository = providerRepository;
 }

 /** 列表：先按星期，再按起始节次（前端网格的渲染顺序）。 */
 @Transactional(readOnly = true)
 public List<CourseDtos.Out> list(Long userId) {
 return courseRepository.findByUserIdOrderByWeekdayAscStartSectionAsc(userId)
 .stream()
 .map(CourseDtos.Out::from)
 .toList();
 }

 /** 新建课程。创建时就做一次节次校正，避免落库倒挂数据。 */
 @Transactional
 public CourseDtos.Out create(Long userId, CourseDtos.Create payload) {
 int start = payload.startSection();
 int end = payload.endSection();

 Course row = new Course();
 row.setUserId(userId);
 row.setName(payload.name().strip());
 row.setTeacher(payload.teacher());
 row.setLocation(payload.location());
 row.setWeekday(payload.weekday());
 // 校正后再写入（见 normalizeSections 的说明）
 int[] normalized = normalizeSections(start, end);
 row.setStartSection(normalized[0]);
 row.setEndSection(normalized[1]);
 row.setWeeks(payload.weeks());
 row.setColor(payload.color());
 return CourseDtos.Out.from(courseRepository.save(row));
 }

 /**
 * 更新课程。
 *
 * <p><b>关键点：节次校正必须在「所有字段都改完之后」再执行一次。</b>
 * <br>因为前端可能<b>只改其中一个</b>字段 —— 比如把结束节次从 4 改成 2。
 * 此时只校验传入值是不够的（2 本身合法），必须拿"改完之后的最终状态"
 * 去比较 start 与 end，才能发现倒挂。早期设计在最后一行做
 * {@code row.start_section, row.end_section = _norm_sections(...)}，正是这个原因。
 */
 @Transactional
 public CourseDtos.Out update(Long userId, Long courseId, CourseDtos.Update payload) {
 Course row = requireOwned(userId, courseId);

 if (payload.name() != null) {
 row.setName(payload.name().strip());
 }
 if (payload.teacher() != null) {
 row.setTeacher(payload.teacher());
 }
 if (payload.location() != null) {
 row.setLocation(payload.location());
 }
 if (payload.weekday() != null) {
 row.setWeekday(payload.weekday());
 }
 if (payload.startSection() != null) {
 row.setStartSection(payload.startSection());
 }
 if (payload.endSection() != null) {
 row.setEndSection(payload.endSection());
 }
 if (payload.weeks() != null) {
 row.setWeeks(payload.weeks());
 }
 if (payload.color() != null) {
 row.setColor(payload.color());
 }

 // 全部改完后统一校正（原因见方法注释）
 int[] normalized = normalizeSections(row.getStartSection(), row.getEndSection());
 row.setStartSection(normalized[0]);
 row.setEndSection(normalized[1]);

 return CourseDtos.Out.from(row);
 }

 /** 删除课程。 */
 @Transactional
 public void delete(Long userId, Long courseId) {
 courseRepository.delete(requireOwned(userId, courseId));
 }

 /**
 * 按「与对话（AgentFactory）一致的优先级」解析课表导入要用的模型凭据。
 *
 * <p><b>修复「对话能用云端、导入用不了」</b>：早期实现里课表导入只读
 * {@code app_settings}（旧的单套云端配置），而对话按会话 {@code provider_id} 去
 * {@code llm_providers} 查。用户的云端模型配在 {@code llm_providers}，于是导入拿到空凭据报错。
 * 这里对齐对话的解析顺序：
 * <ol>
 * <li>若请求带了 {@code providerId}，优先查 {@code llm_providers}（按用户隔离）；</li>
 * <li>否则若未指定 provider 且 app_settings 里也没有云端 Key，兜底取第一个已接入平台；</li>
 * <li>都没有则回落 app_settings（RuntimeSettingsService.load）。</li>
 * </ol>
 *
 * @return 解析后的 (provider, model, apiKey, baseUrl)；model 为空时按 provider 取默认
 */
 private ResolvedModel resolveModel(Long userId,
 String provider,
 String model,
 Long providerId,
 RuntimeSettingsService.LlmSettings settings) {
 String p = (provider == null ? "" : provider.trim().toLowerCase());
 String m = model;
 LlmProvider providerRow = null;
 if (providerId != null) {
 // 优先：请求明确指定的平台（与对话里 conv.getProviderId() 同款来源）
 providerRow = providerRepository.findByIdAndUserId(providerId, userId).orElse(null);
 }
 if (providerRow == null && !"ollama".equalsIgnoreCase(p) && settings.apiKey().isBlank()) {
 // 兜底：旧会话/旧请求没带 providerId，且 app_settings 也没云端 Key → 用第一个已接入平台
 providerRow = providerRepository.findFirstByUserIdOrderByIdAsc(userId).orElse(null);
 }
 if (providerRow != null) {
 // 命中 llm_providers：provider 统一为 cloud，model 缺省用该平台的默认模型
 p = "cloud";
 if (m == null || m.isBlank()) {
 m = providerRow.getModel();
 }
 return new ResolvedModel(p, m, providerRow.getApiKey(), providerRow.getBaseUrl());
 }
 // 未命中 llm_providers：回落 app_settings（与早期实现一致）
 if (m == null || m.isBlank()) {
 m = "ollama".equalsIgnoreCase(p) ? properties.llm().ollama().model() : settings.model();
 }
 return new ResolvedModel(p, m, settings.apiKey(), settings.baseUrl());
 }

 /** 解析结果载体：provider / model / apiKey / baseUrl。 */
 private record ResolvedModel(String provider, String model, String apiKey, String baseUrl) {
 }

 /**
 * 智能导入（网址 / 文本 / 截图 → 课程草稿）。对应 {@code import_courses}。
 *
 * <p><b>不落库</b>：只返回解析出的草稿，前端预览、可逐条编辑，
 * 确认后再逐条调创建接口。这样解析错了也只影响一条草稿，不会污染课表。
 *
 * <p>三个分支照抄原文，不要简化：
 * <ol>
 * <li><b>图片分支</b>：走云端视觉模型（多模态）。未配置云端凭据时
 * <b>先返回 400</b>，而不是让请求发出去超时；</li>
 * <li><b>网页分支</b>：抓取失败（需要登录 / 站点禁止抓取）返回 400 并说明原因；</li>
 * <li><b>文本分支</b>：直接用粘贴的内容。</li>
 * </ol>
 */
 @Transactional(readOnly = true)
 public CourseDtos.ImportResponse importCourses(Long userId, CourseDtos.ImportRequest payload) {
 boolean hasImage = payload.image() != null && !payload.image().isBlank();
 boolean hasText = payload.text() != null && !payload.text().isBlank();
 boolean hasUrl = payload.url() != null && !payload.url().isBlank();

 var settings = settingsService.load(userId);

 // ① 图片识别：交给云端视觉模型（多模态）
 if (hasImage) {
 // 与对话一致：优先按 providerId 查 llm_providers，回落 app_settings
 // 原文：model 缺省用 config.VISION_MODEL（视觉模型，不是对话模型）
 String visionModel = (payload.model() == null || payload.model().isBlank())
 ? properties.llm().visionModel()
 : payload.model();
 ResolvedModel rm = resolveModel(userId, "cloud", visionModel, payload.providerId(), settings);
 if (rm.apiKey() == null || rm.apiKey().isBlank()
 || rm.baseUrl() == null || rm.baseUrl().isBlank()) {
 throw ApiException.badRequest(
 "图片识别需要云端视觉模型，请在设置里接入 API 或配置云端凭据");
 }

 // 构造模型放在 try 外：凭据缺失是 400 业务错误，不能被包装成 500
 ChatModel llm = chatModelFactory.create(rm.provider(), rm.model(),
 rm.apiKey(), rm.baseUrl(), null, null, settings);
 UserMessage msg = UserMessage.builder()
 .text(IMPORT_PROMPT + "（这是一张课表截图，请识别其中的课程）")
 .media(buildImageMedia(payload.image().strip()))
 .build();
 String out;
 try {
 out = llm.call(msg);
 } catch (Exception e) {
 throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
 "图片识别失败：" + e.getMessage());
 }
 List<CourseDtos.ParsedCourse> courses = extractJsonList(out);
 return new CourseDtos.ImportResponse(courses.size(), courses);
 }

 // ② 网址 / 文本
 String raw = "";
 if (hasText) {
 raw = payload.text().strip();
 } else if (hasUrl) {
 try {
 String html = httpGateway.get(payload.url().strip(), null,
 FETCH_TIMEOUT, settings, BROWSER_HEADERS);
 raw = htmlToText(html);
 } catch (Exception e) {
 throw ApiException.badRequest(
 "抓取网页失败（可能需要登录或站点不允许抓取）：" + e.getMessage());
 }
 } else {
 throw ApiException.badRequest("请提供课表网址，或直接粘贴课表内容");
 }

 if (raw.isBlank()) {
 throw ApiException.badRequest("没有解析到内容（页面可能是空壳 / 需要登录）");
 }
 if (raw.length() > MAX_RAW_CHARS) {
 raw = raw.substring(0, MAX_RAW_CHARS);
 }

 String provider = (payload.provider() == null || payload.provider().isBlank())
 ? properties.llm().provider()
 : payload.provider();
 // 与对话一致：优先按 providerId 查 llm_providers，回落 app_settings
 ResolvedModel rm = resolveModel(userId, provider, payload.model(), payload.providerId(), settings);

 // 构造模型放在 try 外：凭据缺失是 400 业务错误，不能被包装成 500
 ChatModel llm = chatModelFactory.create(rm.provider(), rm.model(),
 rm.apiKey(), rm.baseUrl(), null, null, settings);
 String out;
 try {
 out = llm.call(IMPORT_PROMPT + raw);
 } catch (Exception e) {
 throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
 "AI 解析失败：" + e.getMessage());
 }

 List<CourseDtos.ParsedCourse> courses = extractJsonList(out);
 return new CourseDtos.ImportResponse(courses.size(), courses);
 }

 // ==================================================================
 // 解析辅助（均照抄原文逻辑）
 // ==================================================================

 /**
 * 把前端传来的图片 data URL 包成 Spring AI 的 {@link Media}。
 *
 * <p>前端用 canvas 把截图转成
 * {@code data:image/jpeg;base64,....}（见 {@code CourseImport.tsx}）。
 *
 * <p><b>为什么先试 URI、失败再退回字符串</b>：data URL 里的 base64 可能含
 * {@code URI.create} 不接受的字��，直接构造会抛
 * {@code IllegalArgumentException}。退回原始字符串同样能被模型适配器识别，
 * 所以这里两级兜底，绝不因为一个格式细节让整张截图识别失败。
 */
 private static Media buildImageMedia(String dataUrl) {
 MimeType mime = org.springframework.util.MimeTypeUtils.IMAGE_JPEG;
 String lower = dataUrl.toLowerCase();
 if (lower.startsWith("data:image/png")) {
 mime = org.springframework.util.MimeTypeUtils.IMAGE_PNG;
 } else if (lower.startsWith("data:image/gif")) {
 mime = org.springframework.util.MimeTypeUtils.IMAGE_GIF;
 } else if (lower.startsWith("data:image/webp")) {
 mime = MimeType.valueOf("image/webp");
 }
 try {
 return Media.builder().mimeType(mime).data(URI.create(dataUrl)).build();
 } catch (IllegalArgumentException e) {
 return Media.builder().mimeType(mime).data(dataUrl).build();
 }
 }

 /** 极简 HTML 去标签：只留可见文本，够喂给模型即可（对应 {@code _html_to_text}）。 */
 private static String htmlToText(String html) {
 String text = SCRIPT_STYLE.matcher(html).replaceAll(" ");
 text = ANY_TAG.matcher(text).replaceAll(" ");
 text = text.replace("&nbsp;", " ")
 .replace("&amp;", "&")
 .replace("&lt;", "<")
 .replace("&gt;", ">");
 text = SPACES.matcher(text).replaceAll(" ");
 text = BLANK_LINES.matcher(text).replaceAll("\n");
 return text.strip();
 }

 /**
 * 从模型回复里抠出 JSON 数组并做基本校验 / 归一化（对应 {@code _extract_json_list}）。
 *
 * <p><b>为什么不直接 {@code readValue}</b>：模型几乎不可能只输出纯净 JSON ——
 * 常见的有 markdown 围栏、前后各一句客套话。所以先找第一个 {@code [} 与最后一个
 * {@code ]} 之间的内容再解析，解析失败返回空列表（前端会提示"没解析出课程"）。
 */
 private List<CourseDtos.ParsedCourse> extractJsonList(String text) {
 if (text == null || text.isBlank()) {
 return List.of();
 }
 String t = CODE_FENCE.matcher(text).replaceAll("").strip();
 int i = t.indexOf('[');
 int j = t.lastIndexOf(']');
 if (i < 0 || j < i) {
 return List.of();
 }
 JsonNode data;
 try {
 data = objectMapper.readTree(t.substring(i, j + 1));
 } catch (Exception e) {
 return List.of();
 }
 if (data == null || !data.isArray()) {
 return List.of();
 }

 List<CourseDtos.ParsedCourse> out = new ArrayList<>();
 for (JsonNode it : data) {
 if (!it.isObject()) {
 continue;
 }
 String name = it.path("name").asText("").strip();
 if (name.isEmpty()) {
 continue; // 没有课程名的条目无法入库，直接丢弃
 }
 int weekday;
 int start;
 int end;
 try {
 weekday = Integer.parseInt(it.path("weekday").asText("1").trim());
 start = Integer.parseInt(it.path("start_section").asText("1").trim());
 end = Integer.parseInt(
 it.path("end_section").asText(String.valueOf(start)).trim());
 } catch (NumberFormatException e) {
 continue;
 }
 out.add(new CourseDtos.ParsedCourse(
 name.length() > 100 ? name.substring(0, 100) : name,
 trimmedOrNull(it.path("teacher").asText(null), 100),
 trimmedOrNull(it.path("location").asText(null), 100),
 Math.min(7, Math.max(1, weekday)),
 Math.max(1, Math.min(start, end)),
 Math.max(1, Math.max(start, end)),
 trimmedOrNull(it.path("weeks").asText(null), 50)));
 }
 return out;
 }

 /** 取字段值并截断；空值 / 空串统一返回 null（与原接口的 null 语义一致）。 */
 private static String trimmedOrNull(String value, int maxLen) {
 if (value == null) {
 return null;
 }
 String v = value.strip();
 if (v.isEmpty()) {
 return null;
 }
 return v.length() > maxLen ? v.substring(0, maxLen) : v;
 }

 /**
 * 保证 start &lt;= end，返回 {@code [start, end]}。
 *
 * <p>为什么是"交换"而不是"报错"：在课表网格上填课程时，
 * 用户的操作顺序经常是先点结束节次再点起始节次（尤其是从上往下拖选）。
 * 这种情况直接判错会让人莫名其妙，交换语义更贴合直觉。
 * 早期设计 {@code _norm_sections()} 就是这个行为。
 */
 private int[] normalizeSections(int start, int end) {
 return start <= end ? new int[]{start, end} : new int[]{end, start};
 }

 private Course requireOwned(Long userId, Long courseId) {
 return courseRepository.findByIdAndUserId(courseId, userId)
 .orElseThrow(() -> ApiException.notFound("课程不存在"));
 }
}
