package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.dto.user.UserOut;
import com.ipas.assistant.dto.user.UserUpdateRequest;
import com.ipas.assistant.entity.User;
import com.ipas.assistant.repository.UserRepository;
import com.ipas.assistant.security.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 个人资料业务逻辑。对应 。
 */
@Service
public class UserService {

 private static final Logger log = LoggerFactory.getLogger(UserService.class);

 /**
 * 允许的取值集合。与早期实现 顶部的三个常量<b>逐项一致</b>。
 *
 * <p>特别注意 {@code ALLOWED_FONT_SIZES} 里同时存在两套值：
 * {@code fs12~fs22} 是新版的分档写法，{@code small/medium/large} 是旧版遗留。
 * 两套都必须保留 —— 数据库里可能还存着旧值（默认值就是 {@code medium}），
 * 若只认新值，老用户一改资料就会被判为非法。
 */
 private static final Set<String> ALLOWED_LANGUAGES = Set.of("zh", "en");

 private static final Set<String> ALLOWED_FONT_SIZES = Set.of(
 "fs12", "fs14", "fs16", "fs18", "fs20", "fs22",
 "small", "medium", "large");

 private static final Set<String> ALLOWED_THEMES = Set.of(
 "light", "dark", "sepia", "contrast");

 private final UserRepository userRepository;
 private final PasswordService passwordService;

 public UserService(UserRepository userRepository, PasswordService passwordService) {
 this.userRepository = userRepository;
 this.passwordService = passwordService;
 }

 /**
 * 查询当前用户完整资料。
 *
 * <p>早期设计在这里特意<b>又查了一次数据库</b>（而不是直接用依赖注入进来的
 * {@code current_user} 对象），注释说明是「字段可能缺新增列」。
 * 这里的动机不同但同样需要重查：JWT 里只带了 id 和 username
 * （见 {@code AuthUser}），昵称、头像这些字段不在令牌里，必须查库。
 *
 * @param userId 当前用户 id
 * @return 个人资料
 * @throws ApiException 404 用户不存在（令牌有效但账号已被删除）
 */
 @Transactional(readOnly = true)
 public UserOut getProfile(Long userId) {
 User user = requireUser(userId);
 return UserOut.from(user);
 }

 /**
 * 更新个人资料 / 修改密码。
 *
 * <p>对应 的 {@code update_me()}，逐条逻辑保持一致：
 *
 * <ol>
 * <li>每个字段<b>只有非 null 时才更新</b>（传 null = 本次不改这个字段）；</li>
 * <li>{@code nickname} 截断到 50 字符；</li>
 * <li>{@code avatar} 截断到 100 万字符；</li>
 * <li>语言 / 字号 / 主题必须是白名单里的值，否则 400 + 中文提示；</li>
 * <li>改密时若没带 {@code current_password} → 400；当前密码不对 → 400。</li>
 * </ol>
 *
 * @param userId 当前用户 id
 * @param payload 更新请求
 * @return 更新后的资料
 */
 @Transactional
 public UserOut updateProfile(Long userId, UserUpdateRequest payload) {
 User user = requireUser(userId);

 // ---- 昵称：去空格 + 截断到 50 字符 ----
 if (payload.nickname() != null) {
 String nickname = payload.nickname().trim();
 user.setNickname(nickname.length() > 50 ? nickname.substring(0, 50) : nickname);
 }

 // ---- 头像：截断到 100 万字符 ----
 // 前端上传前已把图片压到几十 KB 并转成 data URL，正常情况不会触到上限。
 // 保留截断是为了防御「绕过前端直接调接口塞超大字符串」——
 // 若不截断，一个 10MB 的字符串写进 MEDIUMTEXT 会把这一行撑爆。
 if (payload.avatar() != null) {
 String avatar = payload.avatar();
 user.setAvatar(avatar.length() > 1_000_000 ? avatar.substring(0, 1_000_000) : avatar);
 }

 // ---- 语言 ----
 if (payload.language() != null) {
 if (!ALLOWED_LANGUAGES.contains(payload.language())) {
 throw ApiException.badRequest("不支持的语言: " + payload.language());
 }
 user.setLanguage(payload.language());
 }

 // ---- 字号 ----
 if (payload.fontSize() != null) {
 if (!ALLOWED_FONT_SIZES.contains(payload.fontSize())) {
 throw ApiException.badRequest("不支持的字号: " + payload.fontSize());
 }
 user.setFontSize(payload.fontSize());
 }

 // ---- 主题 ----
 if (payload.theme() != null) {
 if (!ALLOWED_THEMES.contains(payload.theme())) {
 throw ApiException.badRequest("不支持的主题: " + payload.theme());
 }
 user.setTheme(payload.theme());
 }

 // ---- 改密 ----
 // 注意这里先判 newPassword 是否为空，再要求 currentPassword —— 顺序与早期设计一致。
 // 前端只在「修改密码」表单里同时提交这两个字段，普通改昵称不会带它们。
 if (payload.newPassword() != null && !payload.newPassword().isEmpty()) {
 if (payload.currentPassword() == null || payload.currentPassword().isEmpty()) {
 throw ApiException.badRequest("修改密码需要提供 current_password");
 }
 if (!passwordService.verify(payload.currentPassword(), user.getHashedPassword())) {
 throw ApiException.badRequest("当前密码不正确");
 }
 user.setHashedPassword(passwordService.hash(payload.newPassword()));
 log.info("用户修改密码成功 id={}", userId);
 }

 // JPA 的「脏检查」会自动把改动同步到数据库（因为 user 处于托管状态），
 // 不需要显式调用 save()。这也是早期设计 db.commit() 在 Java 侧的等价物——
 // 提交时机由 @Transactional 决定。
 return UserOut.from(user);
 }

 /**
 * 按 id 取用户，取不到就抛 404。
 *
 * <p>触发场景：令牌还没过期，但账号已被删除（或数据库被重置）。
 * 此时应当明确返回 404 而不是 500。
 */
 private User requireUser(Long userId) {
 return userRepository.findById(userId)
 .orElseThrow(() -> ApiException.notFound("用户不存在"));
 }
}
