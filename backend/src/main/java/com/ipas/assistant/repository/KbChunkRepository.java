package com.ipas.assistant.repository;

import com.ipas.assistant.entity.KbChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

 /** 某文档的片段数（知识库文档列表里显示"已索引 N 段"）。 */
 long countByUserIdAndFileId(Long userId, Long fileId);

 /** 某知识库的片段总数（知识库列表里的统计）。 */
 long countByUserIdAndCollectionId(Long userId, Long collectionId);

 /** 删除某文档的全部片段（重新索引前先清旧数据）。 */
 void deleteByUserIdAndFileId(Long userId, Long fileId);

 /** 删除某知识库的全部片段（删除知识库时调用）。 */
 void deleteByUserIdAndCollectionId(Long userId, Long collectionId);
}
