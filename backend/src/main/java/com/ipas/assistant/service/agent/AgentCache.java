package com.ipas.assistant.service.agent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 实例缓存。对应 里的
 * {@code agent_cache} 与那段「复合缓存键」逻辑。
 *
 * <h2>为什么要缓存</h2>
 *
 * <p>构建一个 Agent 要初始化模型、绑定工具、编译图 —— 开销不小。
 * 同一个会话连续提问时应当复用，而不是每次都重建。
 *
 * <h2>★ 为什么缓存键必须是"复合"的（本类最核心的设计）</h2>
 *
 * <p>早期设计注释里把这件事说得很清楚，也是它踩过坑的地方：
 * 决定"这一轮回答长什么样"的不止是会话 id，还有<b>七个维度</b>：
 *
 * <ol>
 * <li>会话 id —— 决定用哪段记忆</li>
 * <li>provider（ollama / cloud）</li>
 * <li>模型名</li>
 * <li>启用的技能集合 —— 技能 prompt 会被拼进系统提示词</li>
 * <li>启用的知识库集合 —— 决定检索范围</li>
 * <li>思考开关（enable_thinking）</li>
 * <li>思考预算（thinking_budget）</li>
 * <li>云端接入 id（provider_id）—— 多 API 时指向不同的凭据</li>
 * </ol>
 *
 * <p><b>任何一个维度变了，都必须重建 Agent。</b>少了任何一维会出什么问题：
 * <ul>
 * <li>少了模型名 → 用户切换模型后，界面显示新模型、实际还在用老的
 * （这类"显示与行为不一致"最难排查）；</li>
 * <li>少了技能集 → 勾了新技能不生效；</li>
 * <li>少了知识库集 → 检索还在翻旧的库；</li>
 * <li>少了思考开关 → 开关点了没反应。</li>
 * </ul>
 *
 * <h2>为什么不用 {@code Map<Long, Agent>}（只按会话 id 缓存）</h2>
 *
 * <p>那样"切换模型"时缓存命中，会拿到用旧模型构建的 Agent —— 正是上面第一类问题。
 *
 * <h2>★ 容量：为什么必须是"有界缓存"（一次真实的优化）</h2>
 *
 * <p>最初的实现是 {@code new ConcurrentHashMap<>()} —— 无上限、无过期，只增不减：
 * 只有"改配置 / 删会话"才会清理，否则会随「会话数 × 配置变体」一直累积，
 * 而<b>每个 Agent 都持有一个 ChatModel 与编译好的图</b>，长期运行存在内存泄漏风险。
 *
 * <p>因此改用 <b>Caffeine</b> 做有界缓存：{@code maximumSize} 限制条目数、
 * {@code expireAfterAccess} 空闲到期自动淘汰，两道闸保证内存占用有上界。
 *
 * <p><b>淘汰为什么是安全的</b>：Agent 本身<b>不持有对话历史</b>——历史在<b>共享的</b>
 * {@link com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver} 里（按
 * {@code thread_id} 隔离）。所以被淘汰最多是"下次访问重建一次"（多花点构建开销），
 * <b>不会丢任何对话数据</b>。
 *
 * <h2>并发说明</h2>
 *
 * <p>Web 请求是多线程的，同一个会话的多个请求可能并发进来。这里不做
 * "每个键只构建一次"的额外加锁 —— 极端情况下两个线程同时构建同一个键，
 * 会多构建一个 Agent 并被后者覆盖，结果依然正确（只是浪费一点开销）。
 * 相比之下，给构建过程加锁会拖慢正常路径，不划算。
 */
@Component
public class AgentCache {

 private static final Logger log = LoggerFactory.getLogger(AgentCache.class);

 /**
 * 缓存条目上限，超出后按「最近最少使用」淘汰。
 *
 * <p>条目数 ≈ 用户"同时活跃的 (会话, 模型, 技能集, 知识库集…) 组合"数。
 * 个人助理场景远达不到 64，但一旦用户开了很多会话、频繁切模型，
 * 这个上限能兜住内存。需要更大/更小可调此处（也可外提为配置项）。
 */
 private static final long MAX_ENTRIES = 64;

 /**
 * 空闲多久没被访问就淘汰。
 *
 * <p>30 分钟：远大于"一次对话中的思考间隔"（不会聊到一半把 Agent 淘汰掉），
 * 又能让偶尔用一次的会话及时释放内存。
 */
 private static final Duration EXPIRE_AFTER_ACCESS = Duration.ofMinutes(30);

 /** 缓存键 → Agent 实例。值用 Object：避免本类依赖 Agent 的具体类型，便于测试与演进。 */
 private final Cache<Key, Object> cache = Caffeine.newBuilder()
 .maximumSize(MAX_ENTRIES)
 .expireAfterAccess(EXPIRE_AFTER_ACCESS)
 .build();

 /**
 * 复合缓存键。
 *
 * <p>做成 record 而不是拼一个字符串：record 自动获得正确的
 * {@code equals/hashCode}（逐字段比较），而拼字符串极易出歧义 ——
 * 例如 {@code "1|cloud|gpt-4|23"} 无法区分技能是 {@code [2,3]} 还是 {@code [23]}。
 * 这类 bug 只在特定数据下出现，非常难查。
 *
 * <p>{@code skillIds} / {@code kbIds} 用 {@code List} 而不是 {@code Set}：
 * 调用方会先把它们<b>排序</b>再构造键（见 {@link #of}），
 * 排序后 List 的顺序即稳定，且比 Set 更省内存。
 */
 public record Key(
 Long conversationId,
 String provider,
 String model,
 List<Long> skillIds,
 List<Long> kbIds,
 boolean thinkingEnabled,
 int thinkingBudget,
 Long providerId
 ) {
 }

 /**
 * 构造缓存键，内部会<b>把技能/知识库 id 排序</b>。
 *
 * <p>排序不可省：数据库返回的顺序、前端提交的顺序都不保证稳定，
 * 若顺序不同就当成不同的键，会导致"明明配置没变却反复重建 Agent"，
 * 表现为每次都慢一截，且很难看出原因。
 *
 * @param skillIds 启用的技能 id（可为 null）
 * @param kbIds 启用的知识库 id（可为 null）
 */
 public static Key keyOf(Long conversationId,
 String provider,
 String model,
 List<Long> skillIds,
 List<Long> kbIds,
 boolean thinkingEnabled,
 int thinkingBudget,
 Long providerId) {
 return new Key(
 conversationId,
 provider == null ? "" : provider,
 model == null ? "" : model,
 sorted(skillIds),
 sorted(kbIds),
 thinkingEnabled,
 thinkingBudget,
 providerId);
 }

 private static List<Long> sorted(List<Long> ids) {
 if (ids == null || ids.isEmpty()) {
 return List.of();
 }
 List<Long> copy = new ArrayList<>(ids);
 copy.sort(Long::compareTo);
 return List.copyOf(copy);
 }

 /** 取缓存（未命中返回 null）。 */
 public Object get(Key key) {
 return cache.getIfPresent(key);
 }

 /** 放入缓存（超过上限或空闲到期时由 Caffeine 自动淘汰）。 */
 public void put(Key key, Object agent) {
 cache.put(key, agent);
 }

 /**
 * 丢弃某个会话的全部 Agent（含它的所有模型/技能变体）。
 * 对应 {@code evict_agent(conversation_id)}。
 *
 * <p>因为键的第一维就是会话 id，这里遍历所有键、凡是同一会话的都清掉。
 *
 * <p>调用场景（见 {@code SessionService}）：
 * <ul>
 * <li>会话启用的技能变了</li>
 * <li>会话启用的知识库变了</li>
 * <li>删除会话</li>
 * <li>清空全部会话</li>
 * </ul>
 *
 * <p><b>注意：切换模型 / 技能 / 知识库时【不要】连带清记忆</b> ——
 * 只清 Agent（重建时会复用同一份共享记忆，历史因此接得上）。
 * 若连记忆一起清，用户切个模型就会发现"AI 失忆了"。
 *
 * @return 实际清掉的条目数（供排查"清理有没有生效"）
 */
 public int evictByConversation(Long conversationId) {
 // 精确计数后再删：Caffeine 的 estimatedSize() 是估算值（可能滞后于实际维护），
 // 用它做前后差有可能得到 0，日志会误导排查。
 long removed = cache.asMap().keySet().stream()
 .filter(k -> conversationId.equals(k.conversationId()))
 .count();
 if (removed > 0) {
 cache.asMap().keySet().removeIf(k -> conversationId.equals(k.conversationId()));
 log.debug("已清理会话 {} 的 {} 个 Agent 缓存", conversationId, removed);
 }
 return (int) removed;
 }

 /** 清空全部缓存（配置变化后用，例如修改了本地 num_ctx）。 */
 public void clear() {
 long n = cache.estimatedSize();
 cache.invalidateAll();
 if (n > 0) {
 log.debug("已清空全部 Agent 缓存（约 {} 个）", n);
 }
 }

 /** 当前缓存条目数（估算值；供健康检查与排查使用）。 */
 public int size() {
 return (int) cache.estimatedSize();
 }
}
