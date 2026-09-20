package com.ipas.assistant.schedule;

import com.ipas.assistant.service.ScheduleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 日程提醒后台任务。对应 里的 {@code reminder_loop()} 协程。
 *
 * <p><b>原实现 vs 本实现</b>：
 * <table border="1">
 * <tr><th></th><th>原 早期实现</th><th>本 本实现</th></tr>
 * <tr><td>调度方式</td>
 * <td>{@code asyncio.create_task} 启一个常驻协程，
 * {@code while True: ... await asyncio.sleep(30)}</td>
 * <td>Spring 的 {@code @Scheduled(fixedDelay = 30000)}</td></tr>
 * <tr><td>线程模型</td>
 * <td>与请求处理同在一个事件循环里，靠 {@code await} 让出</td>
 * <td>独立线程池，与请求线程完全隔离</td></tr>
 * </table>
 *
 * <p>本实现在这方面更省心：原实现必须小心「同步的数据库操作会阻塞整个事件循环」，
 * 而 Java 的定时任务是独立线程，数据库是阻塞式 JDBC，不存在这个问题。
 *
 * <p><b>用 fixedDelay 而不是 fixedRate —— 这个选择很重要</b>：
 * <ul>
 * <li>{@code fixedDelay}：<b>上一次执行结束后</b>再等 30 秒。
 * 若某次扫描因为数据库慢花了 10 秒，下次在 40 秒后；
 * 执行之间不会重叠。</li>
 * <li>{@code fixedRate}：按固定节拍触发。若单次执行超过 30 秒，
 * 会有多次执行堆积重叠 —— 可能对同一条日程<b>重复发通知</b>。</li>
 * </ul>
 * 提醒任务宁可有延迟也不能重复推送，所以用 fixedDelay。
 *
 * <p><b>为什么整个循环体包在 try/catch 里</b>：
 * 原实现也一样（{@code except Exception: pass}）。定时任务一旦抛异常逃逸，
 * Spring 会记录错误但默认仍会继续下次调度——不过如果是配置问题，
 * 就会变成每 30 秒刷一条错误日志。这里显式捕获并只记一行 warn，
 * 保证「单次失败不影响后续轮询」这个语义明确写在代码里。
 */
@Component
public class ScheduleReminderJob {

 private static final Logger log = LoggerFactory.getLogger(ScheduleReminderJob.class);

 private final ScheduleService scheduleService;

 public ScheduleReminderJob(ScheduleService scheduleService) {
 this.scheduleService = scheduleService;
 }

 /**
 * 每 30 秒扫一次「即将在 5 分钟内开始且尚未提醒」的日程，写成站内通知。
 */
 @Scheduled(fixedDelay = 30_000)
 public void remindUpcomingSchedules() {
 try {
 int count = scheduleService.issueUpcomingReminders();
 if (count > 0) {
 log.info("已发出 {} 条日程提醒", count);
 }
 } catch (Exception e) {
 // 单次失败不能影响后续轮询（例如数据库短暂不可用）
 log.warn("日程提醒任务本轮执行失败，将在下个周期重试: {}", e.getMessage());
 }
 }
}
