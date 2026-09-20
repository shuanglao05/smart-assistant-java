package com.ipas.assistant.dto;

import com.ipas.assistant.entity.TodoItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 待办模块的请求 / 响应体。对应 里的
 * {@code TodoCreate} / {@code TodoUpdate} / {@code TodoOut}。
 *
 * <p><b>【为什么把同一个模块的几个 DTO 放进一个容器类，而不是一个文件一个类】</b>
 *
 * <p>这是本项目在 DTO 层采用的有意约定。理由：
 * <ul>
 * <li>这几条记录加起来不到 30 行，拆成 4 个文件后「看一眼待办接口长什么样」
 * 要来回翻 4 次；合并后一个文件读到底，<b>契约的完整性比文件粒度更重要</b>。</li>
 * <li>它们互相强相关（改字段时必然一起改），放一起能保证同步演进。</li>
 * <li>前端调用时是一组一组用的，后端按同样的粒度组织更好对照。</li>
 * </ul>
 *
 * <p>注意：<b>实体（Entity）不适用这个约定</b> —— 实体是一个类一张表，
 * 必须独立成文件。这里只针对"体积小、强相关"的 DTO。
 *
 * <p>字段命名上跑的是全局 snake_case 策略（见 application.yml），
 * 所以 Java 的 {@code createdAt} 会输出成 {@code created_at}，与 接口框架 一致。
 */
public final class TodoDtos {

 private TodoDtos() {
 }

 /**
 * 创建待办请求。对应 {@code TodoCreate}：
 * {@code task: str = Field(min_length=1, max_length=500)}
 */
 public record Create(
 @NotBlank(message = "待办内容不能为空")
 @Size(max = 500, message = "待办内容最长 500 个字符")
 String task
 ) {
 }

 /**
 * 更新待办请求。对应 {@code TodoUpdate}：两个字段都可选，
 * null 表示"这次不改这个字段"。
 *
 * <p>注意 {@code done} 用包装类型 {@code Boolean} 而不是原始 {@code boolean}：
 * 原始类型无法表达"前端没传这个字段"，会一律被当成 false，
 * 于是「只改任务文字」的请求会把已完成状态意外重置成未完成。
 * <b>凡是"可选 + 三态语义"的字段，都必须用包装类型。</b>
 */
 public record Update(
 String task,
 Boolean done
 ) {
 }

 /** 待办响应。对应 {@code TodoOut}。 */
 public record Out(
 Long id,
 String task,
 Boolean done,
 LocalDateTime createdAt
 ) {
 /** 从实体转换。手写映射而不是用反射工具，是为了让"哪个字段暴露出去"一目了然。 */
 public static Out from(TodoItem e) {
 return new Out(e.getId(), e.getTask(), e.getDone(), e.getCreatedAt());
 }
 }

 /**
 * 待办统计响应。对应 {@code /api/todos/stats} 返回的
 * {@code {"total": n, "done": n, "updated_at": 时间}}。
 *
 * <p>{@code updated_at} 是"这次统计的时刻"，用于前端判断数据新鲜度。
 */
 public record Stats(
 long total,
 long done,
 LocalDateTime updatedAt
 ) {
 }
}
