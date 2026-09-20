package com.ipas.assistant.service;

import com.ipas.assistant.common.ApiException;
import com.ipas.assistant.common.Times;
import com.ipas.assistant.dto.TodoDtos;
import com.ipas.assistant.entity.TodoItem;
import com.ipas.assistant.repository.TodoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 待办业务逻辑。对应 。
 *
 * <p><b>与 Agent 工具共用本类</b>：早期设计里待办有两条入口 ——
 * REST 接口（）和 Agent 工具（{@code tools.make_todo_tools}），
 * 两者写同一张表。第三阶段接入 Agent 工具时，工具的 {@code add_todo} / {@code list_todos}
 * <b>应当直接调用本类方法</b>，而不是另写一套 SQL。
 * 否则两条路径的排序规则、默认值、事务边界很容易走岔，
 * 表现为"界面上看到的待办顺序和 AI 列出来的不一样"。
 */
@Service
public class TodoService {

 private final TodoRepository todoRepository;

 public TodoService(TodoRepository todoRepository) {
 this.todoRepository = todoRepository;
 }

 /** 列表：未完成优先，同组内新建的在前（排序原因见 Repository 注释）。 */
 @Transactional(readOnly = true)
 public List<TodoDtos.Out> list(Long userId) {
 return todoRepository.findByUserIdOrderByDoneAscIdDesc(userId)
 .stream()
 .map(TodoDtos.Out::from)
 .toList();
 }

 /**
 * 新建待办。
 *
 * <p>{@code task.strip()} 是必须的：早期设计在这里做了 trim，
 * 用户很容易在输入框里多敲空格，不 trim 会让 " 写报告" 和 "写报告"
 * 在界面和排序里被当成两条不同的内容。
 */
 @Transactional
 public TodoDtos.Out create(Long userId, TodoDtos.Create payload) {
 TodoItem todo = new TodoItem();
 todo.setUserId(userId);
 todo.setTask(payload.task().strip());
 return TodoDtos.Out.from(todoRepository.save(todo));
 }

 /**
 * 更新待办。
 *
 * <p>两个细节都与原文保持一致：
 * <ol>
 * <li>{@code task} 的赋值是 {@code payload.task.strip() or todo.task} ——
 * 也就是<b>若 trim 后变成空串，就保持原值不动</b>。
 * 用户全选删除再误保存时，不会把内容清空。</li>
 * <li>{@code done} 只在非 null 时更新（三态语义，见 {@code TodoDtos.Update} 注释）。</li>
 * </ol>
 */
 @Transactional
 public TodoDtos.Out update(Long userId, Long todoId, TodoDtos.Update payload) {
 TodoItem todo = requireOwned(userId, todoId);

 if (payload.task() != null) {
 String stripped = payload.task().strip();
 if (!stripped.isEmpty()) {
 todo.setTask(stripped);
 }
 }
 if (payload.done() != null) {
 todo.setDone(payload.done());
 }
 return TodoDtos.Out.from(todo);
 }

 /** 删除待办。 */
 @Transactional
 public void delete(Long userId, Long todoId) {
 todoRepository.delete(requireOwned(userId, todoId));
 }

 /** 统计（总数 / 已完成数 / 统计时刻）。 */
 @Transactional(readOnly = true)
 public TodoDtos.Stats stats(Long userId) {
 return new TodoDtos.Stats(
 todoRepository.countByUserId(userId),
 todoRepository.countByUserIdAndDoneTrue(userId),
 Times.nowUtc());
 }

 /**
 * 取「属于该用户」的待办，取不到就 404。
 *
 * <p>这是多用户隔离的关键：不能直接用 {@code findById}。
 * 若用 findById，别人只要猜到 id 就能改你的待办 ——
 * 而 id 是自增的，猜起来毫无难度。
 */
 private TodoItem requireOwned(Long userId, Long todoId) {
 return todoRepository.findByIdAndUserId(todoId, userId)
 .orElseThrow(() -> ApiException.notFound("待办不存在"));
 }
}
