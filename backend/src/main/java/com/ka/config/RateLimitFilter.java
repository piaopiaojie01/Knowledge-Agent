package com.ka.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 全局限流过滤器 —— 所有配置从 AppConstants 注入
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private final AppConstants constants;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();
        // 只对 /api/** 限流，静态文件和 HMR 不受影响
        if (!path.startsWith("/api/")) {
            chain.doFilter(req, res);
            return;
        }
        for (String w : constants.getRateLimitWhitelist()) {
            if (path.startsWith(w)) {
                chain.doFilter(req, res);
                return;
            }
        }

        String ip = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        int maxRpm = constants.getRateLimitRpm();

        WindowCounter counter = counters.compute(ip, (k, v) -> {
            if (v == null || now - v.windowStart > 60_000) {
                return new WindowCounter(now);
            }
            return v;
        });

        if (counter.count.incrementAndGet() > maxRpm) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(String.format(
                    "{\"code\":429,\"message\":\"请求过于频繁，每分钟最多%d次\"}", maxRpm));
            return;
        }
        chain.doFilter(req, res);
    }

    private static class WindowCounter {
        final long windowStart;
        final AtomicInteger count = new AtomicInteger(0);
        WindowCounter(long start) { this.windowStart = start; }
    }
}
