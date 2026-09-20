package com.ipas.assistant.repository;

import com.ipas.assistant.entity.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 技能仓储。对应 ，以及第三阶段构建 Agent 时的技能查询。
 */
@Repository
public interface SkillRepository extends JpaRepository<Skill, Long> {

 /** 技能列表：最近改过的排最前。 */
 List<Skill> findByUserIdOrderByUpdatedAtDesc(Long userId);

 Optional<Skill> findByIdAndUserId(Long id, Long userId);

 /**
 * 取「该用户启用的、且在给定 id 集合内」的技能。
 *
 * <p>这是第三阶段构建 Agent 时会用到的查询 —— 早期设计在
 * {@code agent_manager.get_agent_for_conversation} 里同时过滤了三个条件：
 * 属于本用户、{@code is_enabled = true}、id 在会话勾选的集合内。
 * 会话勾选了某个被关闭的技能时，它不应该生效。
 */
 List<Skill> findByIdInAndUserIdAndIsEnabledTrueOrderByIdAsc(List<Long> ids, Long userId);
}
