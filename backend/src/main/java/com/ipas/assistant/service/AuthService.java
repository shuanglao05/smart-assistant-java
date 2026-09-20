package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.dto.auth.LoginRequest;
import com.ipas.assistant.dto.auth.RegisterRequest;
import com.ipas.assistant.dto.auth.TokenResponse;
import com.ipas.assistant.entity.User;
import com.ipas.assistant.repository.UserRepository;
import com.ipas.assistant.security.JwtService;
import com.ipas.assistant.security.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册 / 登录业务逻辑。对应 的两个路由函数。
 *
 * <p>与 接口框架 版本的结构差异：接口框架 把业务逻辑直接写在路由函数里
 * （{@code @router.post("/register")} 下面就是全部逻辑）。这里拆成
 * Controller（只负责收参数、返响应）+ Service（业务逻辑）两层。
 *
 * <p>拆开的好处很实际：
 * <ul>
 * <li>将来「AI 生成的邀请链接也能建号」之类的需求，可以直接复用 Service，
 * 不用再去调自己的 HTTP 接口；</li>
 * <li>{@code @Transactional} 加在 Service 上语义才正确 ——
 * 事务边界应该由「一个完整业务操作」界定，而不是「一次 HTTP 请求」。</li>
 * </ul>
 */
@Service
public class AuthService {

 private static final Logger log = LoggerFactory.getLogger(AuthService.class);

 private final UserRepository userRepository;
 private final PasswordService passwordService;
 private final JwtService jwtService;

 /**
 * 用于「空转校验」的固定 bcrypt 哈希（时序攻击防护，见 login 方法）。
 *
 * <p><b>为什么要在构造器里现算，而不是写一个常量字符串</b>：
 * 最初想直接硬编码一个 {@code $2b$12$...} 常量，但那有两个坑：
 * <ul>
 * <li>bcrypt 哈希的格式是 {@code $2b$12$} + 22 字符 salt + 31 字符摘要，
 * 长度或 Base64 字符集稍有偏差，{@code matches()} 会直接抛
 * {@code IllegalArgumentException} 而<b>不做任何计算</b> ——
 * 空转也就失去意义，时序防护形同虚设；</li>
 * <li>手抄一个哈希既不可读，也无法验证其正确性。</li>
 * </ul>
 * 改为启动时用 PasswordService 真实哈希一个固定字符串，得到的一定是合法哈希，
 * 且代价只有启动时一次 bcrypt 运算（几十毫秒）。
 */
 private final String dummyHash;

 public AuthService(UserRepository userRepository,
 PasswordService passwordService,
 JwtService jwtService) {
 this.userRepository = userRepository;
 this.passwordService = passwordService;
 this.jwtService = jwtService;
 this.dummyHash = passwordService.hash("timing-equalizer-not-a-real-password");
 }

 /**
 * 注册新账号并直接签发登录令牌（早期设计注册成功后也立即返回 token，
 * 用户不需要再登录一次）。
 *
 * @param payload 注册请求
 * @return 令牌与账号名
 * @throws ApiException 400 用户名为空 / 用户名已存在
 */
 @Transactional
 public TokenResponse register(RegisterRequest payload) {
 // 对应：username = payload.username.strip(); if not username: raise 400
 // 这一步专门处理「纯空格」输入（数据校验框架 的 min_length=1 挡不住它）
 String username = payload.username() == null ? "" : payload.username().trim();
 if (username.isEmpty()) {
 throw ApiException.badRequest("用户名不能为空");
 }

 // 对应：if db.query(User).filter(User.username == username).first(): raise 400
 //
 // 【并发说明】这里「先查后插」存在竞态：两个请求同时通过检查后都去插入。
 // 早期设计（SQLite）同样如此。真正兜底的是数据库上 username 的 UNIQUE 索引 ——
 // 第二个插入会失败。这里再显式查一次的意义是「把 99.9% 的情况变成友好提示」，
 // 而不是当作唯一屏障（唯一屏障是 DB 约束，见 schema-mysql.sql 的 uk_users_username）。
 if (userRepository.existsByUsername(username)) {
 throw ApiException.badRequest("用户名已存在");
 }

 User user = new User();
 user.setUsername(username);
 // 口令哈希：内部已处理 bcrypt 的 72 字节上限（详见 PasswordService）
 user.setHashedPassword(passwordService.hash(payload.password()));
 // nickname / avatar / language / font_size / theme 交给 @PrePersist 填默认值，
 // 与早期设计只传 username 与 hashed_password 的写法一致

 User saved = userRepository.save(user);
 log.info("新用户注册成功 id={} username={}", saved.getId(), saved.getUsername());

 return buildTokenResponse(saved);
 }

 /**
 * 校验账号密码并签发令牌。
 *
 * <p>对应：
 * <pre>
 * user = db.query(User).filter(User.username == payload.username.strip()).first()
 * if not user or not verify_password(payload.password, user.hashed_password):
 * raise HTTPException(401, "用户名或密码错误")
 * </pre>
 *
 * <p><b>两个安全细节，必须与原文一致：</b>
 * <ol>
 * <li><b>「用户不存在」和「密码错误」返回同一句话</b>（401「用户名或密码错误」）。
 * 若分开提示，攻击者就能靠响应差异枚举出哪些用户名真实存在。</li>
 * <li><b>用户不存在时也要跑一次 bcrypt 校验</b>（见下面的注释）—— 否则
 * 「账号不存在」会立刻返回，而「账号存在"要跑 12 轮 bcrypt（约几十毫秒），
 * 攻击者用响应耗时就能区分两者。</li>
 * </ol>
 */
 @Transactional(readOnly = true)
 public TokenResponse login(LoginRequest payload) {
 String username = payload.username() == null ? "" : payload.username().trim();
 String rawPassword = payload.password() == null ? "" : payload.password();

 User user = userRepository.findByUsername(username).orElse(null);

 if (user == null) {
 // 时序攻击防护：即使账号不存在，也执行一次等价的 bcrypt 运算，
 // 让「账号不存在」与「密码错误」的响应耗时接近。
 // 早期设计没有做这件事（接口框架 版本会提前返回），这里属于改进。
 passwordService.verify(rawPassword, dummyHash);
 throw ApiException.unauthorized("用户名或密码错误");
 }

 if (!passwordService.verify(rawPassword, user.getHashedPassword())) {
 throw ApiException.unauthorized("用户名或密码错误");
 }

 log.info("用户登录成功 id={} username={}", user.getId(), user.getUsername());
 return buildTokenResponse(user);
 }

 /** 由用户实体签发令牌并组装响应（注册与登录共用）。 */
 private TokenResponse buildTokenResponse(User user) {
 String token = jwtService.createAccessToken(user.getId(), user.getUsername());
 return TokenResponse.of(token, user.getUsername());
 }
}
