package com.ka.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {
    // ── 基本信息 ──
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录用户名（唯一） */
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt 加密后的密码 */
    @Column(name = "password_hash", nullable = false, length = 256)
    private String passwordHash;

    /** 显示名称 */
    @Column(name = "display_name", length = 128)
    private String displayName;

    // ── 权限与状态 ──
    /** 角色：ADMIN / USER */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String role = "USER";

    /** 账号是否启用 */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    // ── 存储配额 ──
    /** 已用存储（字节） */
    @Column(name = "storage_used", nullable = false)
    @Builder.Default
    private Long storageUsed = 0L;

    /** 存储上限（字节），默认 5GB */
    @Column(name = "storage_limit", nullable = false)
    @Builder.Default
    private Long storageLimit = 5368709120L;

    // ── 时间戳 ──
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA 新建时自动填充时间 */

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
