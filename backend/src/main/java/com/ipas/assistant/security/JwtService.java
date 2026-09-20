package com.ipas.assistant.security;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验。对应 里的
 * {@code create_access_token()} + 里的 {@code jwt.decode()}。
 *
 * <p>载荷结构与早期设计<b>逐字段保持一致</b>：
 * <pre>
 * {
 * "sub": "1", // 用户 id，注意是【字符串】不是数字
 * "username": "alice",
 * "exp": 1758000000 // 过期时间（epoch 秒）
 * }
 * </pre>
 * {@code sub} 存字符串是早期设计 {@code {"sub": str(user.id)}} 的写法（JWT 规范要求
 * sub 是字符串）。这里沿用，避免实现期出现「按字符串取、按数字取」的不一致。
 *
 * <p><b>【重要差异 · 必须知道】密钥处理方式与早期设计不同</b>
 *
 * <p>早期实现 侧：{@code jwt.encode(payload, SECRET_KEY, algorithm="HS256")}
 * 直接拿 {@code SECRET_KEY} 的原始字节当密钥，PyJWT 不做长度检查。
 *
 * <p>Java 侧：jjwt 对 HS256 有<b>密钥长度硬校验</b>——少于 256 位（32 字节）会直接抛
 * {@code WeakKeyException}。而早期设计默认值 {@code "dev-secret-change-me"} 只有 21 字节，
 * 沿用必然启动即崩。
 *
 * <p>解决办法：密钥长度不足 32 字节时，先用 SHA-256 摘要规整成固定 32 字节。
 * 这样任意长度的密钥都能用，也避免强迫用户去凑长度。
 *
 * <p><b>由此带来的后果</b>：同一份 SECRET_KEY 在 早期实现和 本实现算出的签名密钥
 * 并不相同，因此<b>两版签发的 token 不能互相验证</b>。这在实践中没有问题——
 * token 只在自己的后端签发、自己校验，用户重新登录即可。但如果你打算做
 * 「早期实现与 本实现并行灰度、共享登录态」，就必须让两侧使用完全相同的
 * 密钥处理方式（把下面的 {@code deriveKey} 改成直接取原始字节，并保证密钥 ≥32 字节）。
 */
@Component
public class JwtService {

 private static final Logger log = LoggerFactory.getLogger(JwtService.class);

 /** HS256 要求的最小密钥长度（字节）。 */
 private static final int MIN_HMAC_KEY_BYTES = 32;

 private final SecretKey signingKey;
 private final long expireMinutes;

 public JwtService(AppProperties properties) {
 String secret = properties.security().secretKey();
 if (secret == null || secret.isBlank()) {
 throw new IllegalStateException(
 "未配置 app.security.secret-key（对应 SECRET_KEY），无法签发登录令牌");
 }
 if (MIN_HMAC_KEY_BYTES > secret.getBytes(StandardCharsets.UTF_8).length) {
 log.warn("app.security.secret-key 长度不足 {} 字节，已用 SHA-256 规整。"
 + "生产环境请配置足够长的随机密钥（例如 openssl rand -base64 48）。",
 MIN_HMAC_KEY_BYTES);
 }
 this.signingKey = deriveKey(secret);
 this.expireMinutes = properties.security().accessTokenExpireMinutes();
 }

 /**
 * 把任意长度的密钥规整成 32 字节，以通过 jjwt 的 HS256 长度校验。
 *
 * <p>做法：长度 >= 32 字节时原样使用（不引入额外变换，语义最直观）；
 * 不足时用 SHA-256 摘要（输出恒为 32 字节）。
 */
 private static SecretKey deriveKey(String secret) {
 byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
 if (raw.length < MIN_HMAC_KEY_BYTES) {
 try {
 raw = MessageDigest.getInstance("SHA-256").digest(raw);
 } catch (NoSuchAlgorithmException e) {
 // SHA-256 是 JDK 必备算法，理论上不可能走到这里
 throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
 }
 }
 return Keys.hmacShaKeyFor(raw);
 }

 /**
 * 签发访问令牌。
 *
 * @param userId 用户主键（会以字符串形式写入 sub）
 * @param username 登录账号
 * @return 紧凑格式的 JWT 字符串
 */
 public String createAccessToken(Long userId, String username) {
 Instant now = Instant.now();
 Instant expiry = now.plusSeconds(expireMinutes * 60);
 return Jwts.builder()
 .subject(String.valueOf(userId))
 .claim("username", username)
 .issuedAt(Date.from(now))
 .expiration(Date.from(expiry))
 .signWith(signingKey, Jwts.SIG.HS256)
 .compact();
 }

 /**
 * 校验令牌并取出用户 id。
 *
 * <p>签名不对、格式不对、已过期等任何情况，都会抛出 {@code io.jsonwebtoken.JwtException}
 * 的子类，由 {@link JwtAuthenticationFilter} 统一拦下。
 *
 * <p>早期设计在这里 {@code int(payload.get("sub"))}，为了抵御「sub 被塞了非数字字符串」
 * 的攻击，下面同样做了数字解析与异常包裹，解析失败按认证失败处理。
 *
 * @param token 前端 {@code Authorization: Bearer <token>} 里那段 token
 * @return 用户主键
 * @throws JwtException 令牌无效或已过期
 */
 public Long parseUserId(String token) {
 Claims claims = Jwts.parser()
 .verifyWith(signingKey)
 .build()
 .parseSignedClaims(token)
 .getPayload();

 String subject = claims.getSubject();
 if (subject == null || subject.isBlank()) {
 // 令牌里没有 sub：结构非法，按无效令牌处理
 throw new JwtException("令牌缺少 sub 声明");
 }
 try {
 return Long.parseLong(subject);
 } catch (NumberFormatException e) {
 throw new JwtException("令牌 sub 不是合法的用户 id: " + subject);
 }
 }

 /**
 * 从 Authorization 头里取出裸 token。
 *
 * @param authorizationHeader 形如 {@code "Bearer eyJhbGci..."}
 * @return 去掉 Bearer 前缀的 token；头部缺失或格式不对时返回 {@code null}
 */
 public static String extractBearerToken(String authorizationHeader) {
 if (authorizationHeader == null || authorizationHeader.isBlank()) {
 return null;
 }
 String prefix = "Bearer ";
 // 大小写不敏感：部分客户端（Postman / 某些 SDK）会写成小写 bearer
 if (authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
 String token = authorizationHeader.substring(prefix.length()).trim();
 return token.isEmpty() ? null : token;
 }
 return null;
 }

 /**
 * 令牌过期时间（分钟）。供 /api/health 与调试使用。
 */
 public long getExpireMinutes() {
 return expireMinutes;
 }

 /** 认证失败时统一抛出的异常（供过滤器调用）。 */
 public static ApiException unauthorized() {
 return ApiException.unauthorized("认证失败");
 }
}
