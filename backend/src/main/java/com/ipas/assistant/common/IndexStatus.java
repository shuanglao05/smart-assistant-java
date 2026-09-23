package com.ipas.assistant.common;

/**
 * 文档索引状态常量。
 *
 * <h2>为什么需要"状态"这一维</h2>
 *
 * <p>索引（切分 + 向量化）是一次可能耗时几十秒到几分钟的重操作：一份 50 万字符的文档
 * 会被切成约 1000 个片段，按批大小 32 分批，要调 32 次嵌入服务。若把它放在上传请求里
 * 同步执行，用户点完上传就要盯着转圈，且浏览器 / 网关的短超时会先一步把请求掐断。
 *
 * <p>所以上传改为「先落库并立即返回，索引放到后台线程池异步跑」，而"后台跑到哪一步了"
 * 必须让用户看得见 —— 这就是本状态字段存在的意义。
 *
 * <h2>状态流转</h2>
 * <pre>
 *   可索引文件：  PENDING ──▶ INDEXING ──▶ READY
 *                                 └─────▶ FAILED（index_error 存原因，可重试）
 *   不可索引文件：SKIPPED（图片等没有正文的文件，从不上索引）
 * </pre>
 *
 * <p>用字符串常量而不是 Java 枚举：数据库列是 VARCHAR，且这些值会原样出现在接口 JSON 里
 * 供前端判断，字符串最直接、也便于将来新增状态时不需要改动已有的表结构。
 */
public final class IndexStatus {

    private IndexStatus() {
        // 常量容器，不允许实例化
    }

    /** 已入库、等待后台线程开始索引。上传成功那一刻的初始状态。 */
    public static final String PENDING = "PENDING";

    /** 正在索引（切分 / 调嵌入服务 / 写片段）。 */
    public static final String INDEXING = "INDEXING";

    /** 索引完成，片段已写入 kb_chunks，可被检索命中。 */
    public static final String READY = "READY";

    /** 索引失败，失败原因在 {@code files.index_error} 里，用户可以点"重试"。 */
    public static final String FAILED = "FAILED";

    /**
     * 无需索引。例如图片：它没有可切分的正文（交给多模态视觉模型直接看图），
     * 因此永远处于这个状态，而不是长期停在 PENDING 让用户误以为"卡住了"。
     */
    public static final String SKIPPED = "SKIPPED";
}
