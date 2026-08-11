package com.ka.controller;

import com.ka.config.JwtUtil;
import com.ka.config.AppConstants;
import com.ka.dto.ApiResponse;
import com.ka.dto.LoginRequest;
import com.ka.dto.LoginResponse;
import com.ka.entity.User;
import com.ka.service.NotificationService;
import com.ka.service.AuditLogService;
import com.ka.service.AuthService;
import com.ka.service.JwtBlacklistService;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器
 * - POST /api/auth/login     → 登录
 * - POST /api/auth/refresh   → 刷新 Token
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final com.ka.repository.UserRepository userRepository;
    private final JwtBlacklistService jwtBlacklistService;
    private final AppConstants constants;

    private static final String COOKIE_NAME = "ka_token";

    @Value("${ka.cookie-secure:false}")
    private boolean cookieSecure;

    /** 优先取 Authorization 头，其次取 HttpOnly Cookie（P0：token 移出 localStorage 后的主要通道） */
    private String extractToken(HttpServletRequest req) {
        String authHeader = req.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        if (req.getCookies() != null) {
            for (Cookie c : req.getCookies()) {
                if (COOKIE_NAME.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    private ResponseCookie buildCookie(String token) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true).secure(cookieSecure).sameSite("Strict")
                .path("/api").maxAge(Duration.ofHours(24)).build();
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true).secure(cookieSecure).sameSite("Strict")
                .path("/api").maxAge(Duration.ZERO).build();
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletRequest httpReq,
                                             HttpServletResponse httpResp) {
        try {
            LoginResponse response = authService.login(request);
            // P0：token 写入 HttpOnly Cookie（SameSite=Strict），前端不再存 localStorage
            httpResp.addHeader(HttpHeaders.SET_COOKIE, buildCookie(response.getToken()).toString());
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
    public ApiResponse<Map<String, String>> refresh(HttpServletRequest httpReq,
                                                    HttpServletResponse httpResp) {
        try {
            String oldToken = extractToken(httpReq);
            if (oldToken == null || oldToken.isBlank()) {
                return ApiResponse.error(401, "缺少 Token");
            }
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
            httpResp.addHeader(HttpHeaders.SET_COOKIE, buildCookie(newToken).toString());
            // 登记新 token 到该用户的活跃索引（强制登出时一并撤销）
            try {
                var claims = jwtUtil.parseToken(newToken);
                long ttlSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
                jwtBlacklistService.registerToken(userId, claims.getId(), ttlSeconds);
            } catch (Exception e) {
                log.warn("刷新后登记 token 失败: {}", e.getMessage());
            }
            return ApiResponse.success(Map.of("token", newToken, "tokenType", "Bearer"));
        } catch (RuntimeException e) {
            return ApiResponse.error(401, e.getMessage());
        }
    }

    /**
     * 登出：将当前 token 加入 Redis 黑名单，立即失效（无需等自然过期）
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpReq, HttpServletResponse httpResp) {
        String token = extractToken(httpReq);
        if (token != null && !token.isBlank()) {
            jwtBlacklistService.revoke(token);
        }
        httpResp.addHeader(HttpHeaders.SET_COOKIE, clearCookie().toString());
        return ApiResponse.success("已退出登录", null);
    }

    /** 当前登录用户信息（供前端刷新页面后经 Cookie 恢复会话） */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest httpReq,
                                               HttpServletResponse httpResp) {
        String token = extractToken(httpReq);
        if (token == null || !jwtUtil.validateToken(token)) {
            httpResp.setStatus(401);
            return ApiResponse.error(401, "未登录或登录已失效");
        }
        // 已撤销（登出/强制登出/禁用）的 token 不允许恢复会话
        if (constants.isJwtBlacklistEnabled() && jwtBlacklistService.isRevoked(jwtUtil.getJtiFromToken(token))) {
            httpResp.setStatus(401);
            return ApiResponse.error(401, "登录已失效");
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            httpResp.setStatus(401);
            return ApiResponse.error(401, "用户不存在或已被禁用");
        }
        Map<String, Object> m = new HashMap<>();
        m.put("userId", user.getId());
        m.put("username", user.getUsername());
        m.put("role", user.getRole());
        m.put("displayName", user.getDisplayName());
        m.put("storageUsed", user.getStorageUsed());
        m.put("storageLimit", user.getStorageLimit());
        return ApiResponse.success(m);
    }
}
