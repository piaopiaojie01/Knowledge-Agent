package com.ka.service;

import com.ka.dto.ModelConfigDTO;
import com.ka.entity.ModelConfig;
import com.ka.repository.ModelConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/** 大模型配置：单行存储（id=1），管理后台在线修改，RAG 请求时透传给 Agent */
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private static final Long CONFIG_ID = 1L;

    private final ModelConfigRepository modelConfigRepository;

    @Value("${ka.llm.default-model:deepseek-v4-flash}")
    private String defaultModel;

    @Value("${ka.llm.default-base-url:https://api.deepseek.com}")
    private String defaultBaseUrl;

    @Transactional
    public ModelConfig getOrCreate() {
        return modelConfigRepository.findById(CONFIG_ID).orElseGet(() -> {
            ModelConfig c = ModelConfig.builder()
                    .id(CONFIG_ID)
                    .modelName(defaultModel)
                    .baseUrl(defaultBaseUrl)
                    .temperature(0.3)
                    .maxTokens(8192)
                    .enabled(true)
                    .build();
            return modelConfigRepository.save(c);
        });
    }

    /** 返回给管理后台：API Key 掩码显示 */
    public ModelConfigDTO getMasked() {
        return toDto(getOrCreate(), true);
    }

    /** 更新配置：空字符串/掩码值不覆盖已保存的 API Key，空字段保持不变 */
    @Transactional
    public ModelConfigDTO update(ModelConfigDTO req) {
        ModelConfig c = getOrCreate();
        if (req.getModelName() != null && !req.getModelName().isBlank()) {
            c.setModelName(req.getModelName().trim());
        }
        if (req.getBaseUrl() != null && !req.getBaseUrl().isBlank()) {
            c.setBaseUrl(req.getBaseUrl().trim());
        }
        if (req.getApiKey() != null && !req.getApiKey().isBlank()
                && !req.getApiKey().contains("****")) {
            c.setApiKey(req.getApiKey().trim());
        }
        if (req.getTemperature() != null) {
            c.setTemperature(req.getTemperature());
        }
        if (req.getMaxTokens() != null) {
            c.setMaxTokens(req.getMaxTokens());
        }
        if (req.getEnabled() != null) {
            c.setEnabled(req.getEnabled());
        }
        modelConfigRepository.save(c);
        return toDto(c, true);
    }

    /** 生成随 RAG 请求透传给 Agent 的配置（只包含已填写的非空项） */
    public Map<String, Object> asRequestConfig() {
        ModelConfig c = getOrCreate();
        Map<String, Object> m = new HashMap<>();
        if (c.getEnabled() == null || !c.getEnabled()) {
            return m;
        }
        if (c.getModelName() != null && !c.getModelName().isBlank()) {
            m.put("model", c.getModelName());
        }
        if (c.getBaseUrl() != null && !c.getBaseUrl().isBlank()) {
            m.put("base_url", c.getBaseUrl());
        }
        if (c.getApiKey() != null && !c.getApiKey().isBlank()) {
            m.put("api_key", c.getApiKey());
        }
        if (c.getTemperature() != null) {
            m.put("temperature", c.getTemperature());
        }
        if (c.getMaxTokens() != null) {
            m.put("max_tokens", c.getMaxTokens());
        }
        return m;
    }

    private ModelConfigDTO toDto(ModelConfig c, boolean mask) {
        String key = c.getApiKey();
        if (mask && key != null && !key.isEmpty()) {
            // 已配置时统一以 * 显示，不暴露任何前后缀
            key = "********";
        }
        return ModelConfigDTO.builder()
                .modelName(c.getModelName())
                .baseUrl(c.getBaseUrl())
                .apiKey(key)
                .temperature(c.getTemperature())
                .maxTokens(c.getMaxTokens())
                .enabled(c.getEnabled())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
