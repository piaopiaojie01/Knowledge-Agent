package com.ka.config;

import com.ka.service.JwtBlacklistService;
import com.ka.entity.User;
import com.ka.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final JwtBlacklistService jwtBlacklistService;
    private final AppConstants constants;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtil.validateToken(token)) {
            // P0：已撤销（登出/失效）的 token 立即拒绝
            if (constants.isJwtBlacklistEnabled()) {
                String jti = jwtUtil.getJtiFromToken(token);
                if (jwtBlacklistService.isRevoked(jti)) {
                    log.warn("拒绝已撤销的 token: jti={}", jti);
                    filterChain.doFilter(request, response);
                    return;
                }
            }
            Long userId = jwtUtil.getUserIdFromToken(token);
            // 用户被禁用/删除后，存量 token 立即失效（无需等自然过期）
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
                log.warn("拒绝非活跃用户的 token: userId={}", userId);
                filterChain.doFilter(request, response);
                return;
            }
            String username = jwtUtil.getUsernameFromToken(token);
            String roleClaim = jwtUtil.parseToken(token).get("role", String.class);
            String role = "ROLE_" + (StringUtils.hasText(roleClaim) ? roleClaim : "USER");

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId, null,
                            List.of(new SimpleGrantedAuthority(role)));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * SSE（SseEmitter）异步分发时会重新进入过滤器链，而默认跳过异步分发会导致
     * Spring Security 6 将第二次校验判为匿名并拒绝（AccessDenied）。
     * 这里改为在异步分发时重新解析 JWT，保证长连接鉴权一致。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
