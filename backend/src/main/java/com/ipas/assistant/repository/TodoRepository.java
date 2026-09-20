package com.ipas.assistant.repository;

import com.ipas.assistant.entity.TodoItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 待办仓储。对应 与 Agent 工具 {@code tools.make_todo_tools}。
 *
 * <p>排序规则 {@code done ASC, id DESC} 是有意为之：
 * 「未完成的排前面，同组内最新的排前面」。这样用户打开列表先看到待办事项，
 * 已完成的沉到底部。
 */
@Repository
public interface TodoRepository extends JpaRepository<TodoItem, Long> {

 /** 列表：未完成优先，其次按新建时间倒序。 */
 List<TodoItem> findByUserIdOrderByDoneAscIdDesc(Long userId);

 /** 按 id + userId 取，用于改/删前的归属校验（防越权）。 */
 Optional<TodoItem> findByIdAndUserId(Long id, Long userId);

 /** 总数（/api/todos/stats）。 */
 long countByUserId(Long userId);

 /** 已完成数（/api/todos/stats）。 */
 long countByUserIdAndDoneTrue(Long userId);
}
