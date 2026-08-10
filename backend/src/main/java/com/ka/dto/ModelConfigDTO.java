package com.ka.dto;

import lombok.*;

import java.time.LocalDateTime;

/** 大模型配置传输对象；GET 返回时 apiKey 为掩码值，留空/掩码提交表示不修改 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfigDTO {
    private String modelName;
    private String baseUrl;
    private String apiKey;
    private Double temperature;
    private Integer maxTokens;
    private Boolean enabled;
    private LocalDateTime updatedAt;
}
