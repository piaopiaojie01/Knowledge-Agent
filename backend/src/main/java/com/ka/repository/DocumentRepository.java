package com.ka.repository;

import com.ka.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByTitle(String title);

    List<Document> findByKbIdAndDocStatus(Long kbId, String docStatus);

    List<Document> findByKbId(Long kbId);

    /** 同 KB 下按内容哈希查找 ACTIVE 文档（上传去重） */
    Optional<Document> findFirstByKbIdAndContentHashAndDocStatus(Long kbId, String contentHash, String docStatus);

    List<Document> findByTitleContainingOrContentContaining(String title, String content);

    @Query(value = "SELECT * FROM documents WHERE kb_id = :kbId AND doc_status = 'ACTIVE' " +
           "AND MATCH(title, content) AGAINST(:keyword IN BOOLEAN MODE)",
           nativeQuery = true)
    List<Document> searchByKeyword(@Param("kbId") Long kbId, @Param("keyword") String keyword);

    @Query(value = "SELECT * FROM documents WHERE doc_status = 'ACTIVE' " +
           "AND MATCH(title, content) AGAINST(:keyword IN BOOLEAN MODE)",
           nativeQuery = true)
    List<Document> searchAllByKeyword(@Param("keyword") String keyword);
}
