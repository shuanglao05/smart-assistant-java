package com.ipas.assistant.service.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 安全算术求值器的单元测试。
 *
 * <p><b>为什么这个类必须有测试</b>：递归下降解析器的运算符优先级、
 * 多字符运算符的识别顺序，都是"写的时候看着对、一跑就错"的地方 ——
 * 我在写这个类时第一次就把 {@code parseTerm} 写坏了（漏了 {@code * / % //} 的处理，
 * 还留了一个不可达分支）。这类错误编译期发现不了，只能靠断言。
 *
 * <p>它是纯逻辑类，不依赖 Spring，所以测试跑起来是毫秒级。
 */
class SafeCalculatorTest {

 @Test
 @DisplayName("四则运算与优先级")
 void basicArithmetic() {
 assertEquals(14.0, SafeCalculator.evaluate("2+3*4"), 1e-9);
 assertEquals(9.0, SafeCalculator.evaluate("(1+2)*3"), 1e-9);
 assertEquals(2.0, SafeCalculator.evaluate("10-2*4"), 1e-9);
 assertEquals(3.5, SafeCalculator.evaluate("7/2"), 1e-9);
 }

 @Test
 @DisplayName("整除 // 必须优先于除号 / 被识别（曾在这里写错）")
 void integerDivisionIsRecognisedBeforeSlash() {
 // 若先匹配 '/'，`7 // 2` 会被读成 `7 / (/ 2)` 而抛错
 assertEquals(3.0, SafeCalculator.evaluate("7 // 2"), 1e-9);
 assertEquals(-4.0, SafeCalculator.evaluate("-7 // 2"), 1e-9);
 }

 @Test
 @DisplayName("幂运算 ** 必须是右结合（2**3**2 = 512，不是 64）")
 void powerIsRightAssociative() {
 assertEquals(512.0, SafeCalculator.evaluate("2 ** 3 ** 2"), 1e-9);
 assertEquals(1024.0, SafeCalculator.evaluate("2**10"), 1e-9);
 }

 @Test
 @DisplayName("乘法与幂不会互相误判（2*3 与 2**3 都要正确）")
 void starVsDoubleStar() {
 assertEquals(6.0, SafeCalculator.evaluate("2*3"), 1e-9);
 assertEquals(8.0, SafeCalculator.evaluate("2**3"), 1e-9);
 }

 @Test
 @DisplayName("取模与一元正负号")
 void moduloAndUnary() {
 assertEquals(1.0, SafeCalculator.evaluate("10 % 3"), 1e-9);
 assertEquals(-5.0, SafeCalculator.evaluate("-5"), 1e-9);
 assertEquals(5.0, SafeCalculator.evaluate("--5"), 1e-9);
 assertEquals(-6.0, SafeCalculator.evaluate("2 * -3"), 1e-9);
 }

 @Test
 @DisplayName("非法输入必须被拒绝，且不能悄悄算成别的东西")
 void rejectsInvalidInput() {
 // 这些正是"白名单"要挡住的东西：它们不是算术
 assertThrows(IllegalArgumentException.class, () -> SafeCalculator.evaluate(""));
 assertThrows(IllegalArgumentException.class, () -> SafeCalculator.evaluate("1+2abc"));
 assertThrows(IllegalArgumentException.class, () -> SafeCalculator.evaluate("(1+2"));
 assertThrows(IllegalArgumentException.class, () -> SafeCalculator.evaluate("1/0"));
 assertThrows(IllegalArgumentException.class, () -> SafeCalculator.evaluate("7 // 0"));
 // 曾经担心的一类：动态语言里能执行代码的写法，这里必须只是"不支持的表达式"
 assertThrows(IllegalArgumentException.class,
 () -> SafeCalculator.evaluate("__import__('os').system('ls')"));
 }

 @Test
 @DisplayName("结果格式化：整数不带小数点（1+2 显示 3 而不是 3.0）")
 void formatsIntegralResultsWithoutDecimal() {
 assertEquals("3", SafeCalculator.format(SafeCalculator.evaluate("1+2")));
 assertEquals("3.5", SafeCalculator.format(SafeCalculator.evaluate("7/2")));
 assertEquals("-4", SafeCalculator.format(SafeCalculator.evaluate("-8/2")));
 }
}
