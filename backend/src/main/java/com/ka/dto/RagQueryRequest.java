package com.ka.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class RagQueryRequest {
    @NotBlank(message = "问题不能为空")
    private String question;
    private List<String> kbNames;
    private List<Map<String, String>> history;
    private String sessionId;
}
