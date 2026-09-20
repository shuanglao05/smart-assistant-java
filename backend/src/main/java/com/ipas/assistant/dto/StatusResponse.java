package com.ipas.assistant.dto;

/**
 * 统一的「操作成功」响应，对应里那些返回
 * {@code {"status": "deleted"}} / {@code {"status": "ok"}} /
 * {@code {"status": "cleared"}} 的接口。
 *
 * <p>早期设计用的是裸 dict，字段名与取值都随接口而变。这里做成一个 record：
 * 既保留了 {@code status} 这个前端认的字段名，又让"哪些接口会返回什么"
 * 在代码里可被搜索、可被类型检查。用静态工厂方法把取值集中起来，
 * 避免各处手写字符串拼错（拼错了前端判断 {@code res.data.status === 'deleted'}
 * 就会静默失效，很难查）。
 */
public record StatusResponse(String status) {

 /** 删除成功。用于 todos / notes / skills / schedules / courses 的 DELETE。 */
 public static StatusResponse deleted() {
 return new StatusResponse("deleted");
 }

 /** 通用成功。用于通知的全部已读等操作。 */
 public static StatusResponse ok() {
 return new StatusResponse("ok");
 }

 /** 清空成功。 */
 public static StatusResponse cleared() {
 return new StatusResponse("cleared");
 }
}
