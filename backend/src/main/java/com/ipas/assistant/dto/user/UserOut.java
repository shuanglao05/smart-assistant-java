package com.ipas.assistant.dto.user;

import com.ipas.assistant.entity.User;

import java.time.LocalDateTime;

/**
 * 个人资料响应体。对应 {@code schemas.UserOut}。
 *
 * <p><b>【安全要点】这里绝不能出现 {@code hashed_password}</b>。
 * 早期设计靠 数据校验框架 模型的字段白名单自动过滤——只要 UserOut 里不声明该字段，
 * 即使从 ORM 对象转换也不会带出去。
 *
 * <p>Java 侧的风险更高：如果直接返回 {@code User} 实体，Jackson 会把
 * {@code hashedPassword} 一起序列化成 {@code hashed_password} 吐给前端，
 * 等于把全部口令哈希公开。所以这里坚持用独立 DTO + 显式工厂方法
 * {@link #from(User)}，把「哪些字段可以出网」这件事写死在一个地方。
 *
 * <p>字段顺序与原 {@code UserOut} 保持一致（id, username, nickname, avatar,
 * language, font_size, theme, created_at），便于人工比对两个版本接口输出是否一致。
 *
 * @param id 用户主键
 * @param username 登录账号（只读）
 * @param nickname 昵称，可为空
 * @param avatar 头像（表情字符或 data URL），可为空
 * @param language zh / en
 * @param fontSize fs12 ~ fs22（兼容旧值 small/medium/large）
 * @param theme light / dark / sepia / contrast
 * @param createdAt 注册时间（UTC，不带时区后缀，与早期设计序列化格式一致）
 */
public record UserOut(
 Long id,
 String username,
 String nickname,
 String avatar,
 String language,
 String fontSize,
 String theme,
 LocalDateTime createdAt
) {

 /**
 * 由实体转换为响应体。对应 数据校验框架 的
 * {@code model_config = ConfigDict(from_attributes=True)} —— 即「允许从
 * ORM 对象的属性直接构造」。
 *
 * @param user 账号实体（可为 null，返回 null）
 * @return 不含敏感字段的响应体
 */
 public static UserOut from(User user) {
 if (user == null) {
 return null;
 }
 return new UserOut(
 user.getId(),
 user.getUsername(),
 user.getNickname(),
 user.getAvatar(),
 user.getLanguage(),
 user.getFontSize(),
 user.getTheme(),
 user.getCreatedAt());
 }
}
