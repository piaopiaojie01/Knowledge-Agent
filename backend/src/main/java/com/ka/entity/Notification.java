package com.ka.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/** 通知——管理员知道每个人干了什么 */
@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
        /** 触发通知的用户 */
    private String username;

    @Column(nullable = false, length = 32)
        /** 类型：LOGIN/UPLOAD/KB_CREATE 等 */
    private String type;       // LOGIN / UPLOAD / KB_CREATE / KB_DELETE / USER_CREATE / QUERY

    @Column(nullable = false, length = 512)
        /** 通知内容 */
    private String message;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(nullable = false)
        /** 通知时间 */
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
