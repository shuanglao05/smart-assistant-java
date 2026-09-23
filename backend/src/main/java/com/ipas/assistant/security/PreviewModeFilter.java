package com.ipas.assistant.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 只读展示模式的过滤器：判定该拦就把请求挡在业务逻辑之外。
 *
 * <h2>为什么用过滤器而不是在 Controller 里判断</h2>
 *
 * <p>写在 Controller 里意味着每个写接口都要记得加一行判断 —— 新增接口时漏写一处，
 * 这个口子就静默存在了。过滤器是<b>统一入口</b>，天然覆盖全部接口，
 * 也不会随着接口增加而失效。
 *
 * <h2>响应格式与全局一致</h2>
 *
 * <p>返回 403 + {@code {"detail":"..."}}，与 {@code GlobalExceptionHandler} 的格式一致 ——
 * 前端已有的错误处理逻辑（读 {@code detail}）不用改。
 *
 * <h2>与鉴权的关系</h2>
 *
 * <p>本过滤器不负责鉴权：未登录的请求会先被 Spring Security 拦下（401）。
 * 只有已经通过鉴权的写请求才会走到这里被判 403 —— 这个顺序是合理的，
 * "你没登录"比"系统在只读模式"更该先告诉用户。
 */
@Component
public class PreviewModeFilter extends OncePerRequestFilter {

    /** 面向用户的提示：说清"为什么被拒"以及"这不是故障"。 */
    private static final String MESSAGE = "当前为只读展示模式，仅开放浏览与检索";

    private final PreviewModeGuard guard;
    private final ObjectMapper objectMapper;

    public PreviewModeFilter(PreviewModeGuard guard, ObjectMapper objectMapper) {
        this.guard = guard;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (guard.shouldBlock(request.getMethod(), request.getRequestURI())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            // 用 ObjectMapper 而不是手拼 JSON：中文与引号都由它保证转义正确
            objectMapper.writeValue(response.getWriter(), Map.of("detail", MESSAGE));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
