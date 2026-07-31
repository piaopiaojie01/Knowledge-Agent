package com.ka.controller;

import com.ka.client.AgentClient;
import com.ka.config.SecurityUtils;
import com.ka.dto.ApiResponse;
import com.ka.dto.RagQueryRequest;
import com.ka.dto.RagQueryResponse;
import com.ka.entity.KnowledgeBase;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
@Slf4j
public class RagController {

    private final AgentClient agentClient;
    private final KnowledgeBaseRepository kbRepository;

    @Value("${agent.base-url}")
    private String agentBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** 流式查询专用有界线程池：避免阻塞 httpClient.send 占用 ForkJoinPool common pool */
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(16);

    @PreDestroy
    void shutdownExecutor() {
        streamExecutor.shutdown();
    }

    /** 校验当前用户对请求的每个知识库都有访问权限（公开 KB 或显式授权） */
    private void validateKbAccess(List<String> kbNames) {
        if (kbNames == null || kbNames.isEmpty()) return;
        Set<String> accessible = kbRepository
                .findAccessibleByUser(SecurityUtils.getCurrentUserId())
                .stream().map(KnowledgeBase::getName).collect(Collectors.toSet());
        for (String name : kbNames) {
            if (!accessible.contains(name)) {
                throw new RuntimeException("无权限访问知识库: " + name);
            }
        }
    }

    @PostMapping("/query")
    public ApiResponse<RagQueryResponse> query(@Valid @RequestBody RagQueryRequest request,
                                                HttpServletRequest httpReq) {
        validateKbAccess(request.getKbNames());
        AgentClient.AgentQueryResponse agentResp = agentClient.ragQuery(
                request.getQuestion(), request.getKbNames(),
                request.getHistory(), request.getSessionId());

        RagQueryResponse response = RagQueryResponse.builder()
                .success(agentResp.isSuccess())
                .answer(agentResp.getAnswer())
                .sources(agentResp.getSources())
                .metrics(agentResp.getMetrics())
                .inputTokens(agentResp.getInputTokens())
                .outputTokens(agentResp.getOutputTokens())
                .build();

        AuditLogService.log("QUERY", null, null,
                request.getQuestion().substring(0, Math.min(100, request.getQuestion().length())),
                agentResp.isSuccess() ? "OK" : "FAIL", httpReq.getRemoteAddr());

        if (agentResp.isSuccess()) {
            return ApiResponse.success(response);
        } else {
            return ApiResponse.error(500, response.getAnswer());
        }
    }

    @PostMapping("/query/stream")
    public SseEmitter queryStream(@Valid @RequestBody RagQueryRequest request) {
        validateKbAccess(request.getKbNames());

        SseEmitter emitter = new SseEmitter(120_000L);

        Map<String, Object> body = new HashMap<>();
        body.put("question", request.getQuestion());
        body.put("kb_names", request.getKbNames() != null ? request.getKbNames() : List.of());
        body.put("history", request.getHistory() != null ? request.getHistory() : List.of());
        body.put("session_id", request.getSessionId() != null ? request.getSessionId() : "");

        String baseUrl = agentBaseUrl != null && agentBaseUrl.endsWith("/")
                ? agentBaseUrl.substring(0, agentBaseUrl.length() - 1) : agentBaseUrl;

        // 异步线程读取 Agent 的 SSE 响应流，逐行透传给前端
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest httpReq = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/rag/query/stream"))
                        .timeout(Duration.ofSeconds(120))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build();
                HttpResponse<Stream<String>> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofLines());
                try (Stream<String> lines = resp.body()) {
                    lines.forEach(line -> {
                        try {
                            // 跳过空行与 SSE 注释行（":" 开头，如心跳），避免透传给前端导致 JSON 解析失败
                            if (line.isEmpty() || line.startsWith(":")) {
                                return;
                            }
                            // Agent 行格式为 "data: <payload>"，剥掉前缀（含至多一个前导空格，不用 trim，
                            // 保留 payload 首尾语义空白）再由 SseEmitter 重新封装，
                            // 避免前端收到 "data:data: ..." 导致 JSON 解析失败
                            String payload = line;
                            if (line.startsWith("data:")) {
                                payload = line.substring(5);
                                if (payload.startsWith(" ")) {
                                    payload = payload.substring(1);
                                }
                            }
                            emitter.send(SseEmitter.event().data(payload));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                emitter.complete();
            } catch (Exception e) {
                log.warn("queryStream failed: {}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error").data("流式查询失败，请稍后重试"));
                } catch (Exception sendError) {
                    log.warn("queryStream error event send failed: {}", sendError.getMessage());
                }
                emitter.complete();
            }
        }, streamExecutor);

        return emitter;
    }

    @PostMapping("/search")
    public ApiResponse<RagQueryResponse> search(@Valid @RequestBody RagQueryRequest request) {
        validateKbAccess(request.getKbNames());
        AgentClient.AgentQueryResponse agentResp = agentClient.ragSearch(
                request.getQuestion(), request.getKbNames());

        RagQueryResponse response = RagQueryResponse.builder()
                .success(agentResp.isSuccess())
                .sources(agentResp.getSources())
                .build();

        if (agentResp.isSuccess()) {
            return ApiResponse.success("检索完成", response);
        } else {
            return ApiResponse.error(500, agentResp.getAnswer());
        }
    }
}