package com.ipas.assistant.repository;

import com.ipas.assistant.entity.FileItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 文件仓储。对应 / 。
 */
@Repository
public interface FileItemRepository extends JpaRepository<FileItem, Long> {

 Optional<FileItem> findByIdAndUserId(Long id, Long userId);

 /** 取一批文件（用于把消息里的 ref_file_ids 补全成文件名 + 大小）。 */
 List<FileItem> findByIdInAndUserId(List<Long> ids, Long userId);

 /** 知识库文档列表：只看某个库里的文件。 */
 List<FileItem> findByUserIdAndCollectionIdOrderByIdDesc(Long userId, Long collectionId);

 /** 全部文件（不区分知识库），用于「全部文档」视图与重索引。 */
 List<FileItem> findByUserIdOrderByIdDesc(Long userId);

 long countByUserIdAndCollectionId(Long userId, Long collectionId);

 /**
 * 更新索引状态（后台索引线程在无外层事务时调用）。
 *
 * <p><b>为什么用 {@code @Modifying} 批量更新而不是"查出来 set 再 save"</b>：
 * 本方法由异步索引线程调用，那里没有外层事务，若走实体 save 需要先保证
 * 加载与保存在同一持久化上下文里，容易写出"改了一个游离对象却不生效"的问题。
 * 一条 UPDATE 直达数据库，语义最简单、也不受上下文影响。
 *
 * <p>方法上直接标注 {@code @Transactional}：{@code @Modifying} 必须有事务才能执行，
 * 而调用方（异步线程）不是事务方法，所以事务边界加在这里，
 * 避免"忘了加导致报 No EntityManager with actual transaction available"。
 *
 * <p>{@code where ... and userId = ...} 里的 userId 是刻意的：多用户环境下，
 * 即便 fileId 被传错，也绝不会改到别人的文件状态（与全项目"每条查询都带 user_id"一致）。
 */
 @Modifying
 @Transactional
 @Query("update FileItem f set f.indexStatus = :status, f.indexError = :error, f.chunkCount = :count "
 + "where f.id = :id and f.userId = :userId")
 int updateIndexStatus(@Param("userId") Long userId,
 @Param("id") Long id,
 @Param("status") String status,
 @Param("error") String error,
 @Param("count") Integer count);
}
