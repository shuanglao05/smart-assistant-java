package com.ipas.assistant.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读展示模式判定逻辑的单元测试。
 *
 * <p>这里的两个方向都会"把演示搞砸"，所以都要钉死：
 * <ul>
 * <li><b>拦多了</b>：把 GET 或登录拦掉 → 演示时页面打不开 / 登不进去；</li>
 * <li><b>拦少了</b>：漏掉某个写方法 → 演示的人照样能把数据改乱（这正是这个功能存在的意义）。</li>
 * </ul>
 */
class PreviewModeGuardTest {

    private static PreviewModeGuard enabled() {
        return new PreviewModeGuard(true);
    }

    private static PreviewModeGuard disabled() {
        return new PreviewModeGuard(false);
    }

    @Test
    @DisplayName("关闭时从不拦截（默认状态，行为与加这个功能之前完全一致）")
    void disabledNeverBlocks() {
        PreviewModeGuard guard = disabled();

        assertFalse(guard.isEnabled());
        for (String m : new String[]{"GET", "POST", "PUT", "PATCH", "DELETE"}) {
            assertFalse(guard.shouldBlock(m, "/api/todos"), m + " 在关闭模式下都不该被拦");
        }
    }

    @Test
    @DisplayName("开启时放行浏览类方法（只读模式要的就是'能看'）")
    void allowsReadMethods() {
        PreviewModeGuard guard = enabled();

        assertTrue(guard.isEnabled());
        assertFalse(guard.shouldBlock("GET", "/api/todos"));
        assertFalse(guard.shouldBlock("get", "/api/sessions/1/messages"));
        assertFalse(guard.shouldBlock("HEAD", "/api/files"));
        assertFalse(guard.shouldBlock("OPTIONS", "/api/kb/collections"));
    }

    @Test
    @DisplayName("开启时拦截全部写方法（这正是功能存在的意义）")
    void blocksAllWriteMethods() {
        PreviewModeGuard guard = enabled();

        assertTrue(guard.shouldBlock("POST", "/api/todos"));
        assertTrue(guard.shouldBlock("PUT", "/api/kb/collections/1"));
        assertTrue(guard.shouldBlock("PATCH", "/api/users/me"));
        assertTrue(guard.shouldBlock("DELETE", "/api/files/3"));
        assertTrue(guard.shouldBlock("delete", "/api/sessions/2"), "方法大小写不该影响判定");
    }

    @Test
    @DisplayName("开启时放行登录与注册（不放行的话演示时连门都进不去）")
    void allowsAuthEndpoints() {
        PreviewModeGuard guard = enabled();

        assertFalse(guard.shouldBlock("POST", "/api/auth/login"));
        assertFalse(guard.shouldBlock("POST", "/api/auth/register"));
    }

    @Test
    @DisplayName("开启时不影响非 /api 路径（前端静态资源、接口文档页面照常）")
    void ignoresNonApiPaths() {
        PreviewModeGuard guard = enabled();

        assertFalse(guard.shouldBlock("POST", "/swagger-ui/index.html"));
        assertFalse(guard.shouldBlock("POST", "/"));
        assertFalse(guard.shouldBlock("POST", "/assets/index.js"));
    }

    @Test
    @DisplayName("拿不到方法或路径时保守拦截（宁可多拦一个，也不放过一个写操作）")
    void blocksWhenInfoMissing() {
        PreviewModeGuard guard = enabled();

        assertTrue(guard.shouldBlock(null, "/api/todos"));
        assertTrue(guard.shouldBlock("POST", null));
    }
}
