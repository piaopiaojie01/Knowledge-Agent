package com.ka.controller;

import com.ka.dto.ApiResponse;
import com.ka.dto.KnowledgeBaseDTO;
import com.ka.dto.PermissionView;
import com.ka.entity.User;
import com.ka.entity.AuditLog;
import com.ka.entity.KnowledgeBase;
import com.ka.service.AuditLogService;
import com.ka.service.AuthService;
import com.ka.service.PermissionService;
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
    private final PermissionService permissionService;

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

    /** 启用/禁用用户：禁用即时生效（存量 token 全部撤销） */
    @PutMapping("/users/{id}/status")
    public ApiResponse<String> setUserStatus(@PathVariable Long id,
                                             @RequestBody Map<String, Object> body,
                                             HttpServletRequest httpReq) {
        Long operatorId = com.ka.config.SecurityUtils.getCurrentUserId();
        boolean active = Boolean.TRUE.equals(body.get("isActive"));
        try {
            authService.setUserActive(operatorId, id, active);
            audit(operatorId, active ? "ENABLE_USER" : "DISABLE_USER", "用户#" + id,
                    "isActive=" + active, httpReq);
            return ApiResponse.success(active ? "已启用" : "已禁用（该用户全部会话已登出）");
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 重置密码：重置后该用户全部会话强制登出 */
    @PutMapping("/users/{id}/password")
    public ApiResponse<String> resetPassword(@PathVariable Long id,
                                             @RequestBody Map<String, String> body,
                                             HttpServletRequest httpReq) {
        Long operatorId = com.ka.config.SecurityUtils.getCurrentUserId();
        try {
            authService.resetPassword(id, body.get("password"));
            audit(operatorId, "RESET_PASSWORD", "用户#" + id, "密码已由管理员重置", httpReq);
            return ApiResponse.success("密码已重置，该用户已强制登出");
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 强制登出：撤销该用户全部活跃 token */
    @PostMapping("/users/{id}/force-logout")
    public ApiResponse<String> forceLogout(@PathVariable Long id, HttpServletRequest httpReq) {
        Long operatorId = com.ka.config.SecurityUtils.getCurrentUserId();
        try {
            authService.forceLogout(id);
            audit(operatorId, "FORCE_LOGOUT", "用户#" + id, null, httpReq);
            return ApiResponse.success("已强制该用户全部会话登出");
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
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

    /** 全部知识库（含无权访问的），供权限管理与系统运维 */
    @GetMapping("/kbs")
    public ApiResponse<List<KnowledgeBaseDTO>> listAllKbs() {
        List<KnowledgeBaseDTO> list = knowledgeBaseRepository.findAll().stream()
                .map(kb -> KnowledgeBaseDTO.builder()
                        .id(kb.getId()).name(kb.getName()).description(kb.getDescription())
                        .createdBy(kb.getCreatedBy()).isPublic(kb.getIsPublic())
                        .docCount((int) documentRepository.findByKbId(kb.getId()).stream()
                                .filter(d -> "ACTIVE".equals(d.getDocStatus())).count())
                        .createdAt(kb.getCreatedAt()).build())
                .toList();
        return ApiResponse.success(list);
    }

    /** 权限矩阵总览（全部权限行 + 用户名/知识库名） */
    @GetMapping("/permissions")
    public ApiResponse<List<PermissionView>> listPermissions() {
        return ApiResponse.success(permissionService.overview());
    }

    /** 全局管理员授权（任意用户/任意知识库） */
    @PostMapping("/permissions/grant")
    public ApiResponse<String> grantPermission(@RequestBody Map<String, String> body,
                                               HttpServletRequest httpReq) {
        Long operatorId = com.ka.config.SecurityUtils.getCurrentUserId();
        Long kbId;
        try {
            kbId = Long.valueOf(body.get("kbId"));
        } catch (NumberFormatException e) {
            return ApiResponse.error(400, "kbId 非法");
        }
        try {
            permissionService.grant(body.get("username"), kbId, body.get("permissionType"), operatorId);
            audit(operatorId, "GRANT", body.get("username"),
                    "kbId=" + kbId + ", type=" + body.get("permissionType"), httpReq);
            return ApiResponse.success("授权成功");
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    /** 全局管理员回收权限 */
    @PostMapping("/permissions/revoke")
    public ApiResponse<String> revokePermission(@RequestBody Map<String, String> body,
                                                HttpServletRequest httpReq) {
        Long operatorId = com.ka.config.SecurityUtils.getCurrentUserId();
        Long kbId;
        try {
            kbId = Long.valueOf(body.get("kbId"));
        } catch (NumberFormatException e) {
            return ApiResponse.error(400, "kbId 非法");
        }
        try {
            permissionService.revoke(body.get("username"), kbId, operatorId);
            audit(operatorId, "REVOKE", body.get("username"), "kbId=" + kbId, httpReq);
            return ApiResponse.success("权限已回收");
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    private void audit(Long operatorId, String action, String target, String detail,
                       HttpServletRequest httpReq) {
        String operatorName = userRepository.findById(operatorId)
                .map(User::getUsername).orElse(String.valueOf(operatorId));
        AuditLogService.log(action, operatorName, operatorId, target, detail, httpReq.getRemoteAddr());
    }
}
