package com.ipas.assistant.repository;

import com.ipas.assistant.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 日程仓储。对应 。
 *
 * <p>最后一个方法是<b>后台提醒任务</b>用的（原 {@code check_upcoming_reminders}），
 * 它每 30 秒执行一次，找出「尚未提醒、且开始时刻落在未来 5 分钟内」的日程。
 * 三个条件缺一不可：
 * <ul>
 * <li>{@code reminded = false} —— 否则会反复推送同一条；</li>
 * <li>{@code startAt > now} —— 排除已经开始的（"提前 5 分钟提醒"，
 * 过期的不补发，否则用户会收到一堆历史日程的提醒）；</li>
 * <li>{@code startAt <= now + 300} —— 只取即将到来的。</li>
 * </ul>
 * 这个查询每次都要扫 {@code scheduled} 全表范围，所以 {@code start_at} 上建了索引。
 */
@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

 /** 日历视图：按开始时刻正序（前端按时间轴铺开，顺序不能乱）。 */
 List<Schedule> findByUserIdOrderByStartAtAsc(Long userId);

 Optional<Schedule> findByIdAndUserId(Long id, Long userId);

 /** 后台提醒任务的候选集（见接口注释里的三个条件）。 */
 List<Schedule> findByRemindedFalseAndStartAtGreaterThanAndStartAtLessThanEqual(
 Double after, Double before);
}
