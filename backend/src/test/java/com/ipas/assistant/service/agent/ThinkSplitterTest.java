package com.ipas.assistant.service.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 思考/正文拆分器的单元测试。
 *
 * <h2>为什么这个类必须测</h2>
 *
 * <p>它要解决一个"流式专有"的难题：模型把思考过程用特殊标签混在正文里，
 * 而标签很可能<b>被切成两半分帧到达</b>（这一帧只收到 {@code <thi}）。
 * 于是它每帧都要"故意留一点尾巴不发"，等下一帧拼上再说。
 *
 * <p>这个"留尾巴"的策略有两个必然后果，都属于不测就发现不了的坑：
 * <ul>
 * <li>流结束时缓冲里一定还剩东西 —— <b>不调 {@link ThinkSplitter#flush()} 就会丢掉回答结尾</b>
 * （真实故障：用户看到回答停在半句）；</li>
 * <li>模型开了思考却没吐结束标签时，缓冲里的正文会被算作"思考"，正文整段消失。</li>
 * </ul>
 * 因此下面用"逐字符喂入"来覆盖最苛刻的跨帧情形，并断言
 * <b>思考 + 正文拼起来等于原文去掉标签</b> —— 这条不变式意味着"一个字都没丢"。
 */
class ThinkSplitterTest {

    /** 与实现里逐字一致的标签（改这里就等于改契约）。 */
    private static final String OPEN = " thinking";
    private static final String CLOSE = "<｜end▁of▁thinking｜>";

    /**
     * 把若干段文本依次喂入，最后 flush，返回 {@code [思考合体, 正文合体]}。
     * flush 是必须的：实现每帧都会留尾巴，不 flush 必然少几个字。
     */
    private static String[] run(String... chunks) {
        ThinkSplitter splitter = new ThinkSplitter();
        StringBuilder think = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        for (String c : chunks) {
            String[] out = splitter.feed(c);
            think.append(out[0]);
            answer.append(out[1]);
        }
        String[] tail = splitter.flush();
        think.append(tail[0]);
        answer.append(tail[1]);
        return new String[]{think.toString(), answer.toString()};
    }

    /** 逐字符喂入（最苛刻的跨帧情形），再 flush。 */
    private static String[] runCharByChar(String full) {
        String[] chunks = new String[full.length()];
        for (int i = 0; i < full.length(); i++) {
            chunks[i] = String.valueOf(full.charAt(i));
        }
        return run(chunks);
    }

    @Test
    @DisplayName("没有思考标签时：全部内容算正文（含 flush 的尾巴）")
    void plainTextGoesToAnswer() {
        String[] r = run("你好", "，世界");

        assertEquals("", r[0], "不该有思考内容");
        assertEquals("你好，世界", r[1], "正文必须完整（一个字都不能丢）");
    }

    @Test
    @DisplayName("逐字符喂入也能正确拆分（标签被切成任意多段）")
    void handlesTagSplitAcrossFrames() {
        String[] r = runCharByChar("你好" + OPEN + "让我想想" + CLOSE + "答案是 42");

        assertEquals("让我想想", r[0], "标签内的内容应归入思考");
        assertEquals("你好答案是 42", r[1], "标签外的前后正文都应保留");
    }

    @Test
    @DisplayName("★ 不 flush 就会丢尾：最后一帧的短尾巴被缓冲住，只有 flush 才吐出来")
    void withoutFlushTheTailIsLost() {
        ThinkSplitter splitter = new ThinkSplitter();

        // 只喂一次、且很短：实现会把整段留在缓冲里（怕它是半个标签）
        String[] only = splitter.feed("结尾");
        assertEquals("", only[1], "短内容会被缓冲住，这一帧什么也发不出去");

        // flush 之后才拿到 —— 这就是"流结束必须 flush"的直接证据
        String[] tail = splitter.flush();
        assertEquals("结尾", tail[1], "flush 必须把缓冲里的正文吐出来");
        assertEquals("", tail[0]);
    }

    @Test
    @DisplayName("★ 开了思考但没有结束标签：内容不能整段丢（会归入思考，且不消失）")
    void unterminatedThinkKeepsEverything() {
        String body = "只有思考没有收尾";
        String[] r = runCharByChar(OPEN + body);

        assertEquals(body, r[0], "未闭合的思考内容应完整保留在思考里");
        assertEquals("", r[1]);
    }

    @Test
    @DisplayName("思考前后都有正文：三段都各归其位")
    void bodyBeforeAndAfterThink() {
        String[] r = runCharByChar("前言" + OPEN + "内心戏" + CLOSE + "后记");

        assertEquals("内心戏", r[0]);
        assertEquals("前言后记", r[1]);
    }

    @Test
    @DisplayName("flush 是幂等的：已清空后再 flush 不会重复输出")
    void flushIsIdempotent() {
        ThinkSplitter splitter = new ThinkSplitter();
        splitter.feed("内容");
        splitter.flush();

        String[] again = splitter.flush();
        assertEquals("", again[0]);
        assertEquals("", again[1], "第二次 flush 不应再吐内容（否则会把回答复制一遍）");
    }

    @Test
    @DisplayName("空输入与 null 都不抛异常，也不产生内容")
    void emptyInputIsSafe() {
        ThinkSplitter splitter = new ThinkSplitter();

        String[] a = splitter.feed(null);
        String[] b = splitter.feed("");
        assertEquals("", a[0] + a[1]);
        assertEquals("", b[0] + b[1]);

        String[] tail = splitter.flush();
        assertEquals("", tail[0] + tail[1]);
    }
}
