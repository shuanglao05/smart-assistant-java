package com.ipas.assistant.repository;

import com.ipas.assistant.entity.Note;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 笔记仓储。对应 。
 */
@Repository
public interface NoteRepository extends JpaRepository<Note, Long> {

 /** 列表：先按归档日期倒序（最近的天在前），同一天内按最后修改时间倒序。 */
 List<Note> findByUserIdOrderByDayDescUpdatedAtDesc(Long userId);

 Optional<Note> findByIdAndUserId(Long id, Long userId);

 /**
 * 取某用户某天的全部笔记，按创建时间正序。
 * 供 {@code POST /api/notes/summarize}（AI 归纳某天笔记）使用 ——
 * 归纳必须按写入顺序读，否则模型总结出的要点会错乱。
 */
 List<Note> findByUserIdAndDayOrderByCreatedAtAsc(Long userId, String day);
}
