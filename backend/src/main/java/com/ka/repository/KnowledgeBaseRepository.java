package com.ka.repository;

import com.ka.entity.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    List<KnowledgeBase> findByCreatedBy(Long userId);

    @Query("SELECT DISTINCT kb FROM KnowledgeBase kb WHERE kb.isPublic = true OR kb.id IN " +
           "(SELECT p.kbId FROM Permission p WHERE p.userId = :userId)")
    List<KnowledgeBase> findAccessibleByUser(@Param("userId") Long userId);
}
