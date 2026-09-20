package com.ipas.assistant.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节假日模块响应体。对应 的返回结构。
 *
 * <p>字段名用 camelCase，由 {@code application.yml} 的
 * {@code jackson.property-naming-strategy: SNAKE_CASE} 统一序列化成 snake_case。
 * 本模块的字段都是单词（off / work / name / wage / year / source / days），
 * 转换前后一致，但键名 {@code "2026-10-01"} 属于 Map 的 key，
 * <b>不受命名策略影响</b>，必须原样保留（前端按这个格式查表）。
 */
public final class CalendarDtos {

 private CalendarDtos() {
 }

 /**
 * 某一天的属性。
 *
 * <p><b>off 与 work 互为反值</b>（这是数据源的语义决定的）：
 * 一天要么是"放假"、要么是"调休补班"，没有第三种状态。
 * 前端据此显示「休」或「班」角标。
 *
 * @param off true = 放假
 * @param work true = 调休补班（周末但要上班）
 * @param name 节日名（如「国庆节」）或「某某补班」
 * @param wage 薪资倍数：3 = 法定节假日核心日、2 = 假期其余天、1 = 补班
 */
 public record HolidayDay(
 boolean off,
 boolean work,
 String name,
 int wage
 ) {
 }

 /**
 * 某一年的放假安排。
 *
 * @param year 年份
 * @param source 数据来源：{@code cache}（磁盘缓存）/ {@code timor}（在线拉取）/
 * {@code unavailable}（拉取失败，前端退化为只显示传统节日名）
 * @param days 日期 → 当天属性，键形如 {@code "2026-10-01"}
 */
 public record Holidays(
 int year,
 String source,
 Map<String, HolidayDay> days
 ) {
 /**
 * 构造响应。
 *
 * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of()}：
 * 缓存/接口返回的顺序应当被保留，这样前端按日序遍历时顺序稳定，
 * 也让"同一份数据两次请求返回完全一致"更容易验证。
 */
 public static Holidays of(int year, String source, Map<String, HolidayDay> days) {
 return new Holidays(year, source, days == null ? new LinkedHashMap<>() : days);
 }
 }

 /** 数据源标识常量（避免各处手写字符串拼错）。 */
 public static final String SOURCE_CACHE = "cache";
 public static final String SOURCE_TIMOR = "timor";
 public static final String SOURCE_UNAVAILABLE = "unavailable";
}
