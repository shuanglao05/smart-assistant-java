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

 Cors cors,

 Chat chat
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
 int maxTotalChunks,
 /**
 * 最低相似度闸门：余弦相似度低于此值的片段<b>直接丢弃</b>，不再喂给模型。
 *
 * <p>为什么需要它：向量检索"召回率高、精度低"，用户问一个语料里根本没有的问题时，
 * 仍会捞出若干分数很低的片段。这些噪声会让模型顺着无关内容编答案（幻觉），
 * 也会白占上下文。加一道门槛后，这类问题会走"未检索到足够证据"的短路。
 *
 * <p><b>⚠️ 该值必须按实际使用的嵌入模型标定，不能照抄</b>：不同模型的余弦分数尺度不同。
 * 标定方法：拿一批"语料里确实有答案"和"确实没有答案"的问题各跑一遍，取前者的低分位
 * 与后者的高分位之间的分界。若发现正常提问开始频繁返回"未检索到足够证据"，
 * 说明<b>阈值偏高</b>，调低即可（改 yml 或设环境变量，无需改代码）。
 *
 * <p>{@code <= 0} 表示<b>关闭</b>闸门（不过滤）。
 */
 double minSimilarity,
 /**
 * 证据字符预算：拼进提示词的证据总字数上限。
 *
 * <p>为什么不能只用"条数上限"（{@link #maxTotalChunks}）：条数一样时，字符数可能差很远
 * （短片段 50 字、长片段 500 字）。而上限的真正意义是"别把模型的上下文窗口撑爆"，
 * 那是<b>字符/token 维度</b>的事。两者互补：条数上限控规模，字符预算控真实占用。
 *
 * <p>按相似度降序累加，超出预算就停止收录。{@code <= 0} 表示不限制。
 */
 int evidenceMaxChars,
 /**
 * 是否启用混合检索：向量通道 + 关键词通道 → RRF 融合。
 *
 * <p>关键词通道补的是"精确字面"（编号、配置项名、专有名词）这一路 ——
 * 那是纯向量检索的固有短板。融合只使用名次、不使用原始分数，所以两个通道
 * 分数量纲不同也不需要额外归一化（见 {@code Rrf}）。
 *
 * <p>关键词通道依赖数据库的全文索引；<b>索引缺失或查询失败时会自动降级为"只用向量通道"</b>，
 * 不会让检索整体报错。因此默认开启是安全的。
 */
 boolean hybridEnabled,
 /**
 * 关键词通道的相对分数门槛：命中分数低于"本批最高分 × 该比例"的会被丢弃。
 *
 * <p>为什么用<b>相对</b>值：全文检索的分值没有固定量纲（随语料规模与用词分布变化），
 * 定一个绝对阈值在不同库之间根本不可移植。用"相对本次最高分"的比例则天然自适应。
 *
 * <p>{@code <= 0} 表示不设门槛。
 */
 double keywordRelativeScoreFloor,
 /**
 * 上下文扩窗的窗口大小：命中片段时，把同一文件里序号 ±N 的相邻片段也一并作为证据。
 *
 * <p>为什么需要：命中的往往只是"一小段"（比如列表里的第 3 项），模型看不到它属于哪一节、
 * 前后还有什么，容易答偏或答不全。带上邻居能明显提升完整性，代价只是多几百字证据。
 * 证据总量仍受 {@link #evidenceMaxChars} 约束，所以扩大窗口不会无上限地撑爆上下文。
 *
 * <p>0 = 不扩窗（只用命中的片段本身）。
 */
 int neighborWindow
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

 /**
 * 对话执行链路的开关。
 *
 * <p>这两个开关控制的是"一轮提问怎么被执行"：是全交给带工具的 Agent 自由发挥，
 * 还是对知识型问题走"先检索、再生成"的确定性流程。都属于可回退的行为，
 * 出问题关掉即回到优化前的形态。
 */
 public record Chat(
 /**
 * 是否启用「确定性知识问答」链路。
 *
 * <p>打开后，明确在问资料内容的问题会先检索知识库、把带编号的证据交给模型生成，
 * 而不是依赖模型自己决定要不要调检索工具（那样"这次调、下次不调"，结果不可复现）。
 *
 * <p>打开是安全的：检索不到证据时会自动回退到 Agent 路径（见 {@code KbQaService}）。
 */
 boolean kbQaEnabled,
 /**
 * 是否启用检索查询改写。
 *
 * <p>多轮对话里的省略句（"那它怎么配置"）含大量指代词，直接检索几乎召不回东西；
 * 改写会结合最近几轮把它补成一句能独立检索的话。
 *
 * <p>只在问题"看着像省略句"时才真的调用模型（见 {@code QueryRewritePrompts}），
 * 因此对普通提问没有额外延迟。
 */
 boolean rewriteEnabled
 ) {
 }
}
