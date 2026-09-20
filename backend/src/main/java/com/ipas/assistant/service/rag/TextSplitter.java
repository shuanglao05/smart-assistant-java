package com.ipas.assistant.service.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义感知文本切分器。对应 的 {@code split_text} 及其一众辅助函数。
 *
 * <h2>为什么要有它（而不是直接按固定长度切）</h2>
 *
 * <p>早期设计旧版是"定长 500 字符窗口 + 窗口内向后找断点"，实测 322/3100 片会以逗号或引号开头
 * —— 也就是"上一句话被腰斩"。RAG 检索的片段如果半句半句的，模型拿到后语义断裂，
 * 召回质量直接掉一截。所以这个切分器改成"先拆成语义单元（段落→行→小标题→句子），
 * 单元内部保证是完整句子，再贪心合并成片段"，结果片段收尾、开头都是完整句。</p>
 *
 * <h2>为什么单独抽成一个零依赖的纯函数类</h2>
 *
 * <p>它是"入库"侧与"检索"侧共用的基础件（两者都要把正文切成片段，且切法必须一致，
 * 否则同一段文字在入库时和检索时切成两副模样，余弦就比歪了）。把它做成不依赖
 * Spring、不依赖数据库、不依赖 Embedding 的纯静态方法，<b>任何地方都能直接调用</b>
 * （入库服务用它切，将来换 Chroma/FAISS 也只需替换 index 那一半，切分逻辑不动）。</p>
 *
 * <h2>三段式切分算法（与 文本切分器 的 split_text 逐行对齐）</h2>
 * <ol>
 * <li>{@link #splitUnits}：把正文拆成最小语义单元（段落→行→小标题→句子），
 * 单元内部保证完整句子；</li>
 * <li>贪心合并相邻单元：加下一个不超 size 就继续加，攒满一片；
 * 单个单元超长（长段无标点）才在其内部按标点二次切分（{@link #splitLongUnit}）；</li>
 * <li>重叠改为"回退若干完整单元"而非固定字符数（{@link #splitText} 的第三步），
 * 保证重叠区域也是完整句子，不会把一句话劈成两半。</li>
 * </ol>
 */
public final class TextSplitter {

 private TextSplitter() {
 }

 // ==================================================================
 // 常量（逐字沿用 文本切分器，注释解释每个符号的取舍）
 // ==================================================================

 /** 句子终止符（中文 + 英文）。句子切分与"片段是否收尾完整"的判定都用它。 */
 private static final String SENT_END = "。！？!?；;";

 /**
 * 后引号/后括号：句末常跟着 " ’ ） 」 』 】 ] 》 … 等收尾符号，需一并纳入句边界。
 * 注意：这里<b>不含</b> "…" / "——" —— 它们在中文里多表示句中停顿，
 * 当句末符会把一句话劈成两段，产生以 "…" 开头的碎片（实测小说语料 14/350 片）。
 */
 private static final String SENT_TAIL = "”’」』）]》…";

 /** 仅在"整行只剩停顿符号"时才算作收尾，用于判定片段完整性（贴回上一单元）。 */
 private static final String DANGLING = "…—～-·";

 /**
 * 小标题特征：这些行本身是"结构骨架"，应尽量作为片段起点，不要被切在中间。
 * 第X章 / 第X节 / 第X条 / 一、 / 1.1 / （一） / 附录A / 一、
 * 用正则而不是关键词，是为了同时覆盖"第一章"和"1.2 概述"这类不同写法。
 */
 private static final Pattern HEADING_RE = Pattern.compile(
 "^\\s*(?:"
 + "第[一二三四五六七八九十百千零〇\\d]+[章节条款部分篇]"
 + "|[一二三四五六七八九十]+[、．.]"
 + "|（[一二三四五六七八九十\\d]+）"
 + "|\\([一二三四五六七八九十\\d]+\\)"
 + "|\\d+(?:\\.\\d+)*[、．.]?\\s*"
 + "|附\\s*录\\s*[A-Za-z\\d一二三四五六七八九十]*"
 + "|#{1,6}\\s"
 + ")");

 /** 片段默认尺寸（文本切分器 的 config.RAG_CHUNK_SIZE / RAG_CHUNK_OVERLAP 默认值）。 */
 private static final int DEFAULT_CHUNK_SIZE = 500;
 private static final int DEFAULT_CHUNK_OVERLAP = 80;

 /** 片段最小尺寸下限：过小的 size 会让合并退化，兜底到 80。 */
 private static final int MIN_CHUNK_SIZE = 80;

 // ==================================================================
 // 最小语义单元
 // ==================================================================

 /** 一个语义单元：文本 + 是否小标题。小标题只影响"是否单独成单元"，不影响合并切分。 */
 private record Unit(String text, boolean heading) {
 }

 /** 超长单元二次切分的结果：头部文本（<=size）+ 已消费的字符数。 */
 private record SplitLongResult(String head, int used) {
 }

 // ==================================================================
 // 对外入口：splitText
 // ==================================================================

 /**
 * 把长文本切成带重叠的片段（语义感知版）。
 *
 * @param text 正文（允许为 null / 空白）
 * @param size 每个片段的目标字符数；null → {@value #DEFAULT_CHUNK_SIZE}
 * @param overlap 相邻片段重叠字符数；null → {@value #DEFAULT_CHUNK_OVERLAP}
 * @return 片段列表（每个都是完整句子，可能为空列表）
 */
 public static List<String> splitText(String text, Integer size, Integer overlap) {
 int sz = size != null ? size : DEFAULT_CHUNK_SIZE;
 int ov = overlap != null ? overlap : DEFAULT_CHUNK_OVERLAP;
 // 文本切分器：size 过小兜底到 80；overlap 不得超过半片，否则重叠会原地打转。
 sz = Math.max(MIN_CHUNK_SIZE, sz);
 ov = Math.max(0, Math.min(ov, sz / 2));

 String cleaned = (text == null ? "" : text).strip();
 if (cleaned.isEmpty()) {
 return List.of();
 }

 List<Unit> units = splitUnits(cleaned);
 int n = units.size();
 List<String> chunks = new ArrayList<>();
 int i = 0;
 while (i < n) {
 // ① 单个语义单元就超长（如整段无句号的说明文字）：先切出头部成片，
 // 剩余部分替换回单元列表继续参与后续合并，避免产生巨型片段。
 if (units.get(i).text().length() > sz) {
 SplitLongResult r = splitLongUnit(units.get(i).text(), sz);
 if (r.head() != null && !r.head().isEmpty()) {
 chunks.add(r.head());
 }
 String tail = units.get(i).text().substring(r.used()).strip();
 if (!tail.isEmpty()) {
 units.set(i, new Unit(tail, units.get(i).heading()));
 } else {
 i++;
 }
 continue;
 }

 // ② 贪心合并单元：加下一个不超 size 就继续加
 List<String> buf = new ArrayList<>();
 int length = 0;
 int j = i;
 while (j < n && length + units.get(j).text().length() <= sz) {
 buf.add(units.get(j).text());
 length += units.get(j).text().length();
 j++;
 }

 String piece = String.join("\n", buf).strip();
 if (!piece.isEmpty()) {
 chunks.add(piece);
 }
 if (j >= n) {
 break;
 }

 // ③ 回退若干完整单元作为重叠：保证重叠区域也是完整句子
 int back = j;
 int acc = 0;
 while (back > i + 1 && acc + units.get(back - 1).text().length() <= ov) {
 back--;
 acc += units.get(back).text().length();
 }
 // 至少前进一个单元，避免死循环
 i = back > i ? back : j;
 }
 return chunks;
 }

 // ==================================================================
 // 内部：拆语义单元
 // ==================================================================

 /**
 * 把正文拆成最小语义单元，返回 [(单元文本, 是否小标题)]。
 *
 * <p>顺序：先按空行分段 → 再按换行分行 → 小标题单独成单元 → 其余行按句切分。
 * 这样后续合并时，任何单元内部都是完整句子，不会出现"半句话"。
 */
 private static List<Unit> splitUnits(String text) {
 List<Unit> units = new ArrayList<>();
 // 按空行分段（\n 之间夹任意空白 = 一个或多个空行）
 for (String block : text.split("\n\\s*\\n")) {
 for (String rawLine : block.split("\n")) {
 String line = rawLine.strip();
 if (line.isEmpty()) {
 continue;
 }
 // 整行只有停顿符号（如整行只有一个 …… ）：并回上一句，避免产生以 "…" 开头的碎片
 if (stripDangling(line).isEmpty() && !units.isEmpty()) {
 Unit prev = units.get(units.size() - 1);
 units.set(units.size() - 1, new Unit(prev.text() + line, prev.heading()));
 continue;
 }
 if (isHeading(line)) {
 units.add(new Unit(line, true));
 continue;
 }
 for (String sent : splitSentences(line)) {
 if (stripDangling(sent).isEmpty() && !units.isEmpty()) {
 Unit prev = units.get(units.size() - 1);
 units.set(units.size() - 1, new Unit(prev.text() + sent, prev.heading()));
 } else {
 units.add(new Unit(sent, false));
 }
 }
 }
 }
 return units;
 }

 /**
 * 把一段文字切成句子（保留句末标点）。用于保证片段不在句中截断。
 *
 * <p>逐字符扫描而非正则切分，是为了正确处理"句号后面跟着后引号"的情况 ——
 * {@code 他说"好。"} 的句边界应在 {@code "} 之后，而不是引号之前。
 * 后引号 / 后括号属于这一句，要一并吞进当前句子。
 */
 private static List<String> splitSentences(String para) {
 List<String> out = new ArrayList<>();
 StringBuilder buf = new StringBuilder();
 int n = para.length();
 int i = 0;
 while (i < n) {
 char ch = para.charAt(i);
 buf.append(ch);
 if (SENT_END.indexOf(ch) >= 0) {
 // 吞掉紧跟的收尾符号（引号/括号），它们属于这一句
 int j = i + 1;
 while (j < n && SENT_TAIL.indexOf(para.charAt(j)) >= 0) {
 buf.append(para.charAt(j));
 j++;
 }
 String sent = buf.toString().strip();
 if (!sent.isEmpty()) {
 out.add(sent);
 }
 buf.setLength(0);
 i = j;
 continue;
 }
 i++;
 }
 String tail = buf.toString().strip();
 if (!tail.isEmpty()) {
 out.add(tail);
 }
 return out;
 }

 /** 判断一行是否像小标题（章节/条款/编号标题）。要求整行较短，避免误判正文。 */
 private static boolean isHeading(String line) {
 String s = line.strip();
 return !s.isEmpty() && s.length() <= 40 && HEADING_RE.matcher(s).matches();
 }

 /**
 * 把一个超长单元按标点切成 <= size 的头部，返回 (头部文本, 已消费的字符数)。
 *
 * <p>仅在"单个语义单元本身超过 size"时调用（典型场景：整段无句号的说明文字）。
 * 优先在逗号/顿号/分号/空格处断；都找不到就退到 size-1 处硬切（至少切出点东西）。
 */
 private static SplitLongResult splitLongUnit(String unit, int size) {
 if (unit.length() <= size) {
 return new SplitLongResult(unit, unit.length());
 }
 // rfind：在 [0, size) 范围内找最靠后的断点
 int cut = -1;
 cut = Math.max(cut, unit.lastIndexOf("，", size - 1));
 cut = Math.max(cut, unit.lastIndexOf("、", size - 1));
 cut = Math.max(cut, unit.lastIndexOf("；", size - 1));
 cut = Math.max(cut, unit.lastIndexOf(" ", size - 1));
 cut = Math.max(cut, unit.lastIndexOf(",", size - 1));
 if (cut < size * 0.5) {
 // 断点太靠前（前半段几乎没内容）→ 退到 size-1 处硬切，避免产出超短碎片
 cut = size - 1;
 }
 return new SplitLongResult(unit.substring(0, cut + 1).strip(), cut + 1);
 }

 /**
 * 去掉字符串首尾的"停顿符号"（{@link #DANGLING}）。
 *
 * <p>对应 {@code line.strip(_DANGLING)}。Java 没有"按指定字符集 strip"的现成方法，
 * 这里手写：从两端各吃掉属于 DANGLING 的字符。用来判断"整行是不是只剩停顿符号"。
 */
 private static String stripDangling(String s) {
 int start = 0;
 int end = s.length();
 while (start < end && DANGLING.indexOf(s.charAt(start)) >= 0) {
 start++;
 }
 while (end > start && DANGLING.indexOf(s.charAt(end - 1)) >= 0) {
 end--;
 }
 return s.substring(start, end);
 }
}
