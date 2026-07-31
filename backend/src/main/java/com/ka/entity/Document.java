package com.ka.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(nullable = false)
    @Builder.Default
    private Boolean archived = false;

    @Column(name = "file_type", length = 32)
    @Builder.Default
    private String fileType = "text";

    @Column(name = "doc_status", nullable = false, length = 32)
    @Builder.Default
    private String docStatus = "ACTIVE";

    @Column(name = "chunk_count")
    @Builder.Default
    private Integer chunkCount = 0;

    /** 原始文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 文件内容 SHA-256，用于同 KB 下去重 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** 上传者用户 ID（旧数据可空，删除时据此回收配额） */
    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
