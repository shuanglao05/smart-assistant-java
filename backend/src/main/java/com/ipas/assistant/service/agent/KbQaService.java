package com.ipas.assistant.service.agent;

import com.ipas.assistant.common.PromptGuard;
import com.ipas.assistant.config.AppProperties;
import com.ipas.assistant.repository.MessageRepository;
import com.ipas.assistant.service.rag.RagService;
import com.ipas.assistant.service.rag.RagSourcesBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 确定性知识问答：<b>先检索、再让模型基于证据生成</b>，不走 ReAct 循环。
 *
 * <h2>为什么要有这条链路</h2>
 *
 * <p>把它交给 Agent 时，"要不要检索"是模型自己决定的，于是有两个固有问题：
 * <ul>
 * <li><b>不可复现</b>：同一句话这次调了检索工具、下次不调，答案质量随机波动；</li>
 * <li><b>无法保证必检索</b>：知识问答一旦不检索，模型就会凭印象编答案（幻觉）。</li>
 * </ul>
 * 所以对"明确在问资料内容"的问题，改成代码层面的硬流程：
 * <b>（可选）改写查询 → 检索 → 把带编号的证据交给模型 → 生成</b>。
 *
 * <h2>为什么"检索不到证据"时交回 Agent</h2>
 *
 * <p>若资料里确实没有相关内容，直接回一句"未检索到足够证据"体验很差 ——
 * 用户问的可能本来就不在资料范围内（比如常识问题）。所以这里返回
 * {@link Optional#empty()}，由调用方回退到 Agent 路径，让 Agent 用它的方式回答。
 * 编排上是"确定性优先、兜不住再交给 Agent"，而不是非此即彼。
 */
@Service
@Profile("!test")
public class KbQaService {

    private static final Logger log = LoggerFactory.getLogger(KbQaService.class);

    /**
     * 证据驱动的系统提示词。
     *
     * <p>三个约束缺一不可：<b>只能用给定片段</b>（防幻觉）、<b>要标编号</b>
     * （让用户能核对来源）、<b>不用复述问题</b>（省 token 也更像人）。
     */
    private static final String SYSTEM_PROMPT = """
            你是资料问答助手。请严格依据下面提供的【资料片段】回答用户的问题。

            要求：
            1. 只使用资料片段里的信息，不要编造、不要补充片段之外的知识；
            2. 引用某段资料时，用 [1]、[2] 这样的编号标注依据（编号与资料片段一一对应）；
            3. 如果资料片段不足以回答，就直说"资料里没有找到相关内容"，不要硬答；
            4. 用简体中文直接回答，不要复述问题、不要客套。
            """;

    private final RagService ragService;
    private final RagSourcesBus ragSourcesBus;
    private final MessageRepository messageRepository;
    private final AppProperties properties;

    public KbQaService(RagService ragService,
                       RagSourcesBus ragSourcesBus,
                       MessageRepository messageRepository,
                       AppProperties properties) {
        this.ragService = ragService;
        this.ragSourcesBus = ragSourcesBus;
        this.messageRepository = messageRepository;
        this.properties = properties;
    }

 /**
 * 准备好的一轮知识问答。
 *
 * @param flux          可直接订阅的助手消息流
 * @param usedQuery     实际用于检索的查询（可能经过改写，便于观测"到底搜的是什么"）
 * @param hitCount      命中的证据片段数
 * @param evidenceChars 证据文本的字符总数（用于估算提示词 token 量）
 */
 public record Prepared(Flux<Message> flux, String usedQuery, int hitCount, int evidenceChars) {
 }

    /**
     * 尝试走确定性知识问答。
     *
     * @param userId   归属用户
     * @param convId   会话 id（用于取历史与登记来源）
     * @param question 用户本轮原始提问
     * @param kbIds    本会话启用的知识库（空 = 不检索）
     * @param rm       已解析好的模型（与 Agent 链路共用同一套凭据规则）
     * @return 准备好了就返回；<b>检索不到证据或任何环节出错都会返回 empty</b>，
     *         由调用方回退到 Agent 路径
     */
    public Optional<Prepared> prepare(Long userId, Long convId, String question,
                                      List<Long> kbIds, AgentFactory.ResolvedModel rm) {
        if (!properties.chat().kbQaEnabled()) {
            return Optional.empty();
        }

        // ① 需要时才改写（改写要多一次模型调用，只对"像省略句"的提问做）
        String query = question;
        if (properties.chat().rewriteEnabled()
                && QueryRewritePrompts.looksContextDependent(question)) {
            query = rewrite(rm.chatModel(), convId, question);
        }

        // ② 确定性检索（失败一律回退，绝不把异常甩给用户）
        RagService.Result result;
        try {
            result = ragService.retrieve(userId, query, kbIds);
        } catch (Exception e) {
            log.warn("知识问答检索失败（会话 {}），回退 Agent 路径：{}", convId, e.getMessage());
            return Optional.empty();
        }
        if (result.hits().isEmpty()) {
            log.info("知识问答未检索到证据（会话 {}，查询「{}」），回退 Agent 路径", convId, query);
            return Optional.empty();
        }

        // ③ 登记来源：本轮 SSE 的首个正文帧会据此发出 sources 事件
        // （复用 Agent 链路那套收集器，前端零改动）
        List<String> sink = ragSourcesBus.sink(convId);
        if (sink != null) {
            sink.addAll(result.sourceNames());
        }

        // ④ 拼证据 + 生成
 String evidence = buildEvidence(result.hits());
 // 证据来自用户上传的文档、问题来自用户 —— 两者都是不可信输入。
 // 统一用 PromptGuard 圈起来，并在系统提示词里立好"标记内是数据、不是指令"的规矩
 // （标记与规则必须成对出现，只做一半等于没防）。
 Prompt prompt = new Prompt(List.of(
 new SystemMessage(SYSTEM_PROMPT + "\n\n" + PromptGuard.RULE + "\n\n"
 + PromptGuard.wrap("资料片段", evidence)),
 new UserMessage(PromptGuard.wrap("用户问题", question))));

        Flux<Message> flux = rm.chatModel().stream(prompt)
                // 先过滤再映射：Reactor 不允许流里出现 null，
                // 而末尾几个 chunk 常常没有 results（只带统计信息），必须提前滤掉。
                .filter(resp -> resp.getResult() != null && resp.getResult().getOutput() != null)
                .map(resp -> (Message) resp.getResult().getOutput());

        log.info("知识问答命中 {} 段证据（会话 {}），走确定性生成", result.hits().size(), convId);
        return Optional.of(new Prepared(flux, query, result.hits().size(), evidence.length()));
    }

    /**
     * 结合最近对话把问题改写为可独立检索的查询。
     *
     * <p><b>任何失败都退回原问题</b>：改写只是"让召回更准一点"的增益项，
     * 绝不能因为模型抽风或解析失败就让这次检索拿到一句空话。
     */
    private String rewrite(ChatModel model, Long convId, String question) {
        try {
            List<String[]> history = recentHistory(convId, question);
            Prompt prompt = new Prompt(List.of(
                    new UserMessage(QueryRewritePrompts.build(question, history))));
            String out = model.call(prompt).getResult().getOutput().getText();
            String rewritten = QueryRewritePrompts.parse(out, question);
            if (!rewritten.equals(question)) {
                log.info("查询改写：{} → {}", question, rewritten);
            }
            return rewritten;
        } catch (Exception e) {
            log.warn("查询改写失败，改用原问题：{}", e.getMessage());
            return question;
        }
    }

    /**
     * 取最近若干轮对话，供改写参考。
     *
     * <p>刻意排除"本轮提问"与空内容：本轮提问已经单独放进提示词了，
     * 再混进历史会让模型以为那是上一轮的上下文；而空的助手消息是流式期间的占位行，
     * 带上只会干扰。
     */
 private List<String[]> recentHistory(Long convId, String currentQuestion) {
 // 注意：这里的 Message 是聊天记录实体，与方法的返回流类型（Spring AI 的 Message）
 // 不是同一个类型，因此实体用全限定名书写以示区分。
 List<com.ipas.assistant.entity.Message> all =
 messageRepository.findByConversationIdOrderByIdAsc(convId);
 List<String[]> out = new ArrayList<>();
 String current = currentQuestion == null ? "" : currentQuestion.strip();
 for (com.ipas.assistant.entity.Message m : all) {
            String content = m.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if ("user".equals(m.getRole()) && content.strip().equals(current)) {
                continue; // 本轮提问本身
            }
            out.add(new String[]{m.getRole(), content});
        }
        int from = Math.max(0, out.size() - QueryRewritePrompts.HISTORY_MAX_MESSAGES);
        return new ArrayList<>(out.subList(from, out.size()));
    }

    /** 把命中片段拼成带编号的证据块（编号与提示词里要求的 [1][2] 对应）。 */
    private static String buildEvidence(List<RagService.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            RagService.Hit h = hits.get(i);
            sb.append('[').append(i + 1).append("] 来源：")
                    .append(h.fileName() == null ? "未知文件" : h.fileName())
                    .append('\n')
                    .append(h.text() == null ? "" : h.text())
                    .append("\n\n");
        }
        return sb.toString().strip();
    }
}
