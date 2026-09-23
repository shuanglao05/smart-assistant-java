package com.ipas.assistant.common;

/**
 * 「请为这份文档建立索引」的领域事件。
 *
 * <h2>为什么用事件而不是直接调用</h2>
 *
 * <p>索引必须在「上传事务真正提交之后」才开始 —— 否则会出现两种事故：
 * <ol>
 * <li><b>读到未提交的数据</b>：后台线程按 fileId 回查文件时，事务还没提交，
 * 查不到这条记录（或读到旧版本）；</li>
 * <li><b>索引成功但记录回滚</b>：索引写好了片段，但上传事务因其它原因回滚，
 * 于是留下了一批"指向不存在文件"的孤儿片段。</li>
 * </ol>
 *
 * <p>所以上传侧只负责<b>发布事件</b>，由监听器用
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 接住 ——
 * 这样"事务已提交"由框架保证，业务代码不用自己去猜。详见
 * {@code KnowledgeIndexWorker}。
 *
 * <p>只带三个 id（而不是把文件内容也塞进来）：事件是轻量的通知，真正要用的数据
 * 由后台线程在事务提交后自己回查，避免事件对象里携带可能已经过期的快照。
 *
 * @param userId       归属用户（多用户隔离；后台线程拿不到请求上下文，必须显式携带）
 * @param fileId       要索引的文件 id
 * @param collectionId 归属知识库（可为 null，表示仅聊天附件）
 */
public record FileIndexRequested(Long userId, Long fileId, Long collectionId) {
}
