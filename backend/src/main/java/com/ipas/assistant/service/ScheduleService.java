package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.common.Times;
import com.ipas.assistant.dto.ScheduleDtos;
import com.ipas.assistant.entity.Notification;
import com.ipas.assistant.entity.Schedule;
import com.ipas.assistant.repository.NotificationRepository;
import com.ipas.assistant.repository.ScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 日程业务逻辑。对应 。
 *
 * <p>除增删改查外，本类还承担「提前 5 分钟提醒」的核心逻辑
 * （{@link #issueUpcomingReminders()}），由
 * {@code ScheduleReminderJob} 每 30 秒调用一次。
 *
 * <p><b>为什么把提醒逻辑放 Service 而不是 Job 里</b>：
 * 这样它可以被单独调用 —— 排查「为什么没收到提醒」时，
 * 可以在测试或临时接口里直接触发一次，而不必等 30 秒的定时周期。
 * 定时器只负责"什么时候跑"，业务规则属于 Service。
 */
@Service
public class ScheduleService {

 private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

 /** 提前量：5 分钟（早期设计 {@code REMIND_AHEAD_SECONDS = 300}）。 */
 public static final int REMIND_AHEAD_SECONDS = 300;

 private final ScheduleRepository scheduleRepository;
 private final NotificationRepository notificationRepository;

 public ScheduleService(ScheduleRepository scheduleRepository,
 NotificationRepository notificationRepository) {
 this.scheduleRepository = scheduleRepository;
 this.notificationRepository = notificationRepository;
 }

 /** 列表：按开始时刻正序。 */
 @Transactional(readOnly = true)
 public List<ScheduleDtos.Out> list(Long userId) {
 return scheduleRepository.findByUserIdOrderByStartAtAsc(userId)
 .stream()
 .map(ScheduleDtos.Out::from)
 .toList();
 }

 /** 新建日程。{@code reminded} 由 @PrePersist 置 false。 */
 @Transactional
 public ScheduleDtos.Out create(Long userId, ScheduleDtos.Create payload) {
 Schedule row = new Schedule();
 row.setUserId(userId);
 row.setTitle(payload.title().strip());
 row.setStartAt(payload.startAt());
 row.setNote(payload.note());
 return ScheduleDtos.Out.from(scheduleRepository.save(row));
 }

 /**
 * 更新日程。
 *
 * <p><b>⚠️ 关键业务细节：改了开始时间，必须把 reminded 重置为 false。</b>
 * <br>否则会出现这种情况：用户有个 10:00 的日程，已经提醒过了；
 * 用户把它改到明天 10:00 —— 因为 reminded 还是 true，
 * 这条日程<b>永远不会再提醒</b>。用户会觉得"改时间之后提醒就失灵了"。
 * 早期设计专门有一行 {@code row.reminded = False} 处理这件事，这里必须保留。
 */
 @Transactional
 public ScheduleDtos.Out update(Long userId, Long scheduleId, ScheduleDtos.Update payload) {
 Schedule row = requireOwned(userId, scheduleId);

 if (payload.title() != null) {
 row.setTitle(payload.title().strip());
 }
 if (payload.note() != null) {
 row.setNote(payload.note());
 }
 if (payload.startAt() != null) {
 row.setStartAt(payload.startAt());
 row.setReminded(false);
 }
 return ScheduleDtos.Out.from(row);
 }

 /** 删除日程。 */
 @Transactional
 public void delete(Long userId, Long scheduleId) {
 scheduleRepository.delete(requireOwned(userId, scheduleId));
 }

 /**
 * 扫描并发出即将开始的日程提醒，返回本次生成的提醒条数。
 * 对应 {@code check_upcoming_reminders()}。
 *
 * <p>筛选条件（见 Repository 注释）保证同一日程只会提醒一次；
 * 发完立刻置 {@code reminded = true}，与通知写入在<b>同一事务</b>内提交，
 * 避免"通知写了但标记没落库"导致下一轮重复推送。
 *
 * <p>提醒文案也照抄原文：标题 {@code 日程提醒：<标题>}，
 * 正文 {@code 14:30 开始 · <备注>}（备注为空时省略分隔符）。
 * 时间用<b>本地时区</b>格式化 —— 日程的 startAt 是 epoch 秒（绝对时刻），
 * 提醒文案是给人看的，必须显示用户本地时间。
 */
 @Transactional
 public int issueUpcomingReminders() {
 double now = System.currentTimeMillis() / 1000.0;
 List<Schedule> upcoming = scheduleRepository
 .findByRemindedFalseAndStartAtGreaterThanAndStartAtLessThanEqual(
 now, now + REMIND_AHEAD_SECONDS);

 if (upcoming.isEmpty()) {
 return 0;
 }

 for (Schedule s : upcoming) {
 String hhmm = Times.formatLocalHhMm(s.getStartAt());
 String note = s.getNote();
 String body = hhmm + " 开始" + (note != null && !note.isBlank() ? " · " + note : "");

 Notification n = new Notification();
 n.setUserId(s.getUserId());
 n.setTitle("日程提醒：" + s.getTitle());
 n.setBody(body);
 n.setType("remind");
 notificationRepository.save(n);

 s.setReminded(true);
 }

 log.debug("生成了 {} 条日程提醒", upcoming.size());
 return upcoming.size();
 }

 private Schedule requireOwned(Long userId, Long scheduleId) {
 return scheduleRepository.findByIdAndUserId(scheduleId, userId)
 .orElseThrow(() -> ApiException.notFound("日程不存在"));
 }
}
