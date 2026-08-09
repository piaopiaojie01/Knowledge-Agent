package com.ka.controller;

import com.ka.config.JwtUtil;
import com.ka.dto.ApiResponse;
import com.ka.dto.LoginRequest;
import com.ka.dto.LoginResponse;
import com.ka.service.NotificationService;
import com.ka.service.AuditLogService;
import com.ka.service.AuthService;
import com.ka.service.JwtBlacklistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器
 * - POST /api/auth/login     → 登录
 * - POST /api/auth/refresh   → 刷新 Token
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final com.ka.repository.UserRepository userRepository;
    private final JwtBlacklistService jwtBlacklistService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletRequest httpReq) {
        try {
            LoginResponse response = authService.login(request);
            AuditLogService.log("LOGIN", response.getUsername(), response.getUserId(),
                    null, null, httpReq.getRemoteAddr());
            return ApiResponse.success("登录成功", response);
        } catch (RuntimeException e) {
            AuditLogService.log("LOGIN_FAILED", request.getUsername(), null,
                    null, e.getMessage(), httpReq.getRemoteAddr());
            return ApiResponse.error(401, e.getMessage());
        }
    }

    /**
     * 刷新 JWT Token
     * 前端在 Token 即将过期时调用，续期 24 小时
     */
    @PostMapping("/refresh")
    public ApiResponse<Map<String, String>> refresh(HttpServletRequest httpReq) {
        try {
            String authHeader = httpReq.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ApiResponse.error(401, "缺少 Token");
            }
            String oldToken = authHeader.substring(7);
            // 已撤销的 token 不允许续期
            if (jwtUtil.validateToken(oldToken)) {
                String jti = jwtUtil.getJtiFromToken(oldToken);
                if (jwtBlacklistService.isRevoked(jti)) {
                    return ApiResponse.error(401, "Token 已失效，请重新登录");
                }
            }
            // 刷新前校验用户仍存在且未被禁用
            Long userId = jwtUtil.getUserIdFromToken(oldToken);
            var user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("用户不存在"));
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                return ApiResponse.error(401, "账户已被禁用");
            }
            String newToken = jwtUtil.refreshToken(oldToken);
            return ApiResponse.success(Map.of("token", newToken, "tokenType", "Bearer"));
        } catch (RuntimeException e) {
            return ApiResponse.error(401, e.getMessage());
        }
    }

    /**
     * 登出：将当前 token 加入 Redis 黑名单，立即失效（无需等自然过期）
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpReq) {
        String authHeader = httpReq.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwtBlacklistService.revoke(authHeader.substring(7));
        }
        return ApiResponse.success("已退出登录", null);
    }
}
