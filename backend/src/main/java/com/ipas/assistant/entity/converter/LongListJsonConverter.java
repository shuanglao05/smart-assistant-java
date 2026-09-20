package com.ipas.assistant.entity.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@code List<Long>} 与 JSON 文本互转，供 JPA 写入 / 读取 MySQL 的 JSON 列。
 *
 * <p><b>为什么需要它</b>：早期设计的 {@code conversations.active_skill_ids} 是
 * ORM 框架 的 {@code Column(JSON, default=list)} —— 存的是 id 数组
 * （如 {@code [1, 3]}）。而 Java 的实体字段是 {@code List<Long>}，
 * 关系数据库没有「列表」这种原生类型，必须自己指定怎么存。
 *
 * <p><b>为什么用 AttributeConverter 而不是 Hibernate 自带的
 * {@code @JdbcTypeCode(SqlTypes.JSON)}</b>：
 * 后者依赖 Hibernate 的 JSON 格式映射器自动发现，行为受版本和依赖影响，
 * 出问题时报错信息很晦涩。这里显式写清楚「怎么序列化、怎么反序列化」，
 * 行为完全可控，也便于将来排查。
 *
 * <p><b>容错设计（很重要）</b>：反序列化失败时返回<b>空列表</b>而不是抛异常。
 * 理由：这两列只是「本会话启用了哪些技能/知识库」的辅助信息，
 * 就算因为历史脏数据解析不出来，也绝不该让整个会话列表接口 500 ——
 * 用户会以为"聊天记录全丢了"。宁可退化成"没启用任何技能"。
 */
@Converter
public class LongListJsonConverter implements AttributeConverter<List<Long>, String> {

 private static final Logger log = LoggerFactory.getLogger(LongListJsonConverter.class);

 /**
 * 静态的 ObjectMapper 实例。
 *
 * <p>为什么不注入 Spring 容器里的那个：AttributeConverter 由 Hibernate 实例化，
 * 不一定走 Spring 的依赖注入；用它自己的 mapper 更稳妥。
 * 这里只做「数字数组 ↔ 字符串」这种最简单的转换，不需要任何 Spring 侧的自定义配置
 * （尤其是<b>不能</b>用全局那个 —— 它开了 SNAKE_CASE 命名策略，
 * 对 List&lt;Long&gt; 没有影响，但用独立的实例可以避免将来被牵连）。
 */
 private static final ObjectMapper MAPPER = new ObjectMapper();

 private static final TypeReference<List<Long>> LIST_OF_LONG = new TypeReference<>() {
 };

 /**
 * 实体字段 → 数据库列。
 *
 * @param attribute 实体的 List（可能为 null）
 * @return JSON 文本，如 {@code [1,3]}；入参为 null 时返回 {@code []}
 * （早期设计的列默认值就是空数组，不是 null）
 */
 @Override
 public String convertToDatabaseColumn(List<Long> attribute) {
 List<Long> value = attribute == null ? List.of() : attribute;
 try {
 return MAPPER.writeValueAsString(value);
 } catch (Exception e) {
 // 理论上不会发生（序列化 Long 列表），但绝不让它把写库操作搞崩
 log.warn("active_*_ids 序列化失败，按空数组写入: {}", e.getMessage());
 return "[]";
 }
 }

 /**
 * 数据库列 → 实体字段。
 *
 * @param dbData JSON 文本（可能为 null、空串或非法 JSON —— 历史数据可能有）
 * @return 解析出的 id 列表；任何异常都返回空列表（不抛）
 */
 @Override
 public List<Long> convertToEntityAttribute(String dbData) {
 if (dbData == null || dbData.isBlank()) {
 return new ArrayList<>();
 }
 try {
 List<Long> parsed = MAPPER.readValue(dbData, LIST_OF_LONG);
 return parsed == null ? new ArrayList<>() : parsed;
 } catch (Exception e) {
 log.warn("active_*_ids 反序列化失败，按空列表处理。原始值={} 原因={}", dbData, e.getMessage());
 return new ArrayList<>();
 }
 }
}
