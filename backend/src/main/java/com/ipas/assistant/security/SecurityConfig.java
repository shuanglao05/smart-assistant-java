package com.ipas.assistant.security;

import com.ipas.assistant.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 配置。
 *
 * <p>对应 里这几件事的组合：
 * <pre>
 * app.add_middleware(CORSMiddleware, allow_origins=[...],
 * allow_credentials=True, allow_methods=["*"], allow_headers=["*"])
 * </pre>
 * 加上 的 {@code OAuth2PasswordBearer} 声明式鉴权。
 *
 * <p><b>【开放接口清单】—— 与早期设计严格对齐，多一个都不行</b>
 * <table border="1">
 * <tr><th>路径</th><th>是否需登录</th><th>说明</th></tr>
 * <tr><td>/api/auth/register</td><td>否</td><td>注册（还没有账号，不可能带 token）</td></tr>
 * <tr><td>/api/auth/login</td><td>否</td><td>登录</td></tr>
 * <tr><td>/api/health</td><td>否</td><td>健康检查（早期设计该接口没写 Depends）</td></tr>
 * <tr><td>其它 /api/**</td><td><b>是</b></td><td>早期设计全部声明了 Depends(get_current_user)</td></tr>
 * </table>
 *
 * <p>采用「白名单放行 + 其余一律要求认证」而不是「逐个接口标注需要认证」的原因：
 * 前者漏配的后果是「接口多了个 401」，后者漏配的后果是「用户数据被公开访问」。
 * 安全配置的默认值必须偏保守。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

 /** 完全无需认证即可访问的路径（与早期设计的路由声明逐个核对过）。 */
 private static final String[] PUBLIC_PATHS = {
 "/api/auth/register",
 "/api/auth/login",
 "/api/health",
 // Spring Boot 的错误转发端点：若不放开，任何异常都会被改写成 401，
 // 会让「参数错误」这类问题变得极难排查
 "/error",
 };

 private final JwtAuthenticationFilter jwtAuthenticationFilter;
 private final RestAuthenticationEntryPoint authenticationEntryPoint;
 private final AppProperties properties;

 public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
 RestAuthenticationEntryPoint authenticationEntryPoint,
 AppProperties properties) {
 this.jwtAuthenticationFilter = jwtAuthenticationFilter;
 this.authenticationEntryPoint = authenticationEntryPoint;
 this.properties = properties;
 }

 @Bean
 public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
 http
 // CORS 交给下面的 corsConfigurationSource 处理
 .cors(cors -> cors.configurationSource(corsConfigurationSource()))

 // 关闭 CSRF：本项目用无状态 JWT（放在 Authorization 头里），
 // 浏览器不会自动携带凭证，CSRF 攻击的前提不成立。
 // 若保留 CSRF，所有 POST 都要额外带 token，纯属自找麻烦。
 .csrf(csrf -> csrf.disable())

 // 关闭默认的登录方式：本项目只认 JWT，
 // 不要表单登录页、不要 HTTP Basic 弹窗
 .formLogin(form -> form.disable())
 .httpBasic(basic -> basic.disable())
 .logout(logout -> logout.disable())

 // 无状态：不创建 HttpSession。每个请求都必须自带 token。
 // 这样后端可以水平扩展，也不会有「session 过期」的额外状态要维护。
 .sessionManagement(session ->
 session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

 .authorizeHttpRequests(auth -> auth
 // 预检请求（OPTIONS）必须放行：浏览器发 CORS 预检时不带 Authorization 头，
 // 若拦下会导致跨域请求直接失败
 .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
 .requestMatchers(PUBLIC_PATHS).permitAll()
 .anyRequest().authenticated())

 // 认证/授权失败的响应格式（接口框架 风格 {"detail": "..."}）
 .exceptionHandling(ex -> ex
 .authenticationEntryPoint(authenticationEntryPoint)
 .accessDeniedHandler(authenticationEntryPoint))

 // 把 JWT 过滤器插在用户名密码过滤器之前。
 // 位置很重要：必须在授权判断（FilterSecurityInterceptor）之前执行，
 // 否则 SecurityContext 还是空的，所有请求都会被判为未认证。
 .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

 return http.build();
 }

 /**
 * CORS 配置。
 *
 * <p>对应（端口已随本次迁移改为 5174）：
 * <pre>allow_origins=["http://localhost:5174", "http://127.0.0.1:5174"]</pre>
 *
 * <p>两个来源都要保留的原因（早期设计注释里没写，但这是实测踩过的坑）：
 * Vite 打印的地址是 {@code localhost:5174}，但用户手输时经常写
 * {@code 127.0.0.1:5174}。这两个在浏览器眼里是<b>不同的 Origin</b>，
 * 只配一个就会有一半情况跨域失败。
 *
 * <p>注意：开发期前端是通过 Vite 代理（{@code vite.config.ts} 里的
 * {@code '/api': 'http://127.0.0.1:8002'}）访问后端的，那种情况下请求同源、
 * 根本不触发 CORS。这段配置真正生效的场景是「前端直接指向 8002 端口」
 * （例如打包后单独部署，或本地调试时绕过代理）。
 */
 @Bean
 public CorsConfigurationSource corsConfigurationSource() {
 CorsConfiguration configuration = new CorsConfiguration();
 List<String> origins = properties.cors() == null
 ? List.of("http://localhost:5174", "http://127.0.0.1:5174")
 : properties.cors().allowedOrigins();

 // 用 setAllowedOrigins 而不是 setAllowedOriginPatterns：
 // 后者允许通配符（如 *），而 allowCredentials=true 与通配符同时使用
 // 会被浏览器拒绝。显式列出具体来源最安全。
 configuration.setAllowedOrigins(origins);
 // 前端把 JWT 放在 Authorization 头里，而不是 Cookie，
 // 但保留 credentials=true 以兼容将来可能的 Cookie 方案
 configuration.setAllowCredentials(true);
 configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
 configuration.setAllowedHeaders(List.of("*"));
 // 流式对话返回的 X-Accel-Buffering 需要前端能读到（见 chat 模块）
 configuration.setExposedHeaders(List.of("Content-Disposition", "X-Accel-Buffering"));
 // 预检结果缓存 1 小时，减少 OPTIONS 往返
 configuration.setMaxAge(3600L);

 UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
 source.registerCorsConfiguration("/**", configuration);
 return source;
 }
}
