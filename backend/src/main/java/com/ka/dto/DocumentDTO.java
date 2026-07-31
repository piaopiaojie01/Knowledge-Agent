package com.ka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDTO {
    private Long id;
    private Long kbId;
    private String title;
    private String content;
    private String fileType;
    private String docStatus;
    private Integer chunkCount;
    private LocalDateTime createdAt;
}
