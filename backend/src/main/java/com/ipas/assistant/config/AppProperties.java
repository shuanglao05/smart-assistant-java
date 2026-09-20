package com.ipas.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 应用业务配置。
 *
 * <p>这是早期设计 的 对应实现：把散落在各处
 * {@code os.getenv("XXX")} 的读取集中到一个不可变对象里，所有模块注入它即可。
 *
 * <p>用 record 而不是普通类的原因：配置在启动时就固定了，不该在运行中被改写。
 * record 天然不可变，且省掉了 getter/setter 样板代码。
 * Spring Boot 3 对 record 的构造器绑定是原生支持的。
 *
 * <p>命名映射规则：yml 里的 kebab-case 会自动绑到 record 的 camelCase 组件上。
 * 例如 {@code app.security.access-token-expire-minutes} → {@code accessTokenExpireMinutes}。
 *
 * <p><b>刻意没有放进来的两件事</b>：
 *
 * <ol>
 * <li>数据源（数据库地址/账号/密码）—— 它是 Spring 基础设施的一部分，
 * 由 {@code spring.datasource.*} 管理，拆开反而别扭。</li>
 * <li>{@code LLM_PROVIDER} 这类「运行时可被界面改写」的配置 —— 早期设计
 * 会把设置面板的修改写回 .env 文件。新版本改为落库（llm_providers 表），
 * 因此运行时状态不应再混进启动配置里。</li>
 * </ol>
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(

 Security security,

 /** 数据目录（对应 DATA_DIR）。SQLite 时代用来放 app.db；现在改放上传文件与缓存。 */
 String dataDir,

 Llm llm,

 Rag rag,

 History history,

 Upload upload,

 Tools tools,

 Cors cors
) {

 /**
 * 安全 / JWT 配置。对应 {@code SECRET_KEY}、{@code ALGORITHM}、
 * {@code ACCESS_TOKEN_EXPIRE_MINUTES} 三个环境变量。
 */
 public record Security(
 String secretKey,
 String algorithm,
 long accessTokenExpireMinutes
 ) {
 }

 /**
 * 大模型配置。对应 {@code LLM_PROVIDER}、{@code OLLAMA_*}、{@code CLOUD_*} 等。
 *
 * <p>{@code provider} 决定走哪条分支：{@code ollama} = 本地推理，
 * {@code cloud} = 云端 OpenAI 兼容平台（智谱 / DeepSeek / 百炼 / 硅基流动 / OpenAI）。
 */
 public record Llm(
 String provider,
 Ollama ollama,
 Cloud cloud,
 String visionModel
 ) {

 /**
 * 本地 Ollama。对应 {@code OLLAMA_BASE_URL} / {@code OLLAMA_MODEL} /
 * {@code OLLAMA_THINK} / {@code OLLAMA_NUM_CTX}。
 */
 public record Ollama(
 String baseUrl,
 String model,
 /** 是否开启思考。qwen3 开思考会明显变慢，日常问答建议关。 */
 boolean think,
 /** 上下文窗口大小（token）。 */
 int numCtx
 ) {
 }

 /**
 * 云端 OpenAI 兼容平台。对应 {@code CLOUD_API_KEY} / {@code CLOUD_BASE_URL} /
 * {@code CLOUD_MODEL} / {@code CLOUD_ENABLE_THINKING} / {@code CLOUD_THINKING_BUDGET}。
 */
 public record Cloud(
 String apiKey,
 String baseUrl,
 String model,
 /**
 * 深度思考开关。早期设计注释特别强调：{@code enable_thinking} 参数
 * 只对流式调用生效，非流式调用带上会被百炼拒绝，因此只能在流式路径透传。
 */
 boolean enableThinking,
 /** 思维链最大 token 数；0 或不传 = 用平台默认值。 */
 int thinkingBudget
 ) {
 }
 }

 /**
 * Embedding 与 RAG 检索参数。对应 {@code EMBED_*} / {@code RAG_*}。
 */
 public record Rag(
 String embedBaseUrl,
 String embedModel,
 /** 每个片段的目标字符数。 */
 int chunkSize,
 /** 相邻片段重叠字符数。 */
 int chunkOverlap,
 /** 全局默认检索片段数（Top-K）；各知识库可单独覆盖。 */
 int topK,
 /** 向量化批大小：一次发给 Ollama 的文本条数。 */
 int embedBatch,
 /** 单次检索返回片段的总上限。 */
 int maxTotalChunks
 ) {
 }

 /**
 * 对话上下文管理参数。对应 {@code HISTORY_*}，详见早期设计 。
 *
 * <p>这套参数解决的问题：对话越长，历史消息越多，token 消耗线性增长，
 * 最终撑爆模型上下文窗口。策略是「最近 N 条保留原文 + 更早的压缩成摘要」。
 *
 * <p><b>本实现实现说明（评估结论）</b>：早期设计自研的 由
 * 「滑动窗口 + 增量摘要 + 防抖」三部分组成。本实现改用 SAA 官方
 * {@code SummarizationHook} 实现（见 {@code AgentFactory} 的装配注释）——
 * 它与原文的核心语义一致（保留最近 N 条 + 更早的压缩成摘要 + 工具调用对不被切断），
 * 唯一差别是<b>触发条件按 token 数</b>而非「消息条数」。因此下面只有
 * {@code keepRecent} 与新增的 {@code summaryMaxTokens} 被真正使用；
 * 其余字段为「保留原配置项以便对照 / 未来自研版回退」而留，当前不参与压缩判定。
 */
 public record History(
 /** 保留最近多少条原始消息（其余压缩进摘要）。对应官方 hook 的 messagesToKeep。 */
 int keepRecent,
 /** 消息总数超过 keepRecent + 该值才启动摘要，避免短对话也调模型。（原自研语义，当前未用） */
 int summaryTrigger,
 /** 防抖：新滑出窗口的消息不足这么多条时复用旧摘要，避免反复调模型。（原自研语义，当前未用） */
 int summaryStep,
 /** 单次喂给摘要模型的字符上限。（原自研语义，当前未用） */
 int summaryMaxChars,
 /** 摘要提示词里约束的输出字数上限。 */
 int summaryMaxWords,
 /**
 * 官方 {@code SummarizationHook} 的触发阈值：当前上下文超过这么多 token 才压缩。
 * <p>早期设计按「消息条数 &gt; keepRecent + summaryTrigger(=20 条)」触发；
 * 官方 hook 按 token 触发更贴合真实上下文占用，也天然自带防抖
 * （压缩后 token 下降，同一轮内不会反复触发）。
 * <p>{@code <= 0} 表示<b>关闭上下文压缩</b>（不挂该 hook）。
 */
 int summaryMaxTokens
 ) {
 }

 /** 文件上传限制。对应 {@code MAX_UPLOAD_MB} / {@code MAX_CONTENT_CHARS}。 */
 public record Upload(
 int maxMb,
 int maxContentChars
 ) {
 }

 /** 外部 API Key。对应 {@code AMAP_API_KEY} / {@code OPENWEATHERMAP_API_KEY}。 */
 public record Tools(
 /** 高德地图 Key：天气查询首选（中文城市名、国内访问稳定）。 */
 String amapApiKey,
 /** OpenWeatherMap Key：备用（英文城市名检索）。 */
 String openweathermapApiKey
 ) {
 }

 /** CORS 允许的前端来源。对应 应用入口 里写死的两个 5174 端口地址（本次已把 5173 改为 5174）。 */
 public record Cors(
 List<String> allowedOrigins
 ) {
 }
}
