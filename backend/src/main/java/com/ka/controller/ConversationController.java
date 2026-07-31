package com.ka.controller;

import com.ka.config.SecurityUtils;
import com.ka.dto.ApiResponse;
import com.ka.entity.Conversation;
import com.ka.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 会话持久化 API */
@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService service;

    /** 加载当前会话（仅限本人） */
    @GetMapping("/{sessionId}")
    public ApiResponse<List<Conversation>> load(@PathVariable String sessionId) {
        return ApiResponse.success(service.loadSession(sessionId, SecurityUtils.getCurrentUserId()));
    }

    /** 列出当前用户的所有会话（含标题） */
    @GetMapping("/list")
    public ApiResponse<List<Map<String, String>>> listSessions() {
        return ApiResponse.success(service.listSessions(SecurityUtils.getCurrentUserId()));
    }

    /** 重命名会话（仅限本人） */
    @PutMapping("/{sessionId}/title")
    public ApiResponse<String> rename(@PathVariable String sessionId, @RequestBody Map<String, String> body) {
        service.renameSession(sessionId, SecurityUtils.getCurrentUserId(), body.get("title"));
        return ApiResponse.success("ok");
    }
    
        /** 保存一条消息 */
    @PostMapping
    public ApiResponse<Conversation> save(@RequestBody Map<String, String> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(service.save(
                body.get("sessionId"),
                userId,
                body.get("role"),
                body.get("content"),
                toInt(body.get("inputTokens")),
                toInt(body.get("outputTokens"))
        ));
    }

    /** token 统计 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.success(service.getTokenStats(SecurityUtils.getCurrentUserId()));
    }

    
    /** 生成分享链接（仅限本人的会话） */
    @PostMapping("/share/{sessionId}")
    public ApiResponse<Map<String, Object>> share(@PathVariable String sessionId) {
        List<Conversation> msgs = service.loadSession(sessionId, SecurityUtils.getCurrentUserId());
        if (msgs.isEmpty()) {
            return ApiResponse.error(403, "会话不存在或无权限分享");
        }
        String shareId = java.util.UUID.randomUUID().toString();
        // TODO: 生产环境应存 Redis 并设 TTL；当前为内存实现，重启失效
        sharedSessions.put(shareId, msgs);
        return ApiResponse.success(Map.of("shareId", shareId, "url", "/share/" + shareId));
    }
    private static final java.util.Map<String, Object> sharedSessions = new java.util.concurrent.ConcurrentHashMap<>();


    private int toInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }

    /** 清空会话（仅限本人） */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> clear(@PathVariable String sessionId) {
        service.clearSession(sessionId, SecurityUtils.getCurrentUserId());
        return ApiResponse.success("ok", null);
    }
}
