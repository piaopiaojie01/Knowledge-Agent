package com.ka.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** MCP（Model Context Protocol）服务器配置 */
@Entity
@Table(name = "mcp_servers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    /** Streamable HTTP / SSE 端点地址 */
    @Column(nullable = false, length = 512)
    private String url;

    private Boolean enabled = true;

    @Column(length = 512)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
