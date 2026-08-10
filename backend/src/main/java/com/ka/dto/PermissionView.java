package com.ka.dto;

import com.ka.entity.Permission;

import java.time.LocalDateTime;

/** 权限矩阵视图：权限行 + 用户名/知识库名，供管理端展示 */
public record PermissionView(
        Long userId,
        String username,
        Long kbId,
        String kbName,
        String permissionType,
        String grantedByName,
        LocalDateTime createdAt) {

    public static PermissionView of(Permission p, String username, String kbName, String grantedByName) {
        return new PermissionView(
                p.getUserId(), username, p.getKbId(), kbName,
                p.getPermissionType(), grantedByName, p.getCreatedAt());
    }
}
