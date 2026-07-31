package com.ka.controller;

import com.ka.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import jakarta.annotation.PostConstruct;

/**
 * 健康监控控制器
 * GET /api/health → 返回 backend + agent 状态，供 Nginx / K8s 探活
 */
@RestController
public class HealthController {

    /** agent 基础 URL，从 application.yml 注入，不硬编码 localhost */
    @Value("${agent.base-url}")
    private String agentBaseUrl;

    @PostConstruct
    public void init() {
        // 去掉尾部斜杠
        if (agentBaseUrl != null && agentBaseUrl.endsWith("/")) {
            agentBaseUrl = agentBaseUrl.substring(0, agentBaseUrl.length() - 1);
        }
    }

    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("backend", "UP");
        status.put("time", System.currentTimeMillis());

        // 探测 Agent 健康端点
        try {
            URL url = new URL(agentBaseUrl + "/api/v1/rag/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            status.put("agent", code == 200 ? "UP" : "DOWN(" + code + ")");
        } catch (Exception e) {
            status.put("agent", "DOWN: " + e.getMessage());
        }

        String overall = "UP".equals(status.get("agent")) ? "UP" : "DEGRADED";
        status.put("status", overall);

        return ApiResponse.success(status);
    }
}
