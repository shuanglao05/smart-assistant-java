package com.ipas.assistant.dto;

/**
 * 天气模块的响应体。对应 {@code GET /api/weather} 的返回
 * {@code {"city", "mode", "result"}}。
 */
public final class WeatherDtos {

 private WeatherDtos() {
 }

 /**
 * 天气查询结果。
 *
 * @param city 查询的城市（原样回显，便于前端在卡片上显示"你查的是哪个城市"）
 * @param mode {@code now} 实况 / {@code forecast} 预报
 * @param result <b>给人看的整段文本</b>，如「北京 当前 26°C，晴，湿度 40%，东南风 3级」
 */
 public record WeatherResult(String city, String mode, String result) {
 }
}
