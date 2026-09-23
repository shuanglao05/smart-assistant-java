package com.ipas.assistant.repository;

import com.ipas.assistant.entity.ChatTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行阶段记录的仓储。
 */
@Repository
public interface ChatTraceRepository extends JpaRepository<ChatTrace, Long> {

    /**
     * 取某轮回答的全部阶段，按写入顺序（= 阶段发生顺序）。
     *
     * <p>排 {@code id} 而不是 {@code created_at}：同一轮里几个阶段可能落在同一微秒
     * （准备阶段本身就很快），按时间排会出现顺序抖动；自增 id 是稳定的写入次序。
     */
    List<ChatTrace> findByConversationIdAndExchangeIdOrderByIdAsc(Long conversationId, Long exchangeId);

    /**
     * 清理过期记录。
     *
     * <p>为什么要清理：每轮对话都会写好几行，长期运行后这张表只增不减。
     * 它只是"可观测性"数据，保留一段时间即可，不是业务数据。
     */
    @Modifying
    @Transactional
    @Query("delete from ChatTrace t where t.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
