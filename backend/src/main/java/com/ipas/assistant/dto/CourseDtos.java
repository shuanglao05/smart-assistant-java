package com.ipas.assistant.dto;

import com.ipas.assistant.entity.Course;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 课表模块的请求 / 响应体。对应 的
 * {@code CourseCreate} / {@code CourseUpdate} / {@code CourseOut} / {@code CourseImportRequest}。
 */
public final class CourseDtos {

 private CourseDtos() {
 }

 /**
 * 创建课程。对应 {@code CourseCreate}，校验范围逐项对齐：
 * {@code weekday} 1~7、{@code startSection}/{@code endSection} >= 1。
 *
 * <p>注意<b>没有校验 {@code startSection <= endSection}</b> ——
 * 早期设计也没校验，而是在服务层用交换的方式"纠正"倒挂的输入
 * （用户先填结束节次再填起始节次是很自然的操作顺序，报错反而不友好）。
 */
 public record Create(
 @NotBlank(message = "课程名称不能为空")
 @Size(max = 100, message = "课程名称最长 100 个字符")
 String name,

 @Size(max = 100, message = "老师最长 100 个字符")
 String teacher,

 @Size(max = 100, message = "地点最长 100 个字符")
 String location,

 @NotNull(message = "星期不能为空")
 @Min(value = 1, message = "星期取值 1~7（1=周一）")
 @Max(value = 7, message = "星期取值 1~7（1=周一）")
 Integer weekday,

 @NotNull(message = "起始节次不能为空")
 @Min(value = 1, message = "起始节次至少为 1")
 Integer startSection,

 @NotNull(message = "结束节次不能为空")
 @Min(value = 1, message = "结束节次至少为 1")
 Integer endSection,

 @Size(max = 50, message = "周次最长 50 个字符")
 String weeks,

 String color
 ) {
 }

 /** 更新课程。所有字段可选，null = 不改。 */
 public record Update(
 @Size(max = 100, message = "课程名称最长 100 个字符")
 String name,

 String teacher,

 String location,

 @Min(value = 1, message = "星期取值 1~7（1=周一）")
 @Max(value = 7, message = "星期取值 1~7（1=周一）")
 Integer weekday,

 @Min(value = 1, message = "起始节次至少为 1")
 Integer startSection,

 @Min(value = 1, message = "结束节次至少为 1")
 Integer endSection,

 String weeks,

 String color
 ) {
 }

 /** 课程响应。对应 {@code CourseOut}。 */
 public record Out(
 Long id,
 String name,
 String teacher,
 String location,
 Integer weekday,
 Integer startSection,
 Integer endSection,
 String weeks,
 String color,
 LocalDateTime createdAt
 ) {
 public static Out from(Course e) {
 return new Out(e.getId(), e.getName(), e.getTeacher(), e.getLocation(),
 e.getWeekday(), e.getStartSection(), e.getEndSection(),
 e.getWeeks(), e.getColor(), e.getCreatedAt());
 }
 }

 /**
 * 智能导入请求。对应 {@code CourseImportRequest}。
 *
 * <p>三种输入方式二选一：{@code image}（课表截图 data URL，走视觉模型）、
 * 或 {@code text}/{@code url}（文本或网页，走文本模型）。
 *
 * <p><b>注意</b>：本接口的 AI 调用已在第三阶段接入 Spring AI（501 占位已移除）。
 * 输入不合法（三种输入都没给）会在 Service 里先抛 400，而不是静默返回空列表 ——
 * 后者会让用户以为"这张截图里没课"，比直接报错更难排查。
 */
 public record ImportRequest(
 String url,
 String text,
 /** 图片 data URL，形如 {@code data:image/png;base64,...}。 */
 String image,
 String provider,
 String model,
 /**
 * 云端平台 id（对应 {@code llm_providers.id}）。
 *
 * <p><b>为什么要这个字段</b>：云端凭据存在两张表里 —— 现代「接入平台」写在
 * {@code llm_providers}，旧的「单套云端配置」写在 {@code app_settings}。
 * 对话（AgentFactory）按会话的 {@code provider_id} 去 {@code llm_providers} 查，
 * 而课表导入早期只读 {@code app_settings}，于是「对话能用云端、导入用不了」。
 * 这里把 providerId 透传下来，让导入和对话走同一套凭据解析逻辑。
 * 为 null 时回落：先查第一个已接入平台，再回落 app_settings。
 */
 Long providerId
 ) {
 }

 /**
 * 解析出的单门课程草稿（未落库）。
 *
 * <p>字段名与 {@link Create} 保持一致，这样前端确认预览后可以直接把每个元素
 * POST 给创建接口，不需要做字段重命名。
 */
 public record ParsedCourse(
 String name,
 String teacher,
 String location,
 Integer weekday,
 Integer startSection,
 Integer endSection,
 String weeks
 ) {
 }

 /** 导入结果：{@code {"count": n, "courses": [...]}}。 */
 public record ImportResponse(
 int count,
 List<ParsedCourse> courses
 ) {
 }
}
