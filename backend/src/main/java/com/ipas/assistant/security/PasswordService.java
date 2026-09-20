package com.ipas.assistant.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 口令哈希与校验。对应 里的
 * {@code hash_password()} / {@code verify_password()}。
 *
 * <p><b>这一层做对了两件容易被忽略的事，直接决定「老用户的密码能不能继续登录」：</b>
 *
 * <h2>一、bcrypt 版本前缀必须是 $2b$</h2>
 * bcrypt 库的 {@code gensalt()} 生成的哈希形如 {@code $2b$12$...}。
 * 而 Spring 的 {@code BCryptPasswordEncoder} 默认生成 {@code $2a$}。
 * 两者算法相同、互相可以校验（所以<b>老密码能登录</b>），
 * 但为了「本实现新注册的账号，哈希格式与历史数据保持一致」，
 * 这里显式指定 {@code $2B} 版本与 12 轮强度（与 bcrypt 默认值相同）。
 * 这样将来若要把数据搬回 早期实现，也不会因为格式差异出问题。
 *
 * <h2>二、超过 72 字节的口令必须先截断</h2>
 * bcrypt 算法本身只取口令的前 <b>72 字节</b>，因此显式做了
 * {@code password.encode("utf-8")[:72]} 截断。
 *
 * <p>Java 侧更麻烦：Spring 的 {@code BCrypt.hashpw()} 遇到超过 72 字节的口令
 * 会直接抛 {@code IllegalArgumentException}，<b>而不是静默截断</b>。
 * 若不处理，一个 30 个汉字的密码就会让注册接口 500。
 * 所以这里必须补上截断逻辑。
 *
 * <p><b>实现细节与已知边界</b>：下面按「字符」而非「字节」截断——
 * 保留最长的一串字符，使其 UTF-8 编码不超过 72 字节，绝不把一个汉字劈成半个
 * （劈开会产生非法 UTF-8 字节序列，而 Java 的 String 装不下这种序列）。
 *
 * <p>与 早期实现本的差异仅存在于一种极端情况：<b>口令超过 72 字节、
 * 且第 72 字节恰好落在某个汉字中间</b>。此时 不同语言实现截出的字节序列与 Java 截出的
 * 不完全相同，这类用户的哈希校验会失败，需要重置密码。
 * 实际影响可忽略：能触发它的前提是「24 个以上汉字的密码」，
 * 而这种密码在 bcrypt 语义下本来就只有前 72 字节有效。
 */
@Service
public class PasswordService {

 /** bcrypt 算法可处理的最大口令长度（字节）。 */
 private static final int BCRYPT_MAX_BYTES = 72;

 /** bcrypt 强度：2^12 = 4096 轮，与早期实现 默认值一致。 */
 private static final int BCRYPT_STRENGTH = 12;

 private final BCryptPasswordEncoder encoder =
 new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, BCRYPT_STRENGTH);

 /**
 * 生成口令哈希。
 *
 * @param rawPassword 明文口令
 * @return 形如 {@code $2b$12$...} 的哈希字符串，可直接写入 users.hashed_password
 */
 public String hash(String rawPassword) {
 return encoder.encode(clip(rawPassword));
 }

 /**
 * 校验明文口令与哈希是否匹配。
 *
 * <p>任何异常都返回 {@code false} 而不向上抛——早期设计同样做了
 * {@code except (ValueError, TypeError): return False} 的兜底。
 * 原因：数据库里可能存着历史遗留的畸形哈希（早期导入的数据），
 * 此时应当按「密码不对」处理，而不是让登录接口 500。
 *
 * @param rawPassword 用户提交的明文口令
 * @param hashedPassword 数据库里存的哈希
 * @return 是否匹配
 */
 public boolean verify(String rawPassword, String hashedPassword) {
 if (rawPassword == null || hashedPassword == null || hashedPassword.isBlank()) {
 return false;
 }
 try {
 return encoder.matches(clip(rawPassword), hashedPassword);
 } catch (IllegalArgumentException e) {
 // 哈希格式不合法（不是有效的 bcrypt 串）→ 按不匹配处理
 return false;
 }
 }

 /**
 * 按「不劈开字符」的方式把口令截到 72 字节以内。
 *
 * <p>实现思路：从后往前逐个字符试探，找到第一个 UTF-8 编码长度不超过 72 字节的
 * 前缀。口令通常很短，这个循环实际上一次都不会执行。
 *
 * @param rawPassword 明文口令（可为 null）
 * @return 截断后的口令；入参为 null 时返回空串
 */
 private static String clip(String rawPassword) {
 if (rawPassword == null) {
 return "";
 }
 if (rawPassword.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES) {
 return rawPassword;
 }
 // 超长（罕见）：逐字符回退，直到 UTF-8 字节数落入 72 以内
 int end = rawPassword.length();
 while (end > 0
 && rawPassword.substring(0, end).getBytes(StandardCharsets.UTF_8).length
 > BCRYPT_MAX_BYTES) {
 // 需要跳过半个代理对（代理对是两个 char 表示一个字符）
 end--;
 if (end > 0
 && Character.isHighSurrogate(rawPassword.charAt(end - 1))
 && end < rawPassword.length()
 && Character.isLowSurrogate(rawPassword.charAt(end))) {
 end--;
 }
 }
 return rawPassword.substring(0, end);
 }
}
