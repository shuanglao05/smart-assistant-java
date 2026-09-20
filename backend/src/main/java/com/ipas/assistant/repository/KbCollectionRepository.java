package com.ipas.assistant.repository;

import com.ipas.assistant.entity.KbCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库仓储。对应 。
 */
@Repository
public interface KbCollectionRepository extends JpaRepository<KbCollection, Long> {

 List<KbCollection> findByUserIdOrderByIdAsc(Long userId);

 Optional<KbCollection> findByIdAndUserId(Long id, Long userId);

 /** 判断是否重名（早期设计在创建知识库时做了同名检查）。 */
 boolean existsByUserIdAndName(Long userId, String name);

 /** 取该用户的第一个知识库（早期设计给"无归属文件"回填默认库时用过）。 */
 Optional<KbCollection> findFirstByUserIdOrderByIdAsc(Long userId);
}
