package com.ka.service;

import com.ka.config.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * JWT 撤销黑名单（Redis）：
 * 登出/失效后旧 token 立即不可用，无需等待自然过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

    private static final String KEY_PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;

    /** 将 token 的 jti 加入黑名单，保留至原 token 过期时间 */
    public void revoke(String token) {
        try {
            Claims claims = jwtUtil.parseToken(token);
            String jti = claims.getId();
            long ttlSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
            if (jti != null && ttlSeconds > 0) {
                redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
                log.info("JWT 已撤销: jti={}", jti);
            }
        } catch (Exception e) {
            log.warn("JWT 撤销失败（可能已过期或非法）: {}", e.getMessage());
        }
    }

    /**
     * 查询 jti 是否已撤销。
     * Redis 不可用时按已撤销处理（fail-closed，安全优先），
     * 可通过 ka.jwt-blacklist-enabled=false 关闭整条黑名单链路。
     */
    public boolean isRevoked(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            log.error("Redis 不可用，JWT 黑名单按已撤销处理（fail-closed）", e);
            return true;
        }
    }
}
