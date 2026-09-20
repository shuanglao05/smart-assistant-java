package com.ipas.assistant.common;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 时间工具：统一「本项目的 UTC 时间」取法。
 *
 * <p><b>【时间语义约定 —— 全项目唯一出处，改动只在这里改】</b>
 *
 * <p>本项目存的时间是 <b>UTC 墙上时间</b>，用 {@link LocalDateTime} 承载
 * （不带时区信息）。这是刻意保持与原 早期实现一致的语义 ——
 * 早期设计用 {@code datetime.now(timezone.utc)} 生成时间，并以 naive 形式落库。
 *
 * <h2>为什么要理解这件事（三个连带后果）</h2>
 *
 * <ol>
 * <li><b>不能在图省事改成 {@code LocalDateTime.now()}</b>。那是服务器本地时间，
 * 会让「容器时区一改，历史时间就换了含义」。必须显式指定 {@code ZoneOffset.UTC}。</li>
 *
 * <li><b>不能改成 {@code Instant} 或 {@code OffsetDateTime}</b>。
 * 它们在 Jackson 默认配置下会输出带 {@code Z} 后缀的字符串
 * （如 {@code 2026-09-16T08:28:30Z}）。而前端是按<b>无时区字符串</b>解析的
 * （{@code new Date("2026-09-16T08:28:30")} 走本地时区分支），
 * 一旦带上 Z，所有时间会立刻偏 8 小时。</li>
 *
 * <li><b>JDBC 连接参数必须禁掉时区转换</b>，否则会出现「库里存的值 ≠ 接口返回的值」。
 * 参见 {@code application.yml} 里的 {@code connectionTimeZone=LOCAL&preserveInstants=false}
 * 及其注释 —— 这是实测踩出来的坑，光看接口发现不了。</li>
 * </ol>
 *
 * <h2>已知的显示问题（既有问题，非本次迁移引入）</h2>
 *
 * <p>前端 {@code NotificationBell.tsx} 等位置用 {@code new Date(created_at).toLocaleString()}，
 * JS 会把不带时区的字符串按<b>本地时区</b>解析，于是界面上显示的时间比真实本地时间
 * 早 8 小时（因为存的是 UTC）。
 *
 * <p>经与项目负责人确认，本次迁移<b>保持与早期设计一致的 UTC 语义</b>，
 * 界面显示问题作为独立的修复项处理（若将来要改，正确做法是让前端在解析前补上
 * {@code Z}，而不是把后端改成存本地时间 —— 后者会让已迁移的历史数据语义不一致）。
 */
public final class Times {

 private Times() {
 // 工具类，禁止实例化
 }

 /**
 * 当前 UTC 时间，<b>精度截到微秒</b>。
 *
 * <p>{@code truncatedTo(MICROS)} 这一步不能省，这是实测撞出来的坑：
 * <ul>
 * <li>{@code LocalDateTime.now()} 在本机（JDK 21 / Windows）能给出<b>纳秒</b>级精度，
 * 序列化成 {@code 2026-09-16T08:45:09.5211059}（7 位小数）；</li>
 * <li>而 MySQL 的 {@code DATETIME(6)} 只存 6 位，插入时会<b>四舍五入</b>
 * 成 {@code .521106}。</li>
 * </ul>
 * 于是同一条记录出现「创建接口返回 …5211059」而「查询接口返回 …521106」
 * —— 差在最后一位。虽然无实质影响，但这种"刚建完立刻查就对不上"的现象
 * 会让人怀疑数据出了问题。
 *
 * <p>截到微秒后，Java 生成的值与数据库能存的精度<b>完全一致</b>，
 * 不再需要舍入，两处返回值逐位相同。
 *
 * @return 形如 {@code 2026-09-16T08:28:30.123456} 的 UTC 墙上时间（最多 6 位小数）
 */
 public static LocalDateTime nowUtc() {
 return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
 }

 /**
 * 把 epoch 秒转成本地时区的「HH:mm」文本，供日程提醒文案使用。
 *
 * <p>注意这里<b>刻意用系统默认时区</b>而不是 UTC：日程的 {@code start_at}
 * 存的是 epoch 秒（绝对时刻），而提醒文案是给人看的，
 * 必须显示用户本地时间。早期设计写的是 {@code datetime.fromtimestamp(秒数)}，
 * 同样是本地时区，两者一致。
 *
 * @param epochSeconds epoch 秒
 * @return 形如 {@code 14:30} 的文本
 */
 public static String formatLocalHhMm(double epochSeconds) {
 return LocalDateTime.ofEpochSecond(
 (long) epochSeconds, 0, ZoneOffset.systemDefault().getRules()
 .getOffset(java.time.Instant.ofEpochSecond((long) epochSeconds)))
 .toLocalTime()
 .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
 }
}
