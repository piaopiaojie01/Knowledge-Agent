package com.ka.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** 会话记录——刷新不丢，存 MySQL */
@Entity
@Table(name = "conversations", indexes = {
    @Index(name = "idx_conv_session", columnList = "session_id"),
    @Index(name = "idx_conv_user", columnList = "user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
        /** 自增主键 */
    private Long id;

    /** 会话 ID（同一次登录固定） */
    @Column(nullable = false, length = 64)
        /** 会话标识（同一次登录固定） */
    private String sessionId;

    /** 所属用户 */
    @Column(nullable = false)
        /** 所属用户 */
    private Long userId;

    /** 消息角色：user / assistant */
    @Column(nullable = false, length = 16)
    private String role;

    /** 会话标题（用于列表展示） */
    @Column(length = 128)
    private String title;

    /** 消息内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
        /** 消息正文 */
    private String content;

    @Column(nullable = false)
    @Builder.Default
    private Integer inputTokens = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer outputTokens = 0;

    /** 创建时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
