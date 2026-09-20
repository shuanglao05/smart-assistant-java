package com.ipas.assistant.entity;

import com.ipas.assistant.common.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 账号实体。对应 的 {@code User} 类 / {@code users} 表。
 *
 * <p><b>【为什么用 @Getter + @Setter 而不是 @Data】</b>
 * Lombok 的 {@code @Data} 会一并生成 {@code equals()} 与 {@code hashCode()}，
 * 而它默认基于<b>所有字段</b>比较，包含 {@code id} 和 {@code avatar}。
 * 对 JPA 实体来说这很危险：
 * <ul>
 * <li>新建对象时 {@code id} 为 null，放进 {@code HashSet} 后再保存（id 被赋值），
 * 会导致 {@code hashCode} 变化、集合里再也找不到它；</li>
 * <li>{@code avatar} 可能是几百 KB 的 data URL，参与 {@code equals} 比较纯属浪费。</li>
 * </ul>
 * 只用 {@code @Getter}/{@code @Setter}，把相等性判断交给业务代码，是更稳的做法。
 *
 * <p><b>【时间字段用 LocalDateTime 而不是 Instant 的原因】</b>
 * 早期设计用 {@code datetime.now(timezone.utc)} 生成时间，并以「不带时区」的形式存进数据库。
 * 前端拿到的字符串形如 {@code 2026-09-16T14:23:01}（没有末尾的 Z），
 * JavaScript 的 {@code new Date()} 会按<b>本地时区</b>解析它。
 *
 * <p>这里必须保持同样的语义：{@code LocalDateTime} 在 Jackson 默认配置下同样输出
 * 「不带时区」的 ISO 字符串。若改用 {@code Instant} 或 {@code OffsetDateTime}，
 * 输出会带上 Z 后缀，JS 会改按 UTC 解析，界面上所有时间立刻偏 8 小时。
 */
@Entity
@Table(name = "users")
@Getter
@Setter
public class User {

 @Id
 @GeneratedValue(strategy = GenerationType.IDENTITY)
 @Column(name = "id")
 private Long id;

 /** 登录账号。创建后不可修改（早期设计注释如此说明，且没有改名接口）。 */
 @Column(name = "username", nullable = false, length = 50, unique = true)
 private String username;

 /** bcrypt 哈希，形如 {@code $2b$12$...}。绝不能出现在任何响应体里。 */
 @Column(name = "hashed_password", nullable = false, length = 255)
 private String hashedPassword;

 /** 显示用昵称，可改。 */
 @Column(name = "nickname", length = 50)
 private String nickname;

 /** 表情字符或头像 data URL（前端已压缩，约几十 KB）。 */
 @Column(name = "avatar", columnDefinition = "MEDIUMTEXT")
 private String avatar;

 /** 界面语言：zh / en。 */
 @Column(name = "language", nullable = false, length = 10)
 private String language = "zh";

 /** 界面字号：fs12 ~ fs22（兼容旧值 small/medium/large）。 */
 @Column(name = "font_size", nullable = false, length = 10)
 private String fontSize = "medium";

 /** 界面主题：light / dark / sepia / contrast。 */
 @Column(name = "theme", nullable = false, length = 10)
 private String theme = "dark";

 @Column(name = "created_at")
 private LocalDateTime createdAt;

 /**
 * 落库前补默认值。
 *
 * <p>对应 {@code Column(DateTime, default=utcnow)} 的行为——
 * ORM 框架 在 INSERT 时自动填值。JPA 里用 {@code @PrePersist} 回调实现同样效果。
 *
 * <p>注意这里取的是 {@code ZoneOffset.UTC} 的墙上时间，而不是
 * {@code LocalDateTime.now()}（那是服务器本地时间）。刻意如此，
 * 是为了与 {@code datetime.now(timezone.utc)} 完全一致——
 * 无论服务器在哪个时区，落库的都是 UTC。
 */
 @PrePersist
 void onCreate() {
 if (createdAt == null) {
 // 用 Times.nowUtc() 而不是 LocalDateTime.now(ZoneOffset.UTC)：
 // 前者会把精度截到微秒，与数据库 DATETIME(6) 能存的精度对齐，
 // 避免「创建响应带纳秒、查询响应带微秒」的对不上问题。
 // 详见 Times 类的注释。
 createdAt = Times.nowUtc();
 }
 // 三个带默认值的字符串列兜底：若调用方没设，补上与早期设计相同的默认值，
 // 避免写入 null 后与 NOT NULL 约束冲突
 if (language == null || language.isBlank()) {
 language = "zh";
 }
 if (fontSize == null || fontSize.isBlank()) {
 fontSize = "medium";
 }
 if (theme == null || theme.isBlank()) {
 theme = "dark";
 }
 }
}
