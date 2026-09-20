package com.ipas.assistant.repository;

import com.ipas.assistant.entity.LlmProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 云端模型接入仓储。对应 。
 *
 * <p>{@code findFirstByUserIdOrderByIdAsc} 是给「兜底」用的：
 * 当会话没指定 provider、且 {@code app.llm.cloud.*} 也为空（用户把默认配置清掉了）时，
 * 取该用户第一个已接入的 provider 来应答，保证老会话仍然可用。
 * 原 {@code agent_manager.warm_up_cloud()} 与
 * {@code get_agent_for_conversation()} 里都有这段兜底逻辑。
 */
@Repository
public interface LlmProviderRepository extends JpaRepository<LlmProvider, Long> {

 /** 已接入列表：按接入顺序。 */
 List<LlmProvider> findByUserIdOrderByIdAsc(Long userId);

 Optional<LlmProvider> findByIdAndUserId(Long id, Long userId);

 /** 兜底用：取该用户第一个已接入的 provider。 */
 Optional<LlmProvider> findFirstByUserIdOrderByIdAsc(Long userId);
}
