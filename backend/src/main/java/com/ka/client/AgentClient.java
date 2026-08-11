package com.ka.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Slf4j
@Component
public class AgentClient {

    /** P0：Agent 调用容错 —— 轻量重试 + 熔断，避免依赖服务抖动拖垮主链路 */
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 300L;
    private static final int FAILURE_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_MS = 30_000L;

    private final RestTemplate restTemplate;
    private final String agentBaseUrl;
    private final String apiKey;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenedAt = 0L;

    @Autowired
    public AgentClient(@Value("${agent.base-url}") String agentBaseUrl,
                       @Value("${agent.connect-timeout:5000}") int connectTimeout,
                       @Value("${agent.read-timeout:60000}") int readTimeout,
                       @Value("${agent.api-key:}") String apiKey) {
        this(new RestTemplate(), agentBaseUrl, apiKey);
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.restTemplate.setRequestFactory(factory);
    }

    /** 测试注入用：使用外部 RestTemplate */
    AgentClient(RestTemplate restTemplate, String agentBaseUrl, String apiKey) {
        this.restTemplate = restTemplate;
        this.agentBaseUrl = agentBaseUrl != null && agentBaseUrl.endsWith("/")
                ? agentBaseUrl.substring(0, agentBaseUrl.length() - 1)
                : agentBaseUrl;
        this.apiKey = apiKey;
    }

    private boolean isCircuitOpen() {
        long opened = circuitOpenedAt;
        if (opened == 0L) return false;
        if (System.currentTimeMillis() - opened > CIRCUIT_OPEN_MS) {
            circuitOpenedAt = 0L; // 半开：放一个请求试探
            return false;
        }
        return true;
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        circuitOpenedAt = 0L;
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
            circuitOpenedAt = System.currentTimeMillis();
            log.warn("Agent 调用连续失败 {} 次，熔断 {}ms", FAILURE_THRESHOLD, CIRCUIT_OPEN_MS);
        }
    }

    /** 带重试的 Agent 调用：网络/超时类异常重试（带退避）；熔断期内快速失败 */
    private <T> T executeWithRetry(String op, Supplier<T> action) {
        if (isCircuitOpen()) {
            throw new RuntimeException("Agent 熔断中，请稍后重试: " + op);
        }
        RestClientException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                T result = action.get();
                recordSuccess();
                return result;
            } catch (RestClientException e) {
                last = e;
                recordFailure();
                if (attempt < MAX_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(BASE_BACKOFF_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new RuntimeException("Agent 调用失败: " + op + " - " + (last == null ? "unknown" : last.getMessage()), last);
    }

    public AgentQueryResponse ragQuery(String question, List<String> kbNames,
                                        List<Map<String, String>> history, String sessionId,
                                        Map<String, Object> llmConfig,
                                        List<String> skills,
                                        List<Map<String, String>> mcpServers) {
        Map<String, Object> body = new HashMap<>();
        body.put("question", question);
        body.put("kb_names", kbNames != null ? kbNames : List.of());
        body.put("history", history != null ? history : List.of());
        body.put("session_id", sessionId != null ? sessionId : "");
        if (llmConfig != null && !llmConfig.isEmpty()) {
            body.put("llm_config", llmConfig);
        }
        if (skills != null && !skills.isEmpty()) {
            body.put("skills", skills);
        }
        if (mcpServers != null && !mcpServers.isEmpty()) {
            body.put("mcp_servers", mcpServers);
        }
        try {
            Map<String, Object> rb = executeWithRetry("query", () -> {
                HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, jsonHeaders());
                return restTemplate.postForEntity(agentBaseUrl + "/api/v1/rag/query", req, Map.class).getBody();
            });
            if (rb == null) return AgentQueryResponse.builder().success(false).answer("Agent 空").build();
            return AgentQueryResponse.builder().success(true)
                    .answer((String) rb.getOrDefault("answer", ""))
                    .sources((List<Map<String, Object>>) rb.getOrDefault("sources", List.of()))
                    .metrics((Map<String, Object>) rb.get("metrics"))
                    .inputTokens(toInt(rb.get("input_tokens")))
                    .outputTokens(toInt(rb.get("output_tokens")))
                    .cacheHitTokens(toInt(rb.get("cache_hit_tokens")))
                    .cacheMissTokens(toInt(rb.get("cache_miss_tokens")))
                    .build();
        } catch (RuntimeException e) {
            return AgentQueryResponse.builder().success(false).answer("Agent 失败: " + e.getMessage()).build();
        }
    }

    public AgentQueryResponse ragSearch(String question, List<String> kbNames,
                                        Map<String, Object> llmConfig) {
        Map<String, Object> body = new HashMap<>();
        body.put("question", question); body.put("kb_names", kbNames != null ? kbNames : List.of()); body.put("top_k", 5);
        if (llmConfig != null && !llmConfig.isEmpty()) {
            body.put("llm_config", llmConfig);
        }
        try {
            Map<String, Object> rb = executeWithRetry("search", () -> {
                HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, jsonHeaders());
                return restTemplate.postForEntity(agentBaseUrl + "/api/v1/rag/search", req, Map.class).getBody();
            });
            if (rb == null) return AgentQueryResponse.builder().success(false).answer("Agent 空").build();
            return AgentQueryResponse.builder().success(true)
                    .sources((List<Map<String, Object>>) rb.getOrDefault("results", List.of())).build();
        } catch (RuntimeException e) {
            return AgentQueryResponse.builder().success(false).answer("Agent 失败: " + e.getMessage()).build();
        }
    }

    public IngestResponse ingest(Long docId, String title, String kbName, String content) {
        Map<String, Object> body = new HashMap<>();
        body.put("doc_id", docId); body.put("title", title); body.put("kb_name", kbName); body.put("content", content);
        return postIngest("/api/v1/rag/ingest", body);
    }

    public IngestResponse ingestPdf(Long docId, String title, String kbName, byte[] pdfBytes, String device) {
        Map<String, Object> body = new HashMap<>();
        body.put("doc_id", docId); body.put("title", title); body.put("kb_name", kbName);
        body.put("pdf_base64", java.util.Base64.getEncoder().encodeToString(pdfBytes));
        if (device != null) body.put("device", device);  // cpu / cuda，null 时用服务端默认
        return postIngest("/api/v1/rag/ingest-pdf", body);
    }

    public IngestResponse ingestImage(Long docId, String title, String kbName, byte[] imgBytes, String device) {
        Map<String, Object> body = new HashMap<>();
        body.put("doc_id", docId); body.put("title", title); body.put("kb_name", kbName);
        // agent 侧 /ingest-image 复用 PdfUpload 模型，字段名是 pdf_base64（图片字节也放这里）
        body.put("pdf_base64", java.util.Base64.getEncoder().encodeToString(imgBytes));
        if (device != null) body.put("device", device);
        return postIngest("/api/v1/rag/ingest-image", body);
    }

    /** Excel 入库：agent 同步解析并返回内容预览（供 MySQL 全文搜索/展示） */
    public IngestResponse ingestExcel(Long docId, String title, String kbName, byte[] bytes) {
        Map<String, Object> body = new HashMap<>();
        body.put("doc_id", docId); body.put("title", title); body.put("kb_name", kbName);
        // 复用 PdfUpload 模型字段名 pdf_base64
        body.put("pdf_base64", java.util.Base64.getEncoder().encodeToString(bytes));
        return postIngest("/api/v1/rag/ingest-excel", body);
    }

    /** 查询后台入库任务状态；agent 不可达/无记录时返回 status=unknown，不抛异常 */
    public IngestStatusResponse ingestStatus(Long docId) {
        try {
            Map<String, Object> rb = executeWithRetry("ingestStatus", () -> {
                HttpHeaders headers = jsonHeaders();
                HttpEntity<Void> entity = new HttpEntity<>(headers);
                return restTemplate.exchange(
                        agentBaseUrl + "/api/v1/rag/ingest/" + docId + "/status",
                        HttpMethod.GET, entity, Map.class).getBody();
            });
            if (rb == null) return new IngestStatusResponse("unknown", "Agent 空", 0, 0, 0, 0);
            return new IngestStatusResponse(
                    (String) rb.getOrDefault("status", "unknown"),
                    (String) rb.getOrDefault("message", ""), toInt(rb.get("inserted")),
                    toInt(rb.get("total")), toInt(rb.get("done")), toInt(rb.get("percent")));
        } catch (RuntimeException e) {
            log.warn("ingestStatus 查询失败: docId={}, error={}", docId, e.getMessage());
            return new IngestStatusResponse("unknown", e.getMessage(), 0, 0, 0, 0);
        }
    }

    public void deleteByKb(String kbName) {
        Map<String, Object> body = new HashMap<>(); body.put("kb_name", kbName);
        try {
            executeWithRetry("deleteByKb", () -> restTemplate.postForEntity(
                    agentBaseUrl + "/api/v1/rag/delete-by-kb", new HttpEntity<>(body, jsonHeaders()), Map.class));
        } catch (Exception e) { log.warn("deleteByKb failed: {}", e.getMessage()); }
    }

    public void deleteByDoc(Long docId) {
        Map<String, Object> body = new HashMap<>(); body.put("doc_id", docId);
        try {
            Map<String, Object> rb = executeWithRetry("deleteByDoc", () -> restTemplate.postForEntity(
                    agentBaseUrl + "/api/v1/rag/delete-by-doc", new HttpEntity<>(body, jsonHeaders()), Map.class).getBody());
            if (rb != null && Boolean.FALSE.equals(rb.get("success"))) {
                log.warn("deleteByDoc agent 返回失败: docId={}, error={}", docId, rb.get("error"));
            }
        } catch (Exception e) { log.warn("deleteByDoc failed: {}", e.getMessage()); }
    }

    private IngestResponse postIngest(String url, Map<String, Object> body) {
        try {
            Map<String, Object> rb = executeWithRetry(url, () -> {
                HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, jsonHeaders());
                return restTemplate.postForEntity(agentBaseUrl + url, req, Map.class).getBody();
            });
            if (rb == null) return new IngestResponse(false, "Agent 空", null);
            return new IngestResponse((boolean) rb.getOrDefault("success", false),
                    (String) rb.getOrDefault("message", ""), (String) rb.getOrDefault("status", "unknown"),
                    (String) rb.getOrDefault("content_preview", ""));
        } catch (RuntimeException e) { return new IngestResponse(false, e.getMessage(), null); }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            // P0：Agent 内部鉴权密钥，防止 8000 端口被未授权调用
            headers.set("X-KA-API-Key", apiKey);
        }
        // 可观测性：透传请求 ID，便于 Agent 日志串联
        String rid = org.slf4j.MDC.get(com.ka.config.RequestIdFilter.MDC_KEY);
        if (rid != null && !rid.isBlank()) {
            headers.set("X-Request-Id", rid);
        }
        return headers;
    }
    private int toInt(Object o) { if (o instanceof Number n) return n.intValue(); return 0; }


    @Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class AgentQueryResponse {
        private boolean success; private String answer;
        private java.util.List<java.util.Map<String, Object>> sources;
        private java.util.Map<String, Object> metrics;
        private int inputTokens; private int outputTokens;
        private int cacheHitTokens; private int cacheMissTokens;
    }

    @Data @lombok.AllArgsConstructor @lombok.NoArgsConstructor
    public static class IngestResponse {
        private boolean success; private String message; private String status;
        private String contentPreview;

        public IngestResponse(boolean success, String message, String status) {
            this(success, message, status, null);
        }
    }

    @Data @lombok.AllArgsConstructor @lombok.NoArgsConstructor
    public static class IngestStatusResponse {
        private String status; private String message; private Integer inserted;
        private Integer total; private Integer done;
        /** agent 侧按阶段加权计算的整体百分比（0-100），缺省 0 表示旧版 agent 未上报 */
        private Integer percent;
    }

}
