package com.ipas.assistant.service.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库"来源文件名"的跨线程收集器 —— 解决 Agent 缓存复用与"每请求一个收集器"的冲突。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>Agent 是按"会话配置"缓存复用的（见 {@code AgentCache}）：同一个会话第二次请求会直接拿到
 * 第一次构建好的 {@code ReactAgent}，它的工具闭包是<b>第一次请求时烤进去的</b>。</p>
 *
 * <p>但"本次对话命中的来源文件名"是<b>每请求</b>的数据，不能烤进闭包（否则第二次请求会复用
 * 第一次的死收集器，来源串台）。所以工具不能持有固定收集器，而要在<b>运行时按会话 id 取当前请求</b>的。</p>
 *
 * <h2>为什么按会话 id 索引、用共享 Map 而不是 ThreadLocal</h2>
 *
 * <p>工具回调与 {@code ChatService.onNext} 跑在同一个 Reactor 订阅线程上，看似可用 ThreadLocal。
 * 但 {@code ChatService.stream()} 是在 HTTP 线程上先 setup 再异步订阅的，HTTP 线程上初始化的
 * ThreadLocal 在 Reactor 线程上看不到 —— 必须靠"跨线程可见"的共享结构。本 Bus 就是一个
 * {@code ConcurrentHashMap<会话id, 本次请求的收集列表>}，任何线程都能读写，天然可见。</p>
 *
 * <p><b>时序</b>：{@code ChatService.stream()} 在准备阶段 {@link #register(Long)} 建好空列表；
 * 工具执行时往 {@link #sink(Long)} 里追加来源名；{@code onNext} 发现列表非空就发 {@code sources}
 * 事件（工具一定先于"最终回答的 token"执行，所以首个 ASSISTANT token 到达时列表已就绪）；
 * 流结束/异常/断连时 {@link #unregister(Long)} 清理。</p>
 *
 * <h2>边界说明</h2>
 *
 * <p>同一会话<b>并发</b>发两条消息才会撞同一个 key（后者覆盖前者的收集器）。
 * 实际中一个用户几乎不会对同一会话同时发两条，且即便撞了也只影响"来源标注"这一展示层，
 * 不影响回答内容，故可接受。真要严格隔离可改按请求 id 索引，但工具闭包拿不到请求 id，
 * 需要额外透传，性价比低。</p>
 */
@Component
public class RagSourcesBus {

 private final ConcurrentHashMap<Long, List<String>> byConversation = new ConcurrentHashMap<>();

 /** 为一轮请求建立（或重置）该会话的来源收集列表。 */
 public void register(Long conversationId) {
 byConversation.put(conversationId, Collections.synchronizedList(new ArrayList<>()));
 }

 /** 取当前请求的来源收集列表（工具追加、ChatService 读取都走它）。 */
 public List<String> sink(Long conversationId) {
 return byConversation.get(conversationId);
 }

 /** 流结束/异常/断连时清理，避免内存泄漏与陈旧数据滞留。 */
 public void unregister(Long conversationId) {
 byConversation.remove(conversationId);
 }
}
