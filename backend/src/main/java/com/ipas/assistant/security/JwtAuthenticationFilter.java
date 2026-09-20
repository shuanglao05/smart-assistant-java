package com.ipas.assistant.security;

import com.ipas.assistant.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器。
 *
 * <p>对应 里的 {@code get_current_user()}——
 * 但它比原版多做了一件事：原版是<b>每个路由</b>通过 {@code Depends(get_current_user)}
 * 显式声明「我要认证」；Spring 是<b>一个过滤器</b>统一处理所有请求，认证结果放进
 * {@code SecurityContext}，Controller 用 {@code @AuthenticationPrincipal} 取用。
 *
 * <p>这样写的好处：鉴权逻辑只有一份，新增接口时不会因为「忘了写 Depends」而裸奔
 * （早期设计里确实存在这种风险——漏加 Depends 的接口就是公开的）。
 *
 * <p><b>认证流程</b>（与原版逐条对应）：
 * <ol>
 * <li>从 {@code Authorization: Bearer xxx} 取出 token</li>
 * <li>校验签名与过期时间，取出 {@code sub}（用户 id）</li>
 * <li><b>查库确认用户仍然存在</b> —— 原版有此步骤，不能省：
 * 账号被删除后，其未过期的 token 必须立即失效，否则等于存在一个幽灵会话</li>
 * <li>把 {@link AuthUser} 放进 SecurityContext</li>
 * </ol>
 *
 * <p><b>失败时不直接返回 401</b>，而是「不设置认证信息」然后放行，让 Spring Security
 * 的授权环节去拒绝。原因：有些接口是公开的（/api/auth/login、/api/health），
 * 带着一个过期 token 去访问它们不应该被拦下——这点和原版行为一致
 * （原版公开接口压根不声明 Depends，根本不解析 token）。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

 private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

 private final JwtService jwtService;
 private final UserRepository userRepository;

 public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
 this.jwtService = jwtService;
 this.userRepository = userRepository;
 }

 @Override
 protected void doFilterInternal(@NonNull HttpServletRequest request,
 @NonNull HttpServletResponse response,
 @NonNull FilterChain filterChain)
 throws ServletException, IOException {

 // 已经认证过就不再重复处理（例如链路上有多次转发）
 if (SecurityContextHolder.getContext().getAuthentication() == null) {
 String token = JwtService.extractBearerToken(request.getHeader("Authorization"));
 if (token != null) {
 authenticate(request, token);
 }
 }
 filterChain.doFilter(request, response);
 }

 /**
 * 校验 token 并写入 SecurityContext。任何异常都只记 debug 日志后静默放弃认证，
 * 避免「token 过期」这种日常情况把日志刷满。
 */
 private void authenticate(HttpServletRequest request, String token) {
 try {
 Long userId = jwtService.parseUserId(token);
 // 校验失败（按 user_id 查不到对应用户）→ 返回 401
 userRepository.findById(userId).ifPresent(user -> {
 AuthUser principal = new AuthUser(user.getId(), user.getUsername());
 UsernamePasswordAuthenticationToken authentication =
 new UsernamePasswordAuthenticationToken(
 principal,
 null, // credentials 置空：token 已经校验完，不需要再留着
 AuthorityUtils.NO_AUTHORITIES); // 本项目没有角色体系，统一空权限
 authentication.setDetails(
 new WebAuthenticationDetailsSource().buildDetails(request));
 SecurityContextHolder.getContext().setAuthentication(authentication);
 });
 } catch (JwtException | IllegalArgumentException e) {
 // 令牌无效 / 过期 / sub 非法：不认证，交给授权环节返回 401
 log.debug("JWT 校验未通过：{}", e.getMessage());
 }
 }
}
