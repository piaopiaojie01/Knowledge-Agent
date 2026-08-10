package com.ka.controller;

import com.ka.config.SecurityUtils;
import com.ka.dto.ApiResponse;
import com.ka.entity.Permission;
import com.ka.repository.PermissionRepository;
import com.ka.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionRepository permissionRepository;
    private final PermissionService permissionService;

    @GetMapping
    public ApiResponse<List<Permission>> listMyPermissions() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(permissionRepository.findByUserId(userId));
    }

    @PostMapping("/grant")
    public ApiResponse<Void> grant(@RequestBody Map<String, String> body) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        Long kbId;
        try {
            kbId = parseKbId(body.get("kbId"));
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
        if (!permissionService.isKbAdmin(operatorId, kbId)) {
            return ApiResponse.error(403, "仅知识库管理员可授权");
        }
        try {
            permissionService.grant(body.get("username"), kbId, body.get("permissionType"), operatorId);
            return ApiResponse.success("授权成功", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/revoke")
    public ApiResponse<Void> revoke(@RequestBody Map<String, String> body) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        Long kbId;
        try {
            kbId = parseKbId(body.get("kbId"));
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
        if (!permissionService.isKbAdmin(operatorId, kbId)) {
            return ApiResponse.error(403, "仅知识库管理员可回收权限");
        }
        try {
            permissionService.revoke(body.get("username"), kbId, operatorId);
            return ApiResponse.success("权限已回收", null);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    private Long parseKbId(String kbIdRaw) {
        try {
            return Long.valueOf(kbIdRaw);
        } catch (NumberFormatException e) {
            throw new RuntimeException("kbId 非法");
        }
    }
}
