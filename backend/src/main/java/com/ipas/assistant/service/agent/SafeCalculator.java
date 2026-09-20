package com.ipas.assistant.service.agent;

/**
 * 安全算术求值器 —— Agent 的 {@code calculator} 工具用它。
 * 对应 里基于 {@code ast} 白名单的 {@code _safe_eval}。
 *
 * <h2>为什么必须自己写一个解析器</h2>
 *
 * <p>早期设计注释里那句话是关键：「用 AST 白名单解析，<b>绝不用 eval</b>
 * （eval 可执行任意代码）」。
 *
 * <p>Java 里对应的"图省事"做法有两种，<b>都不能用</b>：
 * <ul>
 * <li><b>JavaScript 引擎</b>（Nashorn / GraalJS）—— 等于把 eval 请回来，
 * 表达式是<b>大模型生成的</b>，而模型可能被用户输入诱导生成恶意表达式。
 * 把模型输出直接喂给一个能执行代码的引擎，是明确的安全漏洞。</li>
 * <li><b>Spring 的 SpEL</b> —— 看似"Java 官方"，但 SpEL 支持
 * {@code T(java.lang.Runtime)} 这类类型引用和方法调用，
 * 默认配置下能执行任意代码。要把它收紧到只允许算术，得做大量定制，
 * 反而比手写一个解析器更容易出错。</li>
 * </ul>
 *
 * <p>所以这里手写一个<b>只认识数字与七个运算符</b>的递归下降解析器。
 * 它无法表达任何非算术语义 —— 这是"白名单"的本质：
 * <b>不是去拦截危险输入，而是根本不给它任何能表达危险的能力</b>。
 *
 * <h2>支持的语法</h2>
 *
 * <p>与早期设计 {@code _ALLOWED_BINOPS} 白名单一致：
 * {@code + - * / % //(整除) **(幂)}、一元 {@code +/-}、括号。
 *
 * <h2>为什么要支持 {@code //} 和 {@code **}</h2>
 *
 * <p>它们是动态语言的语法。模型在中文语境下被要求算数时，可能输出
 * {@code 7 // 2}（因为它在模仿 幂运算符风格）或 {@code 2 ** 10}。
 * 若不支持，工具会直接报"不支持的表达式"，而这本是个能算出来的问题。
 * 早期设计用 动态语言的 AST 解析 天然支持，这里为保持行为一致也补上。
 */
public final class SafeCalculator {

 private SafeCalculator() {
 }

 /**
 * 求值。
 *
 * @param expression 算术表达式，如 {@code 2+3*4}、{@code (1+2)*10}、
 * {@code 2 ** 10}、{@code 7 // 2}
 * @return 计算结果
 * @throws IllegalArgumentException 表达式非法（含不支持的字符、括号不匹配、除零等）
 */
 public static double evaluate(String expression) {
 if (expression == null || expression.isBlank()) {
 throw new IllegalArgumentException("表达式为空");
 }
 Parser parser = new Parser(expression);
 double value = parser.parseExpression();
 parser.expectEnd();
 return value;
 }

 /**
 * 把结果格式化成不带多余小数位的字符串。
 *
 * <p>与早期设计一致：{@code 6.0} 要显示成 {@code 6} 而不是 {@code 6.0} ——
 * 用户问"1+2等于几"，回答"3.0"会显得很怪。
 * 这一点在原文里对应 {@code if isinstance(value, float) and value.is_integer()}。
 */
 public static String format(double value) {
 if (value == Math.rint(value) && !Double.isInfinite(value)
 && Math.abs(value) < 1e15) {
 return String.valueOf((long) value);
 }
 return String.valueOf(value);
 }

 /**
 * 递归下降解析器。
 *
 * <p>文法（低优先级在前，这正是"递归下降"能正确处理优先级的原理）：
 * <pre>
 * expression := term (('+' | '-') term)*
 * term := power (('*' | '/' | '%' | '//') power)*
 * power := unary ('**' unary)* // 右结合，见下
 * unary := ('+' | '-') unary | primary
 * primary := number | '(' expression ')'
 * </pre>
 *
 * <p>做成私有静态内部类：它只为这一次求值而存在，暴露出去没有意义。
 */
 private static final class Parser {

 private final String src;
 private int pos;

 Parser(String src) {
 this.src = src;
 }

 /**
 * 加减层。
 *
 * <p>这是优先级最低的一层：它调用更高优先级的 {@link #parsePower()} 拿到
 * 操作数，自己只负责把若干项用 {@code + / -} 串起来。
 * 递归下降之所以能正确反映优先级，靠的就是"低优先级在更外层"这个结构。
 */
 double parseExpression() {
 double left = parseTerm();
 while (true) {
 skipSpaces();
 if (eat('+')) {
 left += parseTerm();
 } else if (eat('-')) {
 left -= parseTerm();
 } else {
 return left;
 }
 }
 }

 /**
 * 乘除模层。
 *
 * <p>⚠️ 运算符的判断顺序有讲究，这里踩过一次：
 * <b>{@code //}（整除）必须在 {@code /} 之前判断</b>，
 * 否则 {@code 7 // 2} 会被读成 {@code 7 / (/ 2)} 而报错。
 * 同理在更外层要区分 {@code **} 与 {@code *}。
 */
 private double parseTerm() {
 double left = parsePower();
 while (true) {
 skipSpaces();
 if (peek("//")) {
 pos += 2;
 double right = parsePower();
 requireNonZero(right);
 left = Math.floor(left / right);
 } else if (peek("**")) {
 // 幂运算已由 parsePower 消费；能走到这里说明表达式形如
 // `2 ** ** 3`，属于非法写法，明确报错而不是静默算错
 throw new IllegalArgumentException("幂运算符 ** 使用有误");
 } else if (peekChar('*')) {
 pos++;
 left *= parsePower();
 } else if (peekChar('/')) {
 pos++;
 double right = parsePower();
 requireNonZero(right);
 left /= right;
 } else if (peekChar('%')) {
 pos++;
 double right = parsePower();
 requireNonZero(right);
 left %= right;
 } else {
 return left;
 }
 }
 }

 /**
 * 幂运算层。
 *
 * <p><b>右结合</b>：{@code 2 ** 3 ** 2} 在数学与 动态语言里都是
 * {@code 2 ** (3 ** 2)} = 512，而不是 {@code (2 ** 3) ** 2} = 64。
 * 实现方式是在拿到 {@code **} 之后<b>递归调用自己</b>（而不是调用下一层），
 * 这样运算符就从右边开始归约。
 */
 private double parsePower() {
 double base = parseUnary();
 skipSpaces();
 if (peek("**")) {
 pos += 2;
 return Math.pow(base, parsePower());
 }
 return base;
 }

 private void requireNonZero(double divisor) {
 if (divisor == 0) {
 throw new IllegalArgumentException("除数不能为 0");
 }
 }

 /** 一元正负号层。 */
 private double parseUnary() {
 skipSpaces();
 if (peekChar('-')) {
 pos++;
 return -parseUnary();
 }
 if (peekChar('+')) {
 pos++;
 return parseUnary();
 }
 return parsePrimary();
 }

 private double parsePrimary() {
 skipSpaces();
 if (eat('(')) {
 double v = parseExpression();
 skipSpaces();
 if (!eat(')')) {
 throw new IllegalArgumentException("括号不匹配");
 }
 return v;
 }
 return parseNumber();
 }

 /** 解析一个数字（支持小数与简单科学计数法）。 */
 private double parseNumber() {
 skipSpaces();
 int start = pos;
 while (pos < src.length()) {
 char c = src.charAt(pos);
 if (Character.isDigit(c) || c == '.') {
 pos++;
 } else if ((c == 'e' || c == 'E') && pos + 1 < src.length()
 && (Character.isDigit(src.charAt(pos + 1))
 || src.charAt(pos + 1) == '-'
 || src.charAt(pos + 1) == '+')) {
 // 科学计数法：e/E 后面必须紧跟数字或符号，否则它可能只是表达式里的变量名开头
 pos += 2;
 } else {
 break;
 }
 }
 if (pos == start) {
 throw new IllegalArgumentException(
 "不支持的内容：" + src.substring(Math.min(start, src.length())));
 }
 try {
 return Double.parseDouble(src.substring(start, pos));
 } catch (NumberFormatException e) {
 throw new IllegalArgumentException("非法数字：" + src.substring(start, pos));
 }
 }

 /** 是否会匹配某个两字符运算符（不消费）。 */
 private boolean peek(String op) {
 return src.startsWith(op, pos);
 }

 /** 当前位置是否是指定字符（不消费）。 */
 private boolean peekChar(char c) {
 skipSpaces();
 return pos < src.length() && src.charAt(pos) == c;
 }

 /**
 * 若当前位置是指定字符则消费并返回 true。
 *
 * <p>只用于单字符运算符与括号。多字符运算符（{@code **} / {@code //}）
 * 由调用方先用 {@link #peek} 判断再 {@code pos += 2} ——
 * 因为"消费单个字符"和"先看两个字符再决定"是两件不同的事，
 * 混在一个方法里最容易写出 {@code **} 被当成两个 {@code *} 的 bug。
 */
 private boolean eat(char c) {
 if (peekChar(c)) {
 pos++;
 return true;
 }
 return false;
 }

 private void skipSpaces() {
 while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
 pos++;
 }
 }

 /** 解析完后必须已到末尾，否则说明有无法识别的内容（如 {@code 1+2abc}）。 */
 void expectEnd() {
 skipSpaces();
 if (pos < src.length()) {
 throw new IllegalArgumentException(
 "表达式末尾有多余内容：" + src.substring(pos));
 }
 }
 }
}
