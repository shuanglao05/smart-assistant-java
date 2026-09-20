package com.ipas.assistant.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Jackson 序列化的补充配置。
 *
 * <p>全局的 snake_case 命名策略、时间格式等已经写在 {@code application.yml} 里
 * （那几项是纯配置，不需要代码）。这里只处理一个纯配置解决不了的差异。
 *
 * <h2>要解决的问题：Double 被写成科学计数法</h2>
 *
 * <p><b>现象</b>：日程的 {@code start_at}（epoch 秒）在接口里返回
 * {@code 1.7896E9}，而原 早期实现返回的是 {@code 1789600000.0}。
 *
 * <p><b>原因</b>：Jackson 序列化 {@code double} 时直接走
 * {@code Double.toString()}，它对较大/较小的数值会输出科学计数法。
 * 而标准 JSON 序列化的 {@code json.dumps()} 输出的是常规十进制形式。
 *
 * <p><b>为什么必须对齐</b>：{@code 1.7896E9} 本身是<b>合法 JSON 数字</b>，
 * 前端 {@code JSON.parse} 后拿到的也是正确的 1789600000 ——
 * 也就是说<b>功能上不会坏</b>。但它是一处契约上的可见差异：
 * 任何对比过两端响应的人都会疑惑"这里为什么变了"，
 * 而排查这类"看起来没问题又不太一样"的差异最浪费时间。
 * 既然一行配置能消掉，就不该留着。
 *
 * <p><b>做法</b>：注册一个 Double 的序列化器，用
 * {@code BigDecimal.toPlainString()} 输出常规十进制形式。
 *
 * <p><b>影响范围</b>：只影响 {@code Double} / {@code double} 类型字段。
 * 本项目目前只有 {@code schedules.start_at} 是 Double，
 * 后续若有新的浮点字段（如坐标、评分）也会一并受益 ——
 * 前端拿到的永远是 {@code 1.5} 而不是 {@code 1.5E0}。
 *
 * <p><b>一处已知的微小差异</b>：整数值的 Double 会输出成
 * {@code 1789600000}（无小数位），而 标准 JSON 序列化输出 {@code 1789600000.0}。
 * 两者在 JSON 里是同一个数值、JS 解析结果完全相同，故不再额外补 {@code .0}。
 */
@Configuration
public class JacksonConfig {

 /**
 * 让所有 Double 以常规十进制形式序列化，不用科学计数法。
 *
 * <p>用 {@code Jackson2ObjectMapperBuilderCustomizer} 而不是自己 new 一个
 * {@code ObjectMapper}：前者是「在 Spring Boot 已经配好的 ObjectMapper 上
 * 追加这一项定制」，命名策略、时间格式等既有配置全部保留；
 * 后者会把它们全部覆盖掉，导致 snake_case 契约失效。
 */
 @Bean
 public Jackson2ObjectMapperBuilderCustomizer plainDoubleCustomizer() {
 // ⚠️ 这里必须写 new JsonSerializer<Double>() { ... }，
 // 不能图省事写 new JsonSerializer<>() { ... }（菱形推断）。
 // 原因：serializerByType 的形参是 JsonSerializer<?>，菱形推断会得到
 // JsonSerializer<Object>，此时要覆写的方法签名变成 serialize(Object, ...)，
 // 与我们写的 serialize(Double, ...) 不匹配 —— 编译期报
 // 「不是抽象的, 并且未覆盖 ... 中的抽象方法 serialize(java.lang.Object, ...)」。
 return builder -> builder.serializerByType(Double.class, new JsonSerializer<Double>() {
 @Override
 public void serialize(Double value,
 JsonGenerator gen,
 SerializerProvider serializers) throws IOException {
 if (value == null) {
 gen.writeNull();
 return;
 }
 // writeNumber(String) 会把这个字符串当作【数字字面量】写出（不加引号），
 // 所以不能写成 gen.writeString(...)，那样会变成 "1789600000"（带引号，
 // 前端拿到的是字符串而不是 number，类型就对不上了）。
 gen.writeNumber(BigDecimal.valueOf(value).toPlainString());
 }
 });
 }
}
