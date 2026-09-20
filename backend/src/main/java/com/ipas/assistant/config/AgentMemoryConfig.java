package com.ipas.assistant.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * Agent 的「模型侧对话记忆」装配。
 *
 * <h2>它对应的什么</h2>
 *
 * <p>早期设计用 图工作流框架 的 {@code 记忆存储}（落 {@code data/checkpoints.db}）保存
 * 每个会话的对话状态（HumanMessage / AIMessage / ToolMessage 的完整消息链），
 * 让"重启后端不丢上下文"、也让"切换模型时历史接得上"。
 *
 * <p>这里用 SAA 图运行时内置的 {@link MysqlSaver} 承接同一职责。
 *
 * <h2>⚠️ 这个类存在的意义：推翻了一个基于文档的判断</h2>
 *
 * <p>公开文档里 SAA 的检查点后端只列了 <b>Memory / Redis / Postgres / FileSystem</b> ——
 * <b>没有 MySQL</b>。如果据此推进，结论会是"必须自己实现一套
 * {@code BaseCheckpointSaver}"（这是个不小的工作量）。
 *
 * <p>但直接解压 jar 查证后发现 MySQL 实现是存在的：
 * <pre>
 * com/alibaba/cloud/ai/graph/checkpoint/savers/mysql/MysqlSaver
 * </pre>
 * <b>所以不需要自研。</b>这也是本项目坚持"关键依赖先查证再动手"的一个实例 ——
 * 文档缺失与"不支持"是两件事。
 *
 * <h2>顺带印证了一个更早的决策</h2>
 *
 * <p>迁移初期把数据库从 SQLite 换成 MySQL，当时的理由是
 * "Spring AI 的 {@code JdbcChatMemoryRepository} 没有 SQLite 方言"。
 * 现在看还有第二重收益：<b>如果坚持用 SQLite，这里的记忆后端就得自己写</b>。
 *
 * <h2>建表选项</h2>
 *
 * <p>{@code MysqlSaver} 自己会建两张表：{@code GRAPH_THREAD} 与 {@code GRAPH_CHECKPOINT}
 * （表名与我们的业务表<b>不冲突</b>，我们全是小写）。三个选项里：
 * <ul>
 * <li>{@code CREATE_IF_NOT_EXISTS} —— 幂等，已存在就保留。<b>默认用它。</b>
 * 与 {@code schema-mysql.sql} 里全部用 {@code IF NOT EXISTS} 是同一个理念：
 * 启动脚本可以反复执行，且永不破坏已有数据。</li>
 * <li>{@code CREATE_NONE} —— 不建表，需先由 DBA 建好。
 * <b>适用于数据库账号没有建表权限的部署环境</b>
 * （很多公司只给 CRUD 权限，否则启动会因建表失败而崩）。</li>
 * <li>{@code CREATE_OR_REPLACE} —— <b>会重建表（清空数据）</b>。
 * 记忆是"用户聊过的历史"，绝不能被重启清掉。<b>不要用。</b></li>
 * </ul>
 *
 * <h2>⚠️ 一个被实测纠正的错误认知</h2>
 *
 * <p>我最初以为 {@code CREATE_NONE} 意味着"不连库"，
 * 于是想在契约测试里用它来绕开"测试环境没有数据库"的问题。
 * <b>实测证明这是错的</b>：{@code MysqlSaver} 的构造函数会<b>无条件</b>调用
 * {@code initTables()}，也就是无论选哪个选项都要先拿到连接；
 * {@code createOption} 只决定"执行哪一套 DDL"，并不决定"是否建连"。
 * 测试里表现为 {@code Cannot invoke "java.sql.Connection.createStatement()" because "connection" is null}。
 *
 * <p>结论：<b>{@code MysqlSaver} 在构造时就要求一个可用的 DataSource</b>。
 * 而本项目的契约测试是刻意<b>不依赖 MySQL</b> 的（为了在任何机器上都能跑），
 * 两者无法共存。所以本配置类用 {@code @Profile("!test")} 在测试环境整体排除，
 * 而不是想办法"让它在无库时也能构造出来"——那只会写出更别扭的代码。
 *
 * <p>代价说明：Agent 相关的 Bean 因此不在契约测试的覆盖范围内。
 * 这是可以接受的 —— 它们本来就需要真实的模型/数据库才能验证，
 * 由端到端联调覆盖，而不是靠 Mock 出来的契约测试。
 */
@Configuration
@Profile("!test")
public class AgentMemoryConfig {

 private static final Logger log = LoggerFactory.getLogger(AgentMemoryConfig.class);

 /**
 * 共享的对话记忆存储器。
 *
 * <p><b>必须是单例且全局共享</b>，这是早期设计注释里特意强调的一点：
 * 所有会话的 Agent 共用同一份记忆，按 {@code thread_id}（= 会话 id）天然隔离。
 * 这样"切换模型 / 技能 / 知识库"导致 Agent 被重建时，对话历史仍然接得上 ——
 * 如果每个 Agent 各建一份记忆，一换模型就会"失忆"。
 *
 * <p>注入 {@link DataSource} 而不是自己建连接：直接复用 Spring 的 HikariCP
 * 连接池，与业务查询共享同一套连接管理（超时、池大小、保活都是配好的）。
 *
 * @param createOption 建表模式，见类注释。取值来自
 * {@code app.agent.memory.create-option}（默认 CREATE_IF_NOT_EXISTS）
 */
 @Bean
 public MysqlSaver conversationCheckpointSaver(
 DataSource dataSource,
 @Value("${app.agent.memory.create-option:CREATE_IF_NOT_EXISTS}") String createOption) {

 CreateOption option = parseCreateOption(createOption);
 MysqlSaver saver = MysqlSaver.builder()
 .dataSource(dataSource)
 .createOption(option)
 .build();
 log.info("对话记忆存储器已装配：MysqlSaver（表 GRAPH_THREAD / GRAPH_CHECKPOINT，建表模式={}）",
 option);
 return saver;
 }

 /**
 * 解析建表模式。
 *
 * <p>非法值<b>回落到 CREATE_IF_NOT_EXISTS 并告警</b>，而不是抛异常 ——
 * 配置写错不该让整个应用起不来（那会让人以为代码有 bug）。
 * 但也绝不能静默回落到 {@code CREATE_OR_REPLACE}：那会清掉用户的对话历史。
 */
 private static CreateOption parseCreateOption(String raw) {
 if (raw == null || raw.isBlank()) {
 return CreateOption.CREATE_IF_NOT_EXISTS;
 }
 try {
 return CreateOption.valueOf(raw.trim().toUpperCase());
 } catch (IllegalArgumentException e) {
 log.warn("app.agent.memory.create-option 取值非法（{}），已回落到 CREATE_IF_NOT_EXISTS。"
 + "合法取值：CREATE_NONE / CREATE_IF_NOT_EXISTS / CREATE_OR_REPLACE", raw);
 return CreateOption.CREATE_IF_NOT_EXISTS;
 }
 }
}
