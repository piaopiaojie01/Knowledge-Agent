package com.ka.service;

import com.ka.dto.ModelConfigDTO;
import com.ka.entity.ModelConfig;
import com.ka.repository.ModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 大模型配置服务：默认值种子、掩码、空白不覆盖、关闭时不下发 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelConfigServiceTest {

    @Mock
    private ModelConfigRepository repo;

    private ModelConfigService service;

    @BeforeEach
    void setUp() {
        service = new ModelConfigService(repo);
        ReflectionTestUtils.setField(service, "defaultModel", "deepseek-v4-flash");
        ReflectionTestUtils.setField(service, "defaultBaseUrl", "https://api.deepseek.com");
    }

    @Test
    void getOrCreate_缺省行不存在时写入默认配置() {
        when(repo.findById(1L)).thenReturn(Optional.empty());
        when(repo.save(any(ModelConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ModelConfig c = service.getOrCreate();

        assertNotNull(c);
        assertEquals("deepseek-v4-flash", c.getModelName());
        assertEquals("https://api.deepseek.com", c.getBaseUrl());
        verify(repo).save(any(ModelConfig.class));
    }

    @Test
    void update_空白ApiKey与BaseUrl不覆盖已有值() {
        ModelConfig existing = ModelConfig.builder().id(1L)
                .modelName("old").baseUrl("http://old").apiKey("sk-secret-1234567890")
                .temperature(0.3).maxTokens(8192).enabled(true).build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));

        ModelConfigDTO req = ModelConfigDTO.builder()
                .modelName("new-model").baseUrl("").apiKey("  ")
                .temperature(0.7).maxTokens(2048).enabled(true).build();
        service.update(req);

        assertEquals("new-model", existing.getModelName());
        assertEquals("http://old", existing.getBaseUrl());
        assertEquals("sk-secret-1234567890", existing.getApiKey());
        assertEquals(0.7, existing.getTemperature());
        assertEquals(2048, existing.getMaxTokens());
    }

    @Test
    void asRequestConfig_关闭或空字段时不下发() {
        ModelConfig disabled = ModelConfig.builder().id(1L)
                .modelName("m").baseUrl("").apiKey("").temperature(0.3)
                .maxTokens(100).enabled(false).build();
        when(repo.findById(1L)).thenReturn(Optional.of(disabled));
        assertTrue(service.asRequestConfig().isEmpty());

        ModelConfig enabled = ModelConfig.builder().id(1L)
                .modelName("m").baseUrl("b").apiKey("k").temperature(0.5)
                .maxTokens(200).enabled(true).build();
        when(repo.findById(1L)).thenReturn(Optional.of(enabled));
        assertEquals("m", service.asRequestConfig().get("model"));
        assertEquals("b", service.asRequestConfig().get("base_url"));
        assertEquals("k", service.asRequestConfig().get("api_key"));
        assertEquals(0.5, service.asRequestConfig().get("temperature"));
        assertEquals(200, service.asRequestConfig().get("max_tokens"));
    }

    @Test
    void getMasked_已配置ApiKey统一星号显示() {
        ModelConfig c = ModelConfig.builder().id(1L)
                .modelName("m").baseUrl("b").apiKey("sk-abcdefghijkl").build();
        when(repo.findById(1L)).thenReturn(Optional.of(c));

        ModelConfigDTO dto = service.getMasked();

        assertEquals("********", dto.getApiKey());
    }
}
