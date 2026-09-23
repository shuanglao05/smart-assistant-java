package com.ipas.assistant.service.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 给任意 {@link ChatModel} 套上韧性保护的装饰器。
 *
 * <h2>为什么用"装饰器"而不是在调用点各写一遍</h2>
 *
 * <p>模型调用的入口不止一处（ReAct Agent、确定性知识问答、查询改写、课表导入），
 * 如果在每个入口都写一遍重试/熔断/限流，代码会重复，而且迟早有人漏写一处 ——
 * 那种漏写不会报错，只是"这个功能偶尔会莫名失败"。
 *
 * <p>装饰在 {@link ChatModelFactory} 这一层则天然覆盖所有入口：工厂是全项目
 * <b>唯一</b>构造模型的地方，从这里出去的对象都带着保护，调用方完全无感。
 *
 * <h2>同步与流式区别对待</h2>
 * <p>{@code call} 走完整的"退避重试 + 熔断 + 限流"；{@code stream} 只做熔断与限流
 * （原因见 {@link LlmResilience#wrapStream}：流已吐出部分内容时重试会拼出两段回答）。
 */
public class ResilientChatModel implements ChatModel {

    private final ChatModel delegate;
    private final LlmResilience resilience;
    /** 日志标识，用来区分是哪个入口在调用（如 chat / query-rewrite）。 */
    private final String label;

    public ResilientChatModel(ChatModel delegate, LlmResilience resilience, String label) {
        this.delegate = delegate;
        this.resilience = resilience;
        this.label = label;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return resilience.execute(label, () -> delegate.call(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return resilience.wrapStream(label, () -> delegate.stream(prompt));
    }

    /**
     * 转发"默认选项"（温度、上下文窗口等）。
     *
     * <p>必须转发：真实模型的默认值（如本地 Ollama 的 {@code num_ctx}、温度 0）挂在它自己身上，
     * 不转发的话上层读到的是一份空默认值，等于把项目的模型参数悄悄改回平台默认。
     */
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
