package com.ipas.assistant.service.agent;

/**
 * 把流式正文里的 {@code thinking...<｜end▁of▁thinking｜>} 拆成「思考」与「回答」两路。
 *
 * <p>直接实现自原 的 {@code _ThinkSplitter}。
 * 部分本地推理模型（如 qwen3 开启 think）会把思考过程用这种特殊标签混在正文里；
 * 这里做流式拆分，好让前端把思考过程单独展示，不污染回答正文。
 *
 * <p>标签可能<b>跨 chunk 到达</b>（一个 chunk 只收到半个开标签），所以必须保留尾巴缓冲，
 * 下一帧再继续拼。这是原实现里最易出错的地方，实现时逐字保留逻辑。
 */
public final class ThinkSplitter {

 // 注意：这两个常量与 早期实现逐字一致，不能改（改了就匹配不到模型吐出的真实标签）
 private static final String OPEN = " thinking";
 private static final String CLOSE = "<｜end▁of▁thinking｜>";

 private String buf = "";
 private boolean inThink = false;

 /**
 * 喂入一段增量文本，返回 {@code [思考增量, 回答增量]}。
 * 两段都可能是空串（比如这帧只有回答、没有思考）。
 */
 public String[] feed(String text) {
 if (text == null || text.isEmpty()) {
 return new String[]{"", ""};
 }
 buf += text;
 StringBuilder think = new StringBuilder();
 StringBuilder answer = new StringBuilder();
 while (!buf.isEmpty()) {
 if (!inThink) {
 int i = buf.indexOf(OPEN);
 if (i >= 0) {
 answer.append(buf, 0, i);
 buf = buf.substring(i + OPEN.length());
 inThink = true;
 } else {
 // 末尾可能藏了半个开标签，先留着不输出
 int keep = OPEN.length() - 1;
 if (buf.length() > keep) {
 answer.append(buf, 0, buf.length() - keep);
 buf = buf.substring(buf.length() - keep);
 }
 break;
 }
 } else {
 int j = buf.indexOf(CLOSE);
 if (j >= 0) {
 think.append(buf, 0, j);
 buf = buf.substring(j + CLOSE.length());
 inThink = false;
 } else {
 int keep = CLOSE.length() - 1;
 if (buf.length() > keep) {
 think.append(buf, 0, buf.length() - keep);
 buf = buf.substring(buf.length() - keep);
 }
 break;
 }
 }
 }
 return new String[]{think.toString(), answer.toString()};
 }

 /** 流结束：把缓冲里剩余的内容吐出（仍处于思考态则算思考）。 */
 public String[] flush() {
 String rest = buf;
 buf = "";
 return inThink ? new String[]{rest, ""} : new String[]{"", rest};
 }
}
