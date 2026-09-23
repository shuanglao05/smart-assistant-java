package com.ipas.assistant.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 只读展示模式的判定逻辑（纯函数，便于单测）。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>把系统在线演示给别人看（答辩、分享给同学）时，对方可以随手改你的待办、删你的笔记、
 * 清空知识库 —— 演示完数据就乱了。这个模式打开后，<b>除浏览与登录之外的写操作一律拒绝</b>，
 * 于是"只读演示"这件事由代码保证，不必靠嘴叮嘱。
 *
 * <h2>为什么判定逻辑要单独抽出来</h2>
 *
 * <p>过滤器的实现（拿 request / 写 response）不好测；而"什么该拦、什么该放"才是真正
 * 容易写错的部分（放行了登录就登不进去，拦掉了 GET 就什么都看不见）。
 * 抽成纯函数后，各种方法与路径的组合都能穷举断言。
 */
@Component
public class PreviewModeGuard {

    private final boolean enabled;

    public PreviewModeGuard(@Value("${app.preview-mode.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** 当前是否处于只读展示模式。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 这次请求是否应当被拒绝。
     *
     * <p>放行三类：
     * <ol>
     * <li><b>浏览类方法</b>（GET / HEAD / OPTIONS）—— 只读模式要的就是"能看"；</li>
     * <li><b>登录与注册</b>（{@code /api/auth/**}）—— 不放行的话演示时连门都进不去；</li>
     * <li><b>非 /api 路径</b>（前端静态资源、接口文档页面等）—— 它们不是写数据的入口。</li>
     * </ol>
     *
     * @param method HTTP 方法
     * @param path   请求路径（不含查询串）
     */
    public boolean shouldBlock(String method, String path) {
        if (!enabled) {
            return false;
        }
        // 拿不到方法时保守拒绝：宁可多拦一个，也不放过一个写操作
        if (method == null) {
            return true;
        }
        String m = method.toUpperCase();
        if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) {
            return false;
        }
        if (path == null) {
            return true;
        }
        if (path.startsWith("/api/auth/")) {
            return false;
        }
        // 只管业务接口；静态资源与文档页面不受影响
        return path.startsWith("/api/");
    }
}
