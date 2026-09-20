package com.ipas.assistant.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipas.assistant.common.ErrorBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 认证 / 授权失败的响应处理。
 *
 * <p>对应 里这一行：
 * <pre>cred = HTTPException(401, "认证失败", headers={"WWW-Authenticate": "Bearer"})</pre>
 *
 * <p><b>为什么不能省</b>：若不自定义，Spring Security 默认会返回
 * {@code WWW-Authenticate: Basic} 并触发浏览器的原生账号密码弹窗——在前后端分离的
 * 应用里这是个很突兀的体验。而且它的响应体是空的，前端 axios 拦截器拿到的
 * {@code err.response.data} 没有 {@code detail} 字段。
 *
 * <p>前端的 401 处理逻辑（{@code api/client.ts}）是：清掉 localStorage 里的
 * token 与 username，然后 {@code window.location.reload()} 踢回登录页。
 * 这个逻辑只依赖<b>状态码</b>是 401，所以这里只要保证状态码正确、并附带
 * {@code WWW-Authenticate: Bearer} 头即可与前端完全兼容。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

 private final ObjectMapper objectMapper;

 public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
 this.objectMapper = objectMapper;
 }

 /**
 * 未认证（没有 token、token 无效或已过期）→ 401。
 */
 @Override
 public void commence(HttpServletRequest request,
 HttpServletResponse response,
 AuthenticationException authException) throws IOException {
 writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "认证失败", true);
 }

 /**
 * 已认证但无权限 → 403。
 *
 * <p>本项目目前只有「登录用户」一种身份，没有角色/权限分级，所以这个分支
 * 正常情况下不会被触发。保留它是为了将来加管理端时不用再改安全配置。
 */
 @Override
 public void handle(HttpServletRequest request,
 HttpServletResponse response,
 AccessDeniedException accessDeniedException) throws IOException {
 writeJson(response, HttpServletResponse.SC_FORBIDDEN, "无权访问", false);
 }

 /**
 * 输出 接口框架 风格的 {@code {"detail": "..."}}。
 *
 * @param withWwwAuthenticate 是否附带 {@code WWW-Authenticate: Bearer} 头
 * （仅 401 需要，且必须用 Bearer 而不是默认的 Basic）
 */
 private void writeJson(HttpServletResponse response,
 int status,
 String detail,
 boolean withWwwAuthenticate) throws IOException {
 response.setStatus(status);
 response.setContentType(MediaType.APPLICATION_JSON_VALUE);
 // 必须显式指定 UTF-8：否则中文提示在部分客户端会变成乱码
 response.setCharacterEncoding(StandardCharsets.UTF_8.name());
 if (withWwwAuthenticate) {
 response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
 }
 response.getWriter().write(objectMapper.writeValueAsString(ErrorBody.of(detail)));
 }
}
