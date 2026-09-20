package com.ipas.assistant.repository;

import com.ipas.assistant.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 消息仓储。对应 里的消息查询与 里的落库逻辑。
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

 /** 打开某个会话时按时间正序拉全部消息（id 递增即时间序，比按时间字段排更稳）。 */
 List<Message> findByConversationIdOrderByIdAsc(Long conversationId);

 /** 消息条数：用于「首轮提问自动命名会话」的判断（早期设计数了 count 后判断是否为 0）。 */
 long countByConversationId(Long conversationId);

 /**
 * 取某会话最后一条用户消息（用于 regenerate「重新生成」）。
 *
 * <p>倒序取第一条 = 最后一条。注意这里<b>不需要</b>在内存里过滤，
 * 直接交给数据库排序取一条，比把所有消息拉出来再筛更省。
 */
 Optional<Message> findFirstByConversationIdAndRoleOrderByIdDesc(Long conversationId, String role);

 /** 单条消息：用于校验归属后再删除。 */
 Optional<Message> findByIdAndConversationId(Long id, Long conversationId);

 /**
 * 找「某条用户消息之后的第一条助手回答」。
 * 用于删除消息时的<b>成对删除</b>（删提问要连带删掉它的回答）。
 */
 Optional<Message> findFirstByConversationIdAndRoleAndIdGreaterThanOrderByIdAsc(
 Long conversationId, String role, Long id);

 /**
 * 找「某条助手回答之前的最后一条用户提问」。
 * 同样是成对删除用（删回答要连带删掉它的提问）。
 */
 Optional<Message> findFirstByConversationIdAndRoleAndIdLessThanOrderByIdDesc(
 Long conversationId, String role, Long id);

 /** 删除某会话的全部消息（删会话时先清消息）。 */
 void deleteByConversationId(Long conversationId);

 /** 删除某会话里 id 大于某值、且角色为 assistant 的消息（regenerate 时清掉旧回答）。 */
 void deleteByConversationIdAndRoleAndIdGreaterThan(Long conversationId, String role, Long id);

 /**
 * 跨会话消息正文检索：查当前用户所有会话里正文命中关键词的消息，连同会话标题一起返回。
 *
 * <p><b>为什么需要一条自定义 JPQL</b>：{@code Message} 里 {@code conversationId}
 * 是一个普通字段（不是 JPA 关联），所以没法用「方法名派生查询」穿透到
 * {@code Conversation.userId} 上做过滤。这里用 JPA 2.1+ 支持的
 * <b>实体连接</b>（{@code JOIN ... ON 条件}）显式把两张表按 id 连起来。
 *
 * <p>返回 {@code Object[]{Message, String title}}：一次查询把标题带出来，
 * 避免"先查消息、再逐个查标题"的 N+1 问题
 * （一次检索可能命中几十条消息，逐条查标题就是几十次往返）。
 *
 * <p>排序按 {@code createdAt} 倒序，与早期设计一致 —— 搜历史时最近说过的更相关。
 */
 @Query("SELECT m, c.title FROM Message m JOIN Conversation c ON m.conversationId = c.id "
 + "WHERE c.userId = :userId AND m.role IN ('user', 'assistant') "
 + "AND LOWER(m.content) LIKE LOWER(CONCAT('%', :q, '%')) "
 + "ORDER BY m.createdAt DESC")
 List<Object[]> searchInUserConversations(@Param("userId") Long userId,
 @Param("q") String q,
 Pageable pageable);
}
