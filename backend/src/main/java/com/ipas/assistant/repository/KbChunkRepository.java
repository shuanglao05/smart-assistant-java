package com.ipas.assistant.repository;

import com.ipas.assistant.entity.KbChunk;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 知识片段仓储。对应 的检索与索引逻辑。
 *
 * <p><b>⚠️ 这里有意不提供"在某知识库集合内检索"的方法。</b>
 * 原因：早期设计的检索把打分（余弦相似度）放在<b>应用层</b>算，而不是在 SQL 里排 ——
 * 所以它需要先把这个用户的全部候选片段<b>整批捞出来</b>（{@code rows = query_obj.all()}），
 * 再到内存里分组、算分、取各自 Top-K。
 * 如果在这里按 {@code LIMIT} 裁剪，会破坏"每个库各取自己 Top-K"的语义
 * （因为还没算分就不知道谁更相关）。所以只提供「按范围取全部」的方法，
 * 排序逻辑留在 {@code RagService} 里。
 */
@Repository
public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {

 /** 取该用户的全部片段（不限定知识库）—— 对应「空集合 = 检索全部库」。 */
 List<KbChunk> findByUserId(Long userId);

 /** 取该用户在指定知识库集合内的片段（对应会话勾选了具体库的情况）。 */
 List<KbChunk> findByUserIdAndCollectionIdIn(Long userId, List<Long> collectionIds);

 /** 某文档的片段，按序号正序（用于图谱展示与回放）。 */
 List<KbChunk> findByUserIdAndFileIdOrderByChunkIndexAsc(Long userId, Long fileId);

 /**
 * 取这些文档在该用户下的全部片段。
 *
 * <p>用途：外部向量索引（pgvector）路径下，命中的片段是从向量库按 id 捞回来的，
 * 它们的<b>相邻片段不在内存里</b>；而上下文扩窗需要在内存里按"同文件 + 序号"找邻居，
 * 所以要把命中涉及的文件的片段补查进来。
 */
 List<KbChunk> findByUserIdAndFileIdIn(Long userId, Collection<Long> fileIds);

 /** 某文档的片段数（知识库文档列表里显示"已索引 N 段"）。 */
 long countByUserIdAndFileId(Long userId, Long fileId);

 /** 某知识库的片段总数（知识库列表里的统计）。 */
 long countByUserIdAndCollectionId(Long userId, Long collectionId);

 /** 删除某文档的全部片段（重新索引前先清旧数据）。 */
 void deleteByUserIdAndFileId(Long userId, Long fileId);

 /** 删除某知识库的全部片段（删除知识库时调用）。 */
 void deleteByUserIdAndCollectionId(Long userId, Long collectionId);

 /**
 * 回填：把一行片段的二进制向量写入（一次性迁移用）。
 *
 * <p>为什么不"查出来 set 再 save"：迁移会处理成千上万行，
 * 走实体保存会把它们全部载入持久化上下文；直接 UPDATE 内存占用恒定。
 * {@code @Modifying} 必须事务，故方法上直接标注。
 */
 @Modifying
 @Transactional
 @Query("update KbChunk c set c.embeddingBin = :bin where c.id = :id")
 int updateEmbeddingBin(@Param("id") Long id, @Param("bin") byte[] bin);

 /**
 * 回填用：找出"只有旧的 JSON 向量、还没有二进制向量"的片段，分页返回。
 *
 * <p>分页是必须的：一次性把全部历史片段读进内存，正是本次要解决问题的老毛病。
 * 每处理完一批，这批就不再满足条件（{@code embedding_bin} 已非空），
 * 因此固定取第 0 页即可稳定向后推进。
 */
 Page<KbChunk> findByEmbeddingBinIsNullAndEmbeddingIsNotNull(Pageable pageable);

 /**
 * 关键词通道检索（混合检索的第二路）。
 *
 * <h2>为什么需要它</h2>
 * <p>向量检索擅长语义，却对"精确字面"不敏感：用户问"{@code app.rag.top-k} 默认多少"、
 * 或某个编号、某个专有名词时，语义相近但字面不同的片段会挤掉真正应该命中的那一条。
 * 全文索引正好补这一路。
 *
 * <h2>为什么必须在 SQL 里算</h2>
 * <p>全文相关性打分（BM25 类）依赖<b>整个语料的统计量</b>（词频、逆文档频率），
 * 把它搬到 Java 里算既要把全部文本读出来、又算不准。交给数据库是最省事也最正确的做法。
 *
 * <h2>为什么用 ngram 解析器</h2>
 * <p>MySQL 默认的全文解析器按空格切词，对中文等于把整段当一个词，完全失效。
 * 建索引时用 {@code WITH PARSER ngram}（按 2 字滑窗切），中文才能被正常检索到。
 * 该索引由建表脚本创建，见 {@code db/schema-mysql.sql}。
 *
 * @return 每行为 {@code [chunkId, keywordScore]}，已按分数降序、最多 {@code limit} 条。
 *         注意：<b>本方法依赖全文索引</b>；索引缺失或语法不被支持时数据库会报错，
 *         调用方（检索服务）必须捕获并降级为"只用向量通道"，否则检索会整体不可用。
 */
 @Query(value = "SELECT c.id, MATCH(c.chunk_text) AGAINST (:q IN NATURAL LANGUAGE MODE) AS kw_score "
 + "FROM kb_chunks c "
 + "WHERE c.user_id = :userId "
 + "AND MATCH(c.chunk_text) AGAINST (:q IN NATURAL LANGUAGE MODE) "
 + "ORDER BY kw_score DESC "
 + "LIMIT :limit",
 nativeQuery = true)
 List<Object[]> keywordSearch(@Param("q") String q,
 @Param("userId") Long userId,
 @Param("limit") int limit);
}
