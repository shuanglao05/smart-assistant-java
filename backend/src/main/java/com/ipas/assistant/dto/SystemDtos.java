package com.ipas.assistant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * 系统设置（数据存储位置）请求 / 响应体。对应 。
 *
 * <p><b>⚠️ 字段名里有 Java 关键字</b>：响应要输出 {@code "default"}，而
 * {@code default} 是 Java 关键字、不能做字段名，所以用 {@code defaultDir} +
 * {@code @JsonProperty("default")} 显式指定。
 * 少了这个注解，SNAKE_CASE 策略会把它变成 {@code default_dir}，前端拿到的就是 undefined。
 */
public final class SystemDtos {

 private SystemDtos() {
 }

 /**
 * 数据目录信息。
 *
 * @param current 当前生效的数据目录
 * @param defaultDir 配置文件里的默认位置
 * @param isDefault 当前是否就是默认位置
 * @param sizeMb 当前数据目录占用大小（MB，保留 1 位小数）
 * @param dbExists 数据库是否可用
 *
 * <p><b>db_exists 的语义已变（必须记录）</b>：
 * 早期设计是「数据目录里有没有 app.db 文件」——因为当时数据库是 SQLite、就躺在数据目录里。
 * 本实现数据库换成了 <b>MySQL（独立服务，不在数据目录）</b>，这个文件不存在了，
 * 所以这里改成「数据库是否可连接」，是同一意图（"数据能不能存取"）在新架构下的对应物。
 * 已核实：前端只在类型里声明了这个字段，<b>界面从未渲染</b>，因此改动不影响显示。
 */
 public record DataDirInfo(
 String current,
 @JsonProperty("default") String defaultDir,
 boolean isDefault,
 double sizeMb,
 boolean dbExists
 ) {
 }

 /**
 * 修改数据目录的请求。
 *
 * <p>{@code path} 挂 {@code @NotNull} 是为了对齐早期设计 数据校验框架 的
 * {@code path: str}（必填）—— 缺字段应当返回 <b>422</b>，
 * 而不是被当成空串、由业务层返回 400。
 *
 * @param path 新位置（必须是绝对路径）
 * @param migrate 是否把现有数据（uploads / cache）复制过去，默认 true
 */
 public record DataDirUpdate(
 @NotNull(message = "请填写路径")
 String path,
 Boolean migrate
 ) {
 /** 取 migrate 值；前端不传时按早期设计默认 true 处理。 */
 public boolean migrateOrDefault() {
 return migrate == null || migrate;
 }
 }

 /**
 * 修改数据目录的结果。
 *
 * @param ok 是否成功
 * @param path 新位置
 * @param migrated 是否完成了数据迁移
 * @param needRestart 是否需要重启后端
 *
 * <p><b>与早期设计的一处有意差异：need_restart 恒为 false</b>。
 * 早期设计把 DATA_DIR 写进 .env，而配置在导入时就固定了，只能重启后生效。
 * 本实现改为落库 + {@code DataDirProvider} 每次读取，
 * <b>改完立即生效、无需重启</b> —— 这是顺带的体验改进，
 * 字段仍然保留（前端会读它决定提示文案），只是不再需要提示"请重启"。
 */
 public record DataDirResult(
 boolean ok,
 String path,
 boolean migrated,
 boolean needRestart
 ) {
 }
}
