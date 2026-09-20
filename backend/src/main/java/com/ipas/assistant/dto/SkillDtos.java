package com.ipas.assistant.dto;

import com.ipas.assistant.entity.Skill;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 技能模块的请求 / 响应体。对应 的
 * {@code SkillCreate} / {@code SkillUpdate} / {@code SkillOut}。
 */
public final class SkillDtos {

 private SkillDtos() {
 }

 /**
 * 创建技能。对应 {@code SkillCreate}：
 * {@code name} (1~100)、{@code description} 可选、{@code prompt} (min_length=1)。
 *
 * <p>注意 {@code description} 在早期设计里<b>没有加长度上限</b>，
 * 但数据库列是 VARCHAR(500)。若前端传了超长描述，MySQL 在严格模式下会
 * 直接报错而不是截断。这里补上 {@code @Size(max = 500)} 把它变成
 * 一个清晰的 422 中文提示，比让数据库抛异常友好得多。
 * （这是与原文的一处有意收紧，属于防御性改进，已记录在方案文档。）
 */
 public record Create(
 @NotBlank(message = "技能名称不能为空")
 @Size(max = 100, message = "技能名称最长 100 个字符")
 String name,

 @Size(max = 500, message = "技能描述最长 500 个字符")
 String description,

 @NotBlank(message = "技能指令不能为空")
 String prompt
 ) {
 }

 /** 更新技能。四个字段都可选，null = 不改。 */
 public record Update(
 @Size(max = 100, message = "技能名称最长 100 个字符")
 String name,

 @Size(max = 500, message = "技能描述最长 500 个字符")
 String description,

 String prompt,

 Boolean isEnabled
 ) {
 }

 /**
 * 技能响应。对应 {@code SkillOut}。
 *
 * <p>注意这里<b>包含 {@code user_id}</b> 而别的模块不包含 —— 这是照抄原
 * {@code SkillOut} 的字段定义（前端类型里也有它）。虽然当前只有一个用户
 * 在用（多用户隔离靠查询条件而非响应字段），但契约要保持一致。
 */
 public record Out(
 Long id,
 Long userId,
 String name,
 String description,
 String prompt,
 Boolean isEnabled,
 LocalDateTime createdAt,
 LocalDateTime updatedAt
 ) {
 public static Out from(Skill e) {
 return new Out(e.getId(), e.getUserId(), e.getName(), e.getDescription(),
 e.getPrompt(), e.getIsEnabled(), e.getCreatedAt(), e.getUpdatedAt());
 }
 }
}
