package com.ka.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDTO {
    private Long id;
    private String name;
    private String description;
    private Boolean enabled;
    private Boolean builtin;
    private String configJson;
    private LocalDateTime updatedAt;
}
