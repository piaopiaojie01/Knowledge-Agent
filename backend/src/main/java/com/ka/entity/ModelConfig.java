package com.ka.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 大模型运行配置（单行，id 固定为 1），管理后台可在线修改 */
@Entity
@Table(name = "model_configs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfig {

    @Id
    private Long id = 1L;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "api_key")
    private String apiKey;

    private Double temperature;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    private Boolean enabled = true;

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
