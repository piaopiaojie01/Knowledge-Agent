package com.ka.client;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AgentClient {

    private final RestTemplate restTemplate;
    private final String agentBaseUrl;

    public AgentClient(@Value("${agent.base-url}") String agentBaseUrl,
                       @Value("${agent.connect-timeout:5000}") int connectTimeout,
                       @Value("${agent.read-timeout:60000}") int readTimeout) {
        this.agentBaseUrl = agentBaseUrl != null && agentBaseUrl.endsWith("/")
                ? agentBaseUrl.substring(0, agentBaseUrl.length() - 1)
                : agentBaseUrl;
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.restTemplate = new RestTemplate(factory);
    }

    public AgentQueryResponse ragQuery(String question, List<String> kbNames,
                                        List<Map<String, String>> history, String sessionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("question", question);
        body.put("kb_names", kbNames != null ? kbNames : List.of());
        body.put("history", history != null ? history : List.of());
        body.put("session_id", sessionId != null ? sessionId : "");
        try {
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, jsonHeaders());
            Map<String, Object> rb = restTemplate.postForEntity(agentBaseUrl + "/api/v1/rag/query", req, Map.class).getBody();
            if (rb == null) return AgentQueryResponse.builder().success(false).answer("Agent 空").build();
            return AgentQueryResponse.builder().success(true)
                    .answer((String) rb.getOrDefault("answer", ""))
                    .sources((List<Map<String, Object>>) rb.getOrDefault("sources", List.of()))
                    .metrics((Map<String, Object>) rb.get("metrics"))
                    .inputTokens(toInt(rb.get("input_tokens")))
                    .outputTokens(toInt(rb.get("output_tokens"))).build();
        } catch (RestClientException e) {
            return AgentQueryResponse.builder().success(false).answer("Agent 失败: " + e.getMessage()).build();
        }
    }

    public AgentQueryResponse ragSearch(String question, List<String> kbNames) {
        Map<String, Object> body = new HashMap<>();
        body.put("question", question); body.put("kb_names", kbNames != null ? kbNames : List.of()); body.put("top_k", 5);
        try {
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, jsonHeaders());
            Map<String, Object> rb = restTemplate.postForEntity(agentBaseUrl + "/api/v1/rag/search", req, Map.class).getBody();
            if (rb == null) return AgentQueryResponse.builder().success(false).answer("Agent 空").build();
            return AgentQueryResponse.builder().success(true)
                    .sources((List<Map<String, Object>>) rb.getOrDefault("results", List.of())).build();
        } catch (RestClientException e) {
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

    /** 查询后台入库任务状态；agent 不可达/无记录时返回 status=unknown，不抛异常 */
    public IngestStatusResponse ingestStatus(Long docId) {
        try {
            Map<String, Object> rb = restTemplate.getForEntity(
                    agentBaseUrl + "/api/v1/rag/ingest/" + docId + "/status", Map.class).getBody();
            if (rb == null) return new IngestStatusResponse("unknown", "Agent 空", 0, 0, 0, 0);
            return new IngestStatusResponse(
                    (String) rb.getOrDefault("status", "unknown"),
                    (String) rb.getOrDefault("message", ""), toInt(rb.get("inserted")),
                    toInt(rb.get("total")), toInt(rb.get("done")), toInt(rb.get("percent")));
        } catch (RestClientException e) {
            log.warn("ingestStatus 查询失败: docId={}, error={}", docId, e.getMessage());
            return new IngestStatusResponse("unknown", e.getMessage(), 0, 0, 0, 0);
        }
    }

    public void deleteByKb(String kbName) {
        Map<String, Object> body = new HashMap<>(); body.put("kb_name", kbName);
        try { restTemplate.postForEntity(agentBaseUrl + "/api/v1/rag/delete-by-kb", new HttpEntity<>(body, jsonHeaders()), Map.class); } catch (Exception e) { log.warn("deleteByKb failed: {}", e.getMessage()); }
    }

    public void deleteByDoc(Long docId) {
        Map<String, Object> body = new HashMap<>(); body.put("doc_id", docId);
        try {
            Map<String, Object> rb = restTemplate.postForEntity(agentBaseUrl + "/api/v1/rag/delete-by-doc", new HttpEntity<>(body, jsonHeaders()), Map.class).getBody();
            if (rb != null && Boolean.FALSE.equals(rb.get("success"))) {
                log.warn("deleteByDoc agent 返回失败: docId={}, error={}", docId, rb.get("error"));
            }
        } catch (Exception e) { log.warn("deleteByDoc failed: {}", e.getMessage()); }
    }

    private IngestResponse postIngest(String url, Map<String, Object> body) {
        try {
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, jsonHeaders());
            Map<String, Object> rb = restTemplate.postForEntity(agentBaseUrl + url, req, Map.class).getBody();
            if (rb == null) return new IngestResponse(false, "Agent 空", null);
            return new IngestResponse((boolean) rb.getOrDefault("success", false),
                    (String) rb.getOrDefault("message", ""), (String) rb.getOrDefault("status", "unknown"));
        } catch (RestClientException e) { return new IngestResponse(false, e.getMessage(), null); }
    }

    private HttpHeaders jsonHeaders() { return new HttpHeaders() {{ setContentType(MediaType.APPLICATION_JSON); }}; }
    private int toInt(Object o) { if (o instanceof Number n) return n.intValue(); return 0; }


    @Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class AgentQueryResponse {
        private boolean success; private String answer;
        private java.util.List<java.util.Map<String, Object>> sources;
        private java.util.Map<String, Object> metrics;
        private int inputTokens; private int outputTokens;
    }

    @Data @lombok.AllArgsConstructor @lombok.NoArgsConstructor
    public static class IngestResponse {
        private boolean success; private String message; private String status;
    }

    @Data @lombok.AllArgsConstructor @lombok.NoArgsConstructor
    public static class IngestStatusResponse {
        private String status; private String message; private Integer inserted;
        private Integer total; private Integer done;
        /** agent 侧按阶段加权计算的整体百分比（0-100），缺省 0 表示旧版 agent 未上报 */
        private Integer percent;
    }

}