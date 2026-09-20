package com.ipas.assistant.repository;

import com.ipas.assistant.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 会话仓储。对应 里的会话查询。
 *
 * <p>所有方法都带 {@code userId} 条件 —— 这是<b>多用户隔离的唯一屏障</b>
 * （数据库没建外键约束，见 schema-mysql.sql 的说明）。
 * 前端左侧会话列表、消息列表、改标题、删会话，都必须走带 userId 的方法，
 * 绝不要用 {@code findById} 直接取（那会越权读到别人的会话）。
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

 /** 会话列表：按最后更新时间倒序（最近聊过的在最上面）。 */
 List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);

 /**
 * 取「属于该用户」的某个会话。
 *
 * <p>返回值用 {@code Optional} 而不是直接返回实体：找不到时应当明确 404，
 * 而不是拿个 null 继续往下走（那样会在更远的地方抛 NPE，堆栈跟真实原因对不上）。
 */
 Optional<Conversation> findByIdAndUserId(Long id, Long userId);

 /** 清空全部会话（前端"清空所有对话"）。 */
 List<Conversation> findAllByUserId(Long userId);

 /**
 * 跨会话标题检索（不区分大小写子串匹配）。
 *
 * <p>{@code ContainingIgnoreCase} 会生成 {@code lower(字段) like lower(?)}
 * 并自动加两端的 {@code %}，等价于早期设计 ORM 框架 的 {@code .ilike("%q%")}。
 */
 List<Conversation> findByUserIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
 Long userId, String title, org.springframework.data.domain.Pageable pageable);
}
