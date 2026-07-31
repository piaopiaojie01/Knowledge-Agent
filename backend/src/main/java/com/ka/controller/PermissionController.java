package com.ka.controller;

import com.ka.config.SecurityUtils;
import com.ka.dto.ApiResponse;
import com.ka.entity.Permission;
import com.ka.entity.User;
import com.ka.repository.PermissionRepository;
import com.ka.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;

    @GetMapping
    public ApiResponse<List<Permission>> listMyPermissions() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(permissionRepository.findByUserId(userId));
    }

    private static final java.util.Set<String> PERMISSION_TYPES = java.util.Set.of("READ", "WRITE", "ADMIN");

    @PostMapping("/grant")
    public ApiResponse<Void> grant(@RequestBody Map<String, String> body) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        String kbIdRaw = body.get("kbId");
        Long kbId;
        try {
            kbId = Long.valueOf(kbIdRaw);
        } catch (NumberFormatException e) {
            return ApiResponse.error(400, "kbId 非法");
        }
        String username = body.get("username");
        String permissionType = body.get("permissionType");
        if (permissionType == null || !PERMISSION_TYPES.contains(permissionType)) {
            return ApiResponse.error(400, "permissionType 仅支持 READ / WRITE / ADMIN");
        }

        if (!isKbAdmin(operatorId, kbId)) {
            return ApiResponse.error(403, "仅知识库管理员可授权");
        }
        User target = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + username));

        // 已有权限行则升级为新的 permissionType
        List<Permission> existing = permissionRepository.findAllByUserIdAndKbId(target.getId(), kbId);
        if (!existing.isEmpty()) {
            for (Permission p : existing) {
                p.setPermissionType(permissionType);
            }
            permissionRepository.saveAll(existing);
            return ApiResponse.success("权限已更新", null);
        }

        try {
            permissionRepository.save(Permission.builder()
                    .userId(target.getId()).kbId(kbId)
                    .permissionType(permissionType).grantedBy(operatorId).build());
        } catch (DataIntegrityViolationException e) {
            // 并发授权撞 uk_user_kb 唯一键：重查并升级为更新
            List<Permission> rows = permissionRepository.findAllByUserIdAndKbId(target.getId(), kbId);
            if (rows.isEmpty()) {
                return ApiResponse.error(409, "授权冲突，请重试");
            }
            for (Permission p : rows) {
                p.setPermissionType(permissionType);
            }
            permissionRepository.saveAll(rows);
        }
        return ApiResponse.success("授权成功", null);
    }

    @PostMapping("/revoke")
    public ApiResponse<Void> revoke(@RequestBody Map<String, String> body) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        Long kbId;
        try {
            kbId = Long.valueOf(body.get("kbId"));
        } catch (NumberFormatException e) {
            return ApiResponse.error(400, "kbId 非法");
        }
        String username = body.get("username");

        if (!isKbAdmin(operatorId, kbId)) {
            return ApiResponse.error(403, "仅知识库管理员可回收权限");
        }
        User target = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + username));

        List<Permission> rows = permissionRepository.findAllByUserIdAndKbId(target.getId(), kbId);
        if (rows.isEmpty()) {
            return ApiResponse.success("该用户在此知识库无权限", null);
        }

        // 不允许回收自己在该 KB 的最后一个 ADMIN 权限
        boolean targetIsSelfAdmin = target.getId().equals(operatorId)
                && rows.stream().anyMatch(p -> "ADMIN".equals(p.getPermissionType()));
        if (targetIsSelfAdmin) {
            long adminCount = permissionRepository.findByKbId(kbId).stream()
                    .filter(p -> "ADMIN".equals(p.getPermissionType())).count();
            if (adminCount <= 1) {
                return ApiResponse.error(403, "不能回收自己在该知识库的唯一 ADMIN 权限");
            }
        }

        permissionRepository.deleteAll(rows);
        return ApiResponse.success("权限已回收", null);
    }

    private boolean isKbAdmin(Long userId, Long kbId) {
        return permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(
                userId, kbId, List.of("ADMIN"));
    }
}
