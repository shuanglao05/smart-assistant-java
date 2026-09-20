package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.dto.NotificationDtos;
import com.ipas.assistant.entity.Notification;
import com.ipas.assistant.repository.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 通知业务逻辑。对应 。
 *
 * <p>三条写入来源：
 * <ol>
 * <li>前端手动新建（本类的 create）；</li>
 * <li>日程提醒后台任务（{@code ScheduleReminderJob}）；</li>
 * <li>Agent 的 {@code notify_user} 工具（第三阶段）。</li>
 * </ol>
 * 后两者都应当调用本类，避免各自拼 Notification 实体时漏字段（比如忘了设 type）。
 */
@Service
public class NotificationService {

 /** 列表条数上限，与早期设计 {@code Query(50, ge=1, le=200)} 的上界一致。 */
 private static final int MAX_LIMIT = 200;

 private final NotificationRepository notificationRepository;

 public NotificationService(NotificationRepository notificationRepository) {
 this.notificationRepository = notificationRepository;
 }

 /**
 * 通知列表。
 *
 * <p>参数被强制夹在 1~200 之间而不是直接透传给数据库：
 * 前端虽然会带 {@code limit}，但不能假设它一定合法 ——
 * 有人手工构造 {@code ?limit=999999} 就能一次性把整表拉出来。
 * 早期设计靠 数据校验框架 的 {@code Query(ge=1, le=200)} 兜住，
 * 这里用同样的夹取逻辑（越界时静默夹到边界，而不是报 422，
 * 因为"多要几条"不是恶意输入，给个上限结果更友好）。
 */
 @Transactional(readOnly = true)
 public List<NotificationDtos.Out> list(Long userId, boolean unreadOnly, int limit) {
 int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
 PageRequest page = PageRequest.of(0, safeLimit);

 List<Notification> rows = unreadOnly
 ? notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId, page)
 : notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, page);

 return rows.stream().map(NotificationDtos.Out::from).toList();
 }

 /** 未读数（铃铛红点）。 */
 @Transactional(readOnly = true)
 public long unreadCount(Long userId) {
 return notificationRepository.countByUserIdAndIsReadFalse(userId);
 }

 /** 手动新建通知。 */
 @Transactional
 public NotificationDtos.Out create(Long userId, NotificationDtos.Create payload) {
 Notification n = new Notification();
 n.setUserId(userId);
 n.setTitle(payload.title());
 n.setBody(payload.body());
 // 前端没传 type 时不要覆盖成 null：@PrePersist 只处理 null，
 // 这里显式判空以保持"缺省 info"的语义
 if (payload.type() != null && !payload.type().isBlank()) {
 n.setType(payload.type());
 }
 return NotificationDtos.Out.from(notificationRepository.save(n));
 }

 /**
 * 标记单条已读 / 未读。
 *
 * <p>{@code isRead} 作为查询参数且默认 true —— 早期设计签名为
 * {@code mark_notification(nid, is_read: bool = True)}。
 * 也就是说 {@code PATCH /api/notifications/5} 不带参数就表示"标记已读"，
 * 这条默认值是契约的一部分，别改成必填。
 */
 @Transactional
 public NotificationDtos.Out markRead(Long userId, Long notificationId, boolean isRead) {
 Notification n = notificationRepository.findByIdAndUserId(notificationId, userId)
 .orElseThrow(() -> ApiException.notFound("通知不存在"));
 n.setIsRead(isRead);
 return NotificationDtos.Out.from(n);
 }

 /** 全部已读。用批量 UPDATE，理由见 Repository 注释。 */
 @Transactional
 public void markAllRead(Long userId) {
 notificationRepository.markAllReadByUserId(userId);
 }

 /** 清空该用户的全部通知（早期设计返回 {@code {"status":"cleared"}}）。 */
 @Transactional
 public void clearAll(Long userId) {
 notificationRepository.deleteByUserId(userId);
 }
}
