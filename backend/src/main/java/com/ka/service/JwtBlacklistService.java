package com.ka.service;

import com.ka.config.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * JWT 撤销黑名单（Redis）：
 * - 登出/失效后旧 token 立即不可用，无需等待自然过期。
 * - 维护 user → jti 索引（jwt:user:{userId}），支持管理员强制登出时全量撤销。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

    private static final String KEY_PREFIX = "jwt:blacklist:";
    private static final String USER_TOKEN_KEY_PREFIX = "jwt:user:";

    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;

    @org.springframework.beans.factory.annotation.Value("${jwt.expiration}")
    private long jwtExpirationMillis = 86400000L;

    /** 将 token 的 jti 加入黑名单，保留至原 token 过期时间 */
    public void revoke(String token) {
        try {
            Claims claims = jwtUtil.parseToken(token);
            String jti = claims.getId();
            long ttlSeconds = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000;
            if (jti != null && ttlSeconds > 0) {
                redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
                // 同步从该用户的活跃 token 索引中移除
                Long userId = claims.get("userId", Long.class);
                if (userId != null) {
                    redisTemplate.opsForSet().remove(USER_TOKEN_KEY_PREFIX + userId, jti);
                }
                log.info("JWT 已撤销: jti={}", jti);
            }
        } catch (Exception e) {
            log.warn("JWT 撤销失败（可能已过期或非法）: {}", e.getMessage());
        }
    }

    /**
     * 登录/刷新成功后登记该用户的活跃 token，供强制登出全量撤销。
     */
    public void registerToken(Long userId, String jti, long ttlSeconds) {
        try {
            if (userId == null || jti == null) return;
            String key = USER_TOKEN_KEY_PREFIX + userId;
            redisTemplate.opsForSet().add(key, jti);
            redisTemplate.expire(key, Duration.ofSeconds(Math.max(ttlSeconds, 60)));
        } catch (Exception e) {
            log.warn("登记用户 token 失败: userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 强制登出：撤销该用户当前登记的全部 token。
     * Redis 不可用时按已撤销处理（fail-closed，安全优先）。
     */
    public void revokeAllForUser(Long userId) {
        try {
            String key = USER_TOKEN_KEY_PREFIX + userId;
            Set<String> jtis = redisTemplate.opsForSet().members(key);
            if (jtis != null) {
                long fallbackTtl = Math.max(jwtExpirationMillis / 1000, 60);
                for (String jti : jtis) {
                    if (jti != null && !jti.isBlank()) {
                        redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(fallbackTtl));
                    }
                }
            }
            redisTemplate.delete(key);
            log.info("强制登出: userId={}, revoked={}", userId, jtis == null ? 0 : jtis.size());
        } catch (Exception e) {
            log.error("强制登出失败（Redis 不可用），按已撤销处理: userId={}", userId, e);
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
