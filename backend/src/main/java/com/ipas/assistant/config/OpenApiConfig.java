package com.ipas.assistant.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档（OpenAPI / Swagger UI）的元信息与鉴权配置。
 *
 * <h2>为什么只配一个 Bean，而不给每个接口写注解</h2>
 *
 * <p>springdoc 会自动扫描所有 {@code @RestController}，接口清单、参数、响应结构都由代码本身推导出来，
 * 不需要逐个手写。真正缺的只有两样东西：
 * <ol>
 * <li><b>文档本身的说明</b>（标题、版本、怎么用）—— 就是这个 Bean；</li>
 * <li><b>怎么在界面上带 token</b> —— 见下面的 bearer 方案。</li>
 * </ol>
 *
 * <p>第 2 点尤其关键：本项目除登录/注册/健康检查外的接口<b>全部要求 JWT</b>，
 * 没有这个配置时 Swagger UI 里点任何接口都只会得到 401，文档等于不能用。
 * 配好之后界面上会出现 "Authorize" 按钮，填入 token 即可正常调试。
 *
 * <h2>版本号取自 AppVersion</h2>
 * <p>与 {@code /api/health} 用的是同一个来源（仓库根目录的 VERSION 文件），
 * 保证"文档里显示的版本"与"接口返回的版本"不会各说各话。
 */
@Configuration
public class OpenApiConfig {

    /** 安全方案名，仅用于在代码内互相引用。 */
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI ipasOpenApi(AppVersion appVersion) {
        return new OpenAPI()
                .info(new Info()
                        .title("IPAS 智能助手 · 接口文档")
                        .version(appVersion.get())
                        .description("""
                                个人智能助理后端接口。涵盖：账号鉴权、会话与消息、待办 / 笔记 / 日程 / 课表、
                                技能、知识库与文件（含 RAG 检索）、模型接入与配置、站内通知、系统设置。

                                使用方式：
                                1. 先调 POST /api/auth/login 拿到 token；
                                2. 点右上角 Authorize，把 token 粘进去（不用加 "Bearer " 前缀）；
                                3. 之后所有接口都会自动带上鉴权头。

                                说明：流式对话（POST /api/chat/stream）返回的是 SSE 事件流，
                                不适合在本界面调试，请用前端的对话页。
                                """))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 access_token")))
                // 全局默认要求鉴权：这样界面上的"Authorize"按钮会对所有接口生效
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
