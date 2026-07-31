package com.ka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagQueryResponse {
    private boolean success;
    private String answer;
    private List<Map<String, Object>> sources;
    private Map<String, Object> metrics;
    private int inputTokens;
    private int outputTokens;
}
