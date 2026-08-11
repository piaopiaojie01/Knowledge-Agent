package com.ka.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P0 容错：Agent 调用重试（网络抖动自动恢复）与熔断（连续失败快速失败） */
class AgentClientTest {

    private RestTemplate restTemplate;
    private AgentClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new AgentClient(restTemplate, "http://agent:8000", "key");
    }

    @Test
    void 网络抖动两次失败后重试成功() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(new RestClientException("connect timeout"))
                .thenThrow(new RestClientException("read timeout"))
                .thenAnswer(inv -> new org.springframework.http.ResponseEntity<>(
                        Map.of("answer", "ok", "sources", List.of(), "input_tokens", 10, "output_tokens", 5),
                        org.springframework.http.HttpStatus.OK));

        AgentClient.AgentQueryResponse resp = client.ragQuery("q", List.of("kb"), List.of(), "s1",
                Map.of(), List.of(), List.of());

        assertTrue(resp.isSuccess());
        assertEquals("ok", resp.getAnswer());
        verify(restTemplate, times(3)).postForEntity(anyString(), any(), eq(Map.class));
    }

    @Test
    void 重试耗尽后返回失败而非抛异常() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(new RestClientException("agent down"));

        AgentClient.AgentQueryResponse resp = client.ragQuery("q", List.of("kb"), List.of(), "s1",
                Map.of(), List.of(), List.of());

        assertFalse(resp.isSuccess());
        assertTrue(resp.getAnswer().contains("Agent 失败"));
        verify(restTemplate, times(3)).postForEntity(anyString(), any(), eq(Map.class));
    }

    @Test
    void 连续失败触发熔断快速失败() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Map.class)))
                .thenThrow(new RestClientException("agent down"));

        // 前 5 次调用触发熔断（每次内部重试 3 次）
        for (int i = 0; i < 5; i++) {
            client.ragQuery("q", List.of("kb"), List.of(), "s1", Map.of(), List.of(), List.of());
        }
        int invocationsAfterFailures = mockingDetails(restTemplate).getInvocations().size();

        // 熔断期内：不再发起 HTTP，快速返回失败
        AgentClient.AgentQueryResponse resp = client.ragQuery("q", List.of("kb"), List.of(), "s1",
                Map.of(), List.of(), List.of());

        assertFalse(resp.isSuccess());
        assertTrue(resp.getAnswer().contains("熔断"));
        assertEquals(invocationsAfterFailures, mockingDetails(restTemplate).getInvocations().size());
    }
}
