package com.ipas.assistant.service.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ConversationMemoryPort} 的<b>空实现</b>（第二阶段使用）。
 *
 * <p>第二阶段还没有 Agent，也没有对话记忆 —— 所有清理动作本来就没有对象可清。
 * 所以这里的方法全是空操作，只在 DEBUG 级别留一行日志，方便排查
 * 「清理到底有没有被调用到」。
 *
 * <p><b>第三阶段怎么替换掉它</b>：新增一个实现了本接口的 Bean，
 * 并标注 {@code @Primary} 即可。Spring 会优先注入 {@code @Primary} 的那个，
 * 本类与所有业务代码都<b>一行都不用改</b>。
 *
 * <p>⚠️ <b>这里刻意不用 {@code @ConditionalOnMissingBean}</b>。
 * 直觉上那个注解更"自动"，但它<b>只对自动配置类可靠</b> ——
 * 条件是按「bean 定义注册顺序」求值的，而组件扫描阶段顺序不确定。
 * 实测在组件扫描的 {@code @Component} 上使用它，会导致本类
 * <b>一个 bean 都不注册</b>，于是 SessionService 注入失败、
 * 整个 Spring 上下文起不来（报 {@code NoSuchBeanDefinitionException}）。
 * 换成 {@code @Primary} 覆盖是确定性的行为。
 */
@Component
public class NoopConversationMemory implements ConversationMemoryPort {

 private static final Logger log = LoggerFactory.getLogger(NoopConversationMemory.class);

 @Override
 public void evictAgent(Long conversationId) {
 log.debug("【空实现】evictAgent(conversationId={}) —— 第二阶段尚无 Agent 缓存", conversationId);
 }

 @Override
 public void clearMemory(Long conversationId) {
 log.debug("【空实现】clearMemory(conversationId={}) —— 第二阶段尚无对话记忆", conversationId);
 }

 @Override
 public void rebuildMemory(Long conversationId,
 Long userId,
 String provider,
 String model,
 List<String[]> messages) {
 log.debug("【空实现】rebuildMemory(conversationId={}, {} 条消息) —— 第二阶段尚无对话记忆",
 conversationId, messages == null ? 0 : messages.size());
 }
}
