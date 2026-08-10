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
    /** 提示词缓存命中/未命中 token（DeepSeek 等返回；用于计算缓存命中率与成本） */
    private int cacheHitTokens;
    private int cacheMissTokens;
    /** 本次问答估算成本（元） */
    private Double cost;
}
