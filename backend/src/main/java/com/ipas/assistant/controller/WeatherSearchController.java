package com.ipas.assistant.controller;

import com.ipas.assistant.dto.SearchDtos;
import com.ipas.assistant.dto.WeatherDtos;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.SearchService;
import com.ipas.assistant.service.WeatherService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 天气与历史检索两个小接口。
 *
 * <p>它们的路径前缀不同（{@code /api/weather} 与 {@code /api/search}），
 * 又都只有一个 GET 方法，所以合到一个控制器里，省掉两个几乎空的类。
 *
 * <p>本类标了 {@code @Validated} —— 这是让 {@code @RequestParam} 上的
 * {@code @Min}/{@code @Max} 生效的前提（不加它，这些注解会被静默忽略）。
 * 校验失败由全局异常处理器转成 <b>422</b>，与 接口框架 的
 * {@code Query(ge=..., le=...)} 行为一致。
 */
@RestController
@Validated
public class WeatherSearchController {

 private final WeatherService weatherService;
 private final SearchService searchService;

 public WeatherSearchController(WeatherService weatherService, SearchService searchService) {
 this.weatherService = weatherService;
 this.searchService = searchService;
 }

 /**
 * GET /api/weather —— 天气查询（实况或预报）。
 *
 * <p>三个参数与早期设计签名一致：
 * {@code city}（必填，1~不限）、{@code mode}（默认 now）、
 * {@code days}（默认 3，范围 1~4，仅 forecast 生效）。
 *
 * <p>注意返回的 {@code result} 是<b>一段人可读的文本</b>，
 * 未配置天气 Key 时它是一句提示而不是错误码 —— 前端直接把这段文字
 * 显示在天气卡片里，用户立刻知道要去配置。
 */
 @GetMapping("/api/weather")
 public ResponseEntity<WeatherDtos.WeatherResult> weather(
 @AuthenticationPrincipal AuthUser me,
 @RequestParam("city")
 @NotBlank(message = "请填写城市名")
 @Size(max = 50, message = "城市名最长 50 个字符") String city,

 @RequestParam(name = "mode", defaultValue = "now") String mode,

 @RequestParam(name = "days", defaultValue = "3")
 @Min(value = 1, message = "预报天数需在 1~4 之间")
 @Max(value = 4, message = "预报天数需在 1~4 之间") int days) {
 return ResponseEntity.ok(weatherService.query(city, mode, days));
 }

 /**
 * GET /api/search —— 跨会话历史检索。
 *
 * <p>{@code q} 的长度上限 100 与早期设计 {@code Query(..., max_length=100)} 一致。
 * 限长是必要的：关键词会参与 {@code LIKE '%...%' }，
 * 超长关键词会让数据库做大量无意义的匹配。
 */
 @GetMapping("/api/search")
 public ResponseEntity<List<SearchDtos.SearchHit>> search(
 @AuthenticationPrincipal AuthUser me,
 @RequestParam("q")
 @NotBlank(message = "请填写搜索关键词")
 @Size(max = 100, message = "关键词最长 100 个字符") String q) {
 return ResponseEntity.ok(searchService.search(me.id(), q));
 }
}
