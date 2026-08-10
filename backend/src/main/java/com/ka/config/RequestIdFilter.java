package com.ka.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/** 请求 ID：优先透传前端/上游 X-Request-Id，否则生成；写入 MDC 供日志关联，响应头回传 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements Filter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;
        String rid = httpReq.getHeader(HEADER);
        if (rid == null || rid.isBlank() || rid.length() > 64) {
            rid = UUID.randomUUID().toString();
        }
        httpResp.setHeader(HEADER, rid);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, rid)) {
            chain.doFilter(request, response);
        }
    }
}
