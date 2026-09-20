package com.ipas.assistant.repository;

import com.ipas.assistant.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 通知仓储。对应 。
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

 /**
 * 列表：按创建时间倒序，取前 N 条。
 *
 * <p>用 {@link Pageable} 而不是把 limit 拼进方法名 —— 方法名里带数字
 * （如 {@code findTop50By...}）会把条数写死在方法签名里，
 * 而前端是可以通过 query 参数传 limit 的（1~200）。
 */
 List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

 /** 只要未读的（前端"仅看未读"开关）。 */
 List<Notification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId, Pageable pageable);

 Optional<Notification> findByIdAndUserId(Long id, Long userId);

 /** 未读数量（铃铛上的红点数字）。 */
 long countByUserIdAndIsReadFalse(Long userId);

 /**
 * 全部标记为已读。
 *
 * <p>用 {@code @Modifying} 的批量 UPDATE 而不是「先查出来再逐个改」：
 * 用户可能有上百条未读通知，逐条改意味着上百次 UPDATE 往返。
 *
 * <p>注意 {@code @Modifying} 方法必须在事务里执行（Service 上标了
 * {@code @Transactional}），否则 Spring Data 会直接抛异常。
 * 返回 int 是本方法实际影响的行数，调用方一般不关心。
 */
 @Modifying
 @Query("UPDATE Notification n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false")
 int markAllReadByUserId(@Param("userId") Long userId);

 /** 清空该用户的全部通知。 */
 void deleteByUserId(Long userId);
}
