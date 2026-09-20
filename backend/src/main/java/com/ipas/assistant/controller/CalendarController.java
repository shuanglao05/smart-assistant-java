package com.ipas.assistant.controller;

import com.ipas.assistant.dto.CalendarDtos;
import com.ipas.assistant.security.AuthUser;
import com.ipas.assistant.service.CalendarService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 节假日接口。对应 的
 * {@code APIRouter(prefix="/api/calendar")}。
 *
 * <p>本类标了 {@code @Validated} —— 这是让 {@code @RequestParam} 上的
 * {@code @Min}/{@code @Max} 生效的前提（不加它，这些注解会被<b>静默忽略</b>，
 * 越界年份会直接打到数据源）。校验失败由全局异常处理器转成
 * <b>422</b>，与 接口框架 的 {@code Query(ge=1970, le=2100)} 行为一致。
 */
@RestController
@RequestMapping("/api/calendar")
@Validated
public class CalendarController {

 private final CalendarService calendarService;

 public CalendarController(CalendarService calendarService) {
 this.calendarService = calendarService;
 }

 /**
 * GET /api/calendar/holidays —— 某年的放假 / 调休补班安排。
 *
 * <p>{@code days} 形如
 * {@code {"2026-10-01": {"off": true, "work": false, "name": "国庆节", "wage": 3}}}；
 * {@code source} 标明数据来源，前端据此决定要不要提示"数据可能不是最新"。
 *
 * <p><b>这个接口不会失败</b>：网络不通时返回 {@code source="unavailable"} + 空 days，
 * 前端退化为只显示传统节日名。日历是辅助视图，不该因为它弹错误打断用户。
 */
 @GetMapping("/holidays")
 public ResponseEntity<CalendarDtos.Holidays> holidays(
 @AuthenticationPrincipal AuthUser me,
 @RequestParam("year")
 @Min(value = 1970, message = "年份需在 1970 ~ 2100 之间")
 @Max(value = 2100, message = "年份需在 1970 ~ 2100 之间") int year) {
 return ResponseEntity.ok(calendarService.holidays(me.id(), year));
 }
}
