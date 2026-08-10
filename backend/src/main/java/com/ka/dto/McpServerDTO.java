package com.ka.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerDTO {
    private Long id;
    private String name;
    private String url;
    private String description;
    private Boolean enabled;
    private LocalDateTime updatedAt;
}
