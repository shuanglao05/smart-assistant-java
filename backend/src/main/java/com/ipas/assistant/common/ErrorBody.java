package com.ipas.assistant.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应体。
 *
 * <p><b>为什么必须单独定义一个类，而不直接返回 Map</b>：
 * 这是为了严格对齐 接口框架 的错误响应格式。接口框架 的
 * {@code HTTPException(status_code, detail)} 序列化出来永远是：
 *
 * <pre>{"detail": "用户名已存在"}</pre>
 *
 * <p>前端 {@code frontend/src/api/client.ts} 的 axios 拦截器虽然只对 401 做了特殊处理
 * （清 token 并刷新回登录页），但各个页面组件会读取 {@code err.response.data.detail}
 * 来给用户提示。如果后端换成 Spring 默认的错误格式
 * （{@code {"timestamp":..., "status":400, "error":"Bad Request", "path":"..."}}），
 * 前端所有错误提示都会变成 "undefined"。
 *
 * <p>所以这个类的存在意义就是：<b>把 Java 的错误格式伪装成 接口框架 的样子</b>，
 * 从而让 React 前端一行都不用改。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ErrorBody(String detail) {

 /**
 * 构造一个只有 detail 的错误体。
 *
 * @param detail 面向用户的中文错误说明（与早期设计的文案保持一致）
 */
 public static ErrorBody of(String detail) {
 return new ErrorBody(detail);
 }
}
