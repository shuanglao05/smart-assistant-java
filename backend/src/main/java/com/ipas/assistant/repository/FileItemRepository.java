package com.ipas.assistant.repository;

import com.ipas.assistant.entity.FileItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
