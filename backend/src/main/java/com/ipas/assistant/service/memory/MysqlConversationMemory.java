package com.ipas.assistant.service.memory;

import com.ipas.assistant.service.agent.AgentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link ConversationMemoryPort} 的<b>真实实现</b>：对话记忆落在 MySQL 的
 * SAA 图检查点表里，Agent 缓存由 {@link AgentCache} 承担。
 *
 * <h2>它替代了早期设计的什么</h2>
 *
 * <p>原 里的 {@code _CHECKPOINTER}（图工作流框架 的
 * {@code 记忆存储}）+ {@code agent_cache}：
 * <ul>
 * <li>记忆：{@code 记忆存储} → SAA 的 {@code MysqlSaver}（表
 * {@code graph_thread} / {@code graph_checkpoint}）；</li>
 * <li>缓存：模块级 dict → {@link AgentCache}。</li>
 * </ul>
 *
 * <h2>★ 清记忆用「硬删除」而不是库自带的 {@code release()}（已实测确认）</h2>
 *
 * <p>SAA 的 {@code BaseCheckpointSaver} 只暴露 {@code release(RunnableConfig)}，
 * 而它实际执行的是：
 * <pre>
 * UPDATE GRAPH_THREAD SET is_released = TRUE WHERE thread_name = ? AND is_released = FALSE
 * </pre>
 * 也就是<b>软释放</b>（打标记），行和检查点都留着。而所有读取语句都带
 * {@code WHERE t.thread_name = ? AND t.is_released != TRUE} ——
 * 所以软释放后图<b>看不到</b>这条线程，功能上是"已清空"。
 *
 * <p>但它有两个问题：
 * <ol>
 * <li><b>数据会持续累积</b>：每删一次会话就留一行 + 它的全部检查点，
 * 永不清除。个人应用跑久了会攒出大量无用数据。</li>
 * <li><b>与早期设计语义不一致</b>：原文调的是 图工作流框架 的
 * {@code delete_thread()}，是真删除。</li>
 * </ol>
 *
 * <p>所以这里选择<b>直接删 {@code graph_thread} 行</b>，
 * 靠库自带的外键 {@code GRAPH_FK_THREAD ... ON DELETE CASCADE} 连带清掉检查点。
 * 实测结果（手工造 1 个 thread + 2 个 checkpoint 后删 thread 行）：
 * <pre>
 * AFTER_INSERT threads=1 checkpoints=2
 * AFTER_DELETE threads=0 checkpoints=0 ← 级联生效
 * </pre>
 *
 * <h2>为什么在 {@code thread_name} 上删（而不是 {@code thread_id}）</h2>
 *
 * <p>因为<b>稳定身份是 {@code thread_name}</b>：库里的 {@code thread_id} 是每次
 * 插入时生成的 UUID 代理主键，而所有关联查询走的都是
 * {@code WHERE t.thread_name = ?}（会话 id 的字符串形式）。
 * 按 {@code thread_id} 删需要先查一次，多一次往返且没必要。
 *
 * <h2>为什么标注 {@code @Profile("!test")} 与 {@code @Primary}</h2>
 *
 * <ul>
 * <li>{@code @Profile("!test")}：本类依赖 {@code DataSource} 与 {@code JdbcTemplate}，
 * 而契约测试刻意<b>不依赖 MySQL</b>。测试环境因此回落到
 * {@link NoopConversationMemory}（空实现）。</li>
 * <li>{@code @Primary}：生产环境两个实现都存在，靠它指定用真的这个。
 * （不用 {@code @ConditionalOnMissingBean} —— 那个注解在组件扫描的类上
 * 会导致 Bean 完全不注册，本项目已经踩过这个坑。）</li>
 * </ul>
 */
@Component
@Primary
@Profile("!test")
public class MysqlConversationMemory implements ConversationMemoryPort {

 private static final Logger log = LoggerFactory.getLogger(MysqlConversationMemory.class);

 /**
 * 按会话清空记忆。
 *
 * <p>{@code DELETE FROM graph_thread WHERE thread_name = ?} ——
 * 不限定 {@code is_released}，把该会话名下<b>所有</b>行（含已软释放的）
 * 一并清掉，避免残留。
 */
 private static final String SQL_DELETE_THREAD =
 "DELETE FROM graph_thread WHERE thread_name = ?";

 private final JdbcTemplate jdbcTemplate;
 private final AgentCache agentCache;

 public MysqlConversationMemory(JdbcTemplate jdbcTemplate, AgentCache agentCache) {
 this.jdbcTemplate = jdbcTemplate;
 this.agentCache = agentCache;
 }

 /** 丢弃该会话缓存的全部 Agent（不含记忆本身）。 */
 @Override
 public void evictAgent(Long conversationId) {
 if (conversationId == null) {
 return;
 }
 agentCache.evictByConversation(conversationId);
 }

 /**
 * 清空该会话在共享记忆里的全部历史。
 *
 * <p>用 {@code thread_name = String.valueOf(conversationId)}：
 * 早期设计传的是 {@code thread_id=str(conv_id)}，
 * 这里保持同样的字符串形式，保证"库里的键"与"我们查的键"一致。
 *
 * <p>任何异常都只记日志不抛出（对应里被 {@code try/except} 包住的行为）：
 * 清记忆失败最多让模型多记得一点内容，绝不该让"删除会话"这个操作整体失败 ——
 * 业务数据已经删了，再报错只会让用户困惑并可能重试造成误删。
 */
 @Override
 @Transactional
 public void clearMemory(Long conversationId) {
 if (conversationId == null) {
 return;
 }
 try {
 int deleted = jdbcTemplate.update(SQL_DELETE_THREAD, String.valueOf(conversationId));
 log.debug("已清空会话 {} 的对话记忆（删除 thread 行 {} 条，检查点由外键级联清除）",
 conversationId, deleted);
 } catch (Exception e) {
 log.warn("清空会话 {} 的对话记忆失败（不影响业务数据，模型上下文将从该点重置）: {}",
 conversationId, e.getMessage());
 }
 }

 /**
 * 按剩余消息重建记忆线程。
 *
 * <p><b>⚠️ 当前只完成了「清掉旧记忆」这一半，尚未回写剩余消息。</b>
 * 这一点必须说清楚，不能让它看起来像做完了：
 *
 * <p>原文的重建流程是
 * {@code 清空 → 用剩余消息构造消息列表 → agent.update_state(...)}。
 * 最后一步需要<b>已经构建好的 Agent</b>（由 Agent 工厂按会话配置产出），
 * 而 Agent 工厂尚未实现。
 *
 * <p><b>为什么不自己拼检查点写进 {@code state_data}</b>：
 * 那需要精确对齐 SAA 图运行时的状态序列化格式（{@code OverAllState} 结构、
 * 消息子类型、序列化器版本）。靠猜格式写进去，很可能写出一个
 * <b>能存进去但读出来就崩</b>的损坏状态 —— 那比"少一半功能"严重得多。
 * 本项目今天已经因为两次"凭推断下结论"被实测纠正过，不再重复这个错误。
 *
 * <p><b>当前的降级行为是安全的</b>：清掉旧记忆后，模型不再"记得"用户已删除的
 * 内容（这正是这个功能要解决的问题），只是也丢掉了本该保留的其余上下文。
 * 这个状态<b>恰好等于早期设计里"重建失败时"的降级路径</b>
 * （原文的 {@code except} 分支就是 {@code clear_conversation_memory}）。
 *
 * <p>Agent 工厂落地后，这里补上消息回写即可，调用方（{@code SessionService}）
 * 一行都不用改。
 */
 @Override
 public void rebuildMemory(Long conversationId,
 Long userId,
 String provider,
 String model,
 List<String[]> messages) {
 // ① 清掉旧记忆（这一步是完整可用的）
 clearMemory(conversationId);

 // ② 剩下的消息回写 —— 待 Agent 工厂接入
 int count = messages == null ? 0 : messages.size();
 log.info("会话 {} 的记忆已清空（原有 {} 条消息待回写）。"
 + "消息回写依赖 Agent 工厂，将在第三阶段 Agent 落地后补齐；"
 + "当前行为等价于早期设计「重建失败」时的降级路径：模型上下文从该点重置。",
 conversationId, count);
 }
}
