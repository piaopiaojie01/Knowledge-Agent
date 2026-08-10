package com.ka.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Spring Security 工具类
 */
public class SecurityUtils {

    /**
     * 从 SecurityContext 获取当前登录用户 ID
     * JwtAuthFilter 将 userId 设为 Authentication.principal
     */
    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        }
        throw new RuntimeException("未登录或 Token 无效");
    }

    /** 当前用户是否为全局管理员（ROLE_ADMIN） */
    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
