package com.ka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class KnowledgeBaseDTO {
    private Long id;
    private String name;
    private String description;
    private Long createdBy;
    private Boolean isPublic;
    private int docCount;
    private LocalDateTime createdAt;
}
