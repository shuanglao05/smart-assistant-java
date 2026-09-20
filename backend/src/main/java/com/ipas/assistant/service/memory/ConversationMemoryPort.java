package com.ipas.assistant.service.memory;

import java.util.List;

/**
 * 会话「模型侧记忆」的操作口。
 *
 * <h2>为什么需要这个接口（而不是等第三阶段再写）</h2>
 *
 * <p>早期设计在会话相关的几个操作里<b>必须同步操作模型侧的对话上下文</b>：
 * <ul>
 * <li>改会话的技能 / 知识库 → 清掉该会话缓存的 Agent，下次按新配置重建
 * （否则"勾了新技能却不生效"）；</li>
 * <li>删除会话 → 清掉缓存的 Agent + 共享记忆里该会话的历史（否则内存泄漏）；</li>
 * <li><b>删除单条消息 → 清空旧记忆线程、按剩余消息重建</b>
 * （否则模型仍"记得"用户已经删掉的内容）；</li>
 * <li>清空全部会话 → 逐个清理。</li>
 * </ul>
 *
 * <p>这些调用点分布在会话的业务流程里。<b>如果现在不做这个抽象、
 * 等第三阶段再来加，就得回头改一遍已经写好并验证过的 SessionService</b> ——
 * 那种"改已完成的代码"最容易引入回归。
 *
 * <p>所以这里先定义接口、给出一个空实现（第二阶段没有 Agent，本就无事可做），
 * 让<b>调用点现在就固定在正确的位置</b>；第三阶段提供一个真正实现替换掉它即可，
 * 业务代码一行都不用动。
 *
 * <p>这也是早期设计注释里反复强调的一件事的另一面：这些清理动作一旦漏掉，
 * 表现是「模型还记得删掉的内容」或「勾了技能不生效」这类**功能看似正常但行为诡异**
 * 的问题，事后极难定位。
 */
public interface ConversationMemoryPort {

 /**
 * 丢弃某会话缓存的 Agent（不含记忆本身）。
 *
 * <p>用于「会话的模型 / 技能 / 知识库配置变了」——下次发言要按新配置重建 Agent。
 */
 void evictAgent(Long conversationId);

 /**
 * 清掉某会话在共享记忆里的全部历史。
 *
 * <p>用于「删除会话」与「删除单条消息后重建」。
 * <b>注意：切换模型/技能/知识库时【不要】调它</b>，否则会把用户的对话历史清掉
 * （早期设计注释专门标注了这一点）。
 */
 void clearMemory(Long conversationId);

 /**
 * 按剩余消息重建该会话的记忆线程。
 *
 * <p>用于「删除单条消息」之后：先把旧记忆清空，再把剩下的消息按顺序写回去，
 * 这样模型看到的上下文与界面上显示的一致。
 *
 * @param conversationId 会话 id
 * @param userId 归属用户（记忆按用户隔离）
 * @param provider 会话当前用的 provider
 * @param model 会话当前用的模型
 * @param messages 剩余消息，按 id 升序；每项是 {@code [role, content]}
 */
 void rebuildMemory(Long conversationId,
 Long userId,
 String provider,
 String model,
 List<String[]> messages);
}
