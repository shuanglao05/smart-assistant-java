package com.ipas.assistant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 可选模型条目。对应 {@code config.llm_options()} 返回的字典结构，
 * 供前端的「切换模型」下拉框渲染。
 *
 * <p>字段含义（与早期设计逐字段对齐）：
 * <ul>
 * <li>{@code provider} —— {@code ollama}（本地）或 {@code cloud}（云端）；</li>
 * <li>{@code providerId} —— 多 API 接入时指向 {@code llm_providers.id}；
 * <b>本地 Ollama 与"默认云端配置"没有这个概念</b>；</li>
 * <li>{@code label} —— 下拉里显示的文字，如「本地 · qwen3:8b」「智谱 GLM · glm-5.2」；</li>
 * <li>{@code configured} —— 是否已具备可用凭据（false 时前端会提示"未配置"）；</li>
 * <li>{@code platform} / {@code contextWindow} / {@code contextWindowText} —— 分组与窗口提示。</li>
 * </ul>
 *
 * <p><b>⚠️ {@code providerId} 上的 {@code @JsonInclude(NON_NULL)} 不能省</b>：
 * 早期设计返回的字典里，本地 Ollama 那条<b>根本没有</b> {@code provider_id} 这个键，
 * 而不是键存在、值为 null。前端类型定义也是可选属性
 * （{@code provider_id?: number}）。若不排除 null，序列化出来会多一个
 * {@code "provider_id": null}，与原文的响应形状不一致 ——
 * 这类"多了一个 null 字段"的差异在排查时很费时间。
 *
 * <p>注意 {@code contextWindow} 则<b>保留</b> null（不加 NON_NULL）：
 * 前端类型明确写的是 {@code context_window?: number | null}，
 * 说明它会主动读这个 null 来判断"未知窗口"。
 */
public final class LlmOptionDtos {

 private LlmOptionDtos() {
 }

 /**
 * 单个可选模型。
 *
 * @param provider ollama / cloud
 * @param providerId 云端接入配置 id；本地与默认云端为 null（会被省略）
 * @param model 模型名
 * @param label 下拉显示文字
 * @param configured 是否已配置可用凭据
 * @param desc 补充说明
 * @param platform 平台分组名
 * @param contextWindow 上下文窗口 token 数；null = 未知
 * @param contextWindowText 窗口的易读文本（8K / 128K / 1M）
 */
 public record LlmOption(
 String provider,

 @JsonInclude(JsonInclude.Include.NON_NULL)
 Long providerId,

 String model,
 String label,
 boolean configured,
 String desc,
 String platform,
 Integer contextWindow,
 String contextWindowText
 ) {
 }
}
