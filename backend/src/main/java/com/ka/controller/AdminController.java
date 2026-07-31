package com.ka.controller;

import com.ka.dto.ApiResponse;
import com.ka.entity.User;
import com.ka.entity.AuditLog;
import com.ka.service.AuditLogService;
import com.ka.service.AuthService;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 管理员接口：用户管理、审计日志、统计 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AuthService authService;
    private final AuditLogService auditLogService;
    private final com.ka.repository.UserRepository userRepository;
    private final com.ka.repository.DocumentRepository documentRepository;
    private final com.ka.repository.KnowledgeBaseRepository knowledgeBaseRepository;
    private final com.ka.repository.ConversationRepository conversationRepository;

    @GetMapping("/users")
    public ApiResponse<List<User>> listUsers() {
        List<User> users = authService.listUsers();
        users.forEach(u -> u.setPasswordHash(""));
        return ApiResponse.success(users);
    }

    @PostMapping("/users")
    public ApiResponse<String> createUser(@RequestBody Map<String, String> body) {
        authService.createUser(body.get("username"), body.get("password"));
        return ApiResponse.success("创建成功");
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<String> deleteUser(@PathVariable Long id) {
        if (id.equals(com.ka.config.SecurityUtils.getCurrentUserId())) {
            return ApiResponse.error(400, "不能删除当前登录账户");
        }
        try {
            authService.deleteUser(id);
            return ApiResponse.success("已删除");
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return ApiResponse.error(400, "该用户存在关联数据，无法删除");
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/audit")
    public ApiResponse<List<AuditLog>> audit() {
        return ApiResponse.success(auditLogService.getRecent());
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> r = new java.util.HashMap<>();
        r.put("userCount", userRepository.count());
        r.put("docCount", documentRepository.count());
        r.put("kbCount", knowledgeBaseRepository.count());
        return ApiResponse.success(r);
    }

    @PostMapping("/users/batch")
    public ApiResponse<Map<String, Object>> batchUsers(@RequestBody Map<String, String> body) {
        String csv = body.getOrDefault("csv", "");
        int ok = 0, failed = 0;
        for (String l : csv.split("\n")) {
            String[] p = l.trim().split("\\s*,\\s*");
            if (p.length < 2) { failed++; continue; }
            try { authService.createUser(p[0].trim(), p[1].trim()); ok++; } catch (Exception e) { failed++; }
        }
        return ApiResponse.success(Map.of("ok", ok, "failed", failed));
    }
}
