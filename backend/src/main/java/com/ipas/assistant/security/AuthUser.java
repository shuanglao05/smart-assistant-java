package com.ipas.assistant.security;

/**
 * 已认证用户（JWT 解析后放进 SecurityContext 的主体）。
 *
 * <p>对应 {@code deps.get_current_user()} 返回的 User 对象。区别是这里
 * <b>只带 id 和 username 两个字段</b>，不带密码哈希、不带头像等大字段。
 *
 * <p><b>为什么要刻意做成轻量的</b>：
 * 早期设计每个请求都把整行 User 查出来塞进依赖里，其中包含 {@code avatar}
 * （可能是几百 KB 的 data URL）和 {@code hashed_password}。这些字段 99% 的接口
 * 用不到，白白浪费内存与带宽，还提高了敏感字段被意外序列化出去的风险。
 * 这里只保留鉴权与多用户隔离真正需要的两个字段，需要完整资料时再由
 * UserService 按需查库（见 UserController#getMe）。
 *
 * @param id 用户主键，所有业务查询都以它做 user_id 过滤（多用户隔离的根）
 * @param username 登录账号
 */
public record AuthUser(Long id, String username) {
}
