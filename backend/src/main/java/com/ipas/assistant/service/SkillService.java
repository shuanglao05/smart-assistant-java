package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.dto.SkillDtos;
import com.ipas.assistant.entity.Conversation;
import com.ipas.assistant.entity.Skill;
import com.ipas.assistant.repository.ConversationRepository;
import com.ipas.assistant.repository.SkillRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能业务逻辑。对应 。
 */
@Service
public class SkillService {

 private final SkillRepository skillRepository;
 private final ConversationRepository conversationRepository;

 public SkillService(SkillRepository skillRepository,
 ConversationRepository conversationRepository) {
 this.skillRepository = skillRepository;
 this.conversationRepository = conversationRepository;
 }

 /** 列表：最近改过的排最前。 */
 @Transactional(readOnly = true)
 public List<SkillDtos.Out> list(Long userId) {
 return skillRepository.findByUserIdOrderByUpdatedAtDesc(userId)
 .stream()
 .map(SkillDtos.Out::from)
 .toList();
 }

 /** 新建技能。 */
 @Transactional
 public SkillDtos.Out create(Long userId, SkillDtos.Create payload) {
 Skill skill = new Skill();
 skill.setUserId(userId);
 skill.setName(payload.name());
 skill.setDescription(payload.description());
 skill.setPrompt(payload.prompt());
 // is_enabled 由 @PrePersist 补 true，与早期设计默认启用一致
 return SkillDtos.Out.from(skillRepository.save(skill));
 }

 /**
 * 更新技能。四个字段都可选，null = 不改。
 *
 * <p>注意 {@code isEnabled} 用包装类型 {@code Boolean} ——
 * 前端「关闭技能」要传 {@code false}，若用原始类型，
 * "没传"和"传了 false"就无法区分，会导致部分更新意外把技能关掉。
 */
 @Transactional
 public SkillDtos.Out update(Long userId, Long skillId, SkillDtos.Update payload) {
 Skill skill = requireOwned(userId, skillId);

 if (payload.name() != null) {
 skill.setName(payload.name());
 }
 if (payload.description() != null) {
 skill.setDescription(payload.description());
 }
 if (payload.prompt() != null) {
 skill.setPrompt(payload.prompt());
 }
 if (payload.isEnabled() != null) {
 skill.setIsEnabled(payload.isEnabled());
 }

 // 先 flush 再构造响应：updatedAt 由 @PreUpdate 维护，而刷写默认发生在
 // 事务提交时。不 flush 的话返回的是旧时间戳，而技能列表正是按
 // updated_at 倒序排的 —— 用户改完技能看不到它跳到最前面，会以为没生效。
 // 早期设计靠 `db.refresh(skill)` 避开这个问题。
 skillRepository.flush();

 return SkillDtos.Out.from(skill);
 }

 /**
 * 删除技能，并把它从该用户所有会话的 {@code active_skill_ids} 里摘掉。
 *
 * <p><b>为什么必须做清理</b>：会话里存的是技能 id 列表。技能删掉后，
 * 那些 id 就成了悬空引用。第三阶段构建 Agent 时会按 id 查技能，
 * 查出空集 —— 表现为「用户明明勾着技能，但模型行为没变化」，
 * 而且界面上看不出任何异常，极难定位。
 *
 * <p><b>为什么放在同一个事务里</b>：两件事要么都成、要么都不做。
 * 若清理会话成功但删除技能失败，用户会看到技能还在但已不被任何会话引用；
 * 反之则留下悬空 id。早期设计也是在一次 commit 里完成的。
 *
 * <p>性能说明：这里遍历了该用户的所有会话。早期设计 {@code
 * _remove_skill_from_sessions} 也是全量遍历。会话数量在个人应用里是几十到几百，
 * 完全可以接受；真到上千再改成「只更新 JSON 里包含该 id 的行」的批量 UPDATE。
 */
 @Transactional
 public void delete(Long userId, Long skillId) {
 Skill skill = requireOwned(userId, skillId);
 removeSkillFromAllSessions(userId, skillId);
 skillRepository.delete(skill);
 }

 /**
 * 从该用户所有会话的 active_skill_ids 中移除指定技能 id。
 *
 * <p>注意先 {@code new ArrayList<>(...)} 再改：实体里存的是可变 List，
 * 但 JPA 判断"字段是否有变化"依赖脏检查，直接改原集合在某些实现下
 * 可能不触发更新；赋一个新列表最稳妥。
 */
 private void removeSkillFromAllSessions(Long userId, Long skillId) {
 List<Conversation> sessions = conversationRepository.findAllByUserId(userId);
 for (Conversation conv : sessions) {
 List<Long> ids = conv.getActiveSkillIds();
 if (ids != null && ids.contains(skillId)) {
 List<Long> updated = new ArrayList<>(ids);
 updated.remove(skillId);
 conv.setActiveSkillIds(updated);
 }
 }
 }

 private Skill requireOwned(Long userId, Long skillId) {
 return skillRepository.findByIdAndUserId(skillId, userId)
 .orElseThrow(() -> ApiException.notFound("技能不存在"));
 }
}
