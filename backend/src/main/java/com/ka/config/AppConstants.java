package com.ka.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用配置常量类 —— 消除硬编码
 * 所有可配置项从 application.yml 注入，不再在 Java 里写死
 */
@Data
@Component
@ConfigurationProperties(prefix = "ka")
public class AppConstants {

    /** CORS 允许的域名白名单 */
    private List<String> corsOrigins = List.of(
            "http://localhost:8080",
            "http://localhost:3000",
            "http://localhost:5173",
            "http://localhost:9888",
            "https://knowledge-agent.your-company.com"
    );

    /** API 限流：每分钟每 IP 最大请求数 */
    private int rateLimitRpm = 300;

    /** API 限流白名单路径（不受限流） */
    private List<String> rateLimitWhitelist = List.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/health",
            "/api/kb",
            "/api/conversation/list",
            "/api/notifications/unread-count",
            "/api/conversation/stats",
            "/favicon.ico"
    );

    /** 登录限流：最大失败次数 */
    private int loginMaxFailures = 5;

    /** 登录限流：锁定分钟数 */
    private int loginLockMinutes = 30;

    /** 审计日志：数据库保留天数 */
    private int auditRetentionDays = 365;

    /** 密码最小长度 */
    private int passwordMinLength = 8;

    /** JWT 撤销黑名单开关（Redis）。Redis 不可用时 fail-closed */
    private boolean jwtBlacklistEnabled = true;

    /** 上传文件大小上限（MB） */
    private long maxUploadMb = 100;

    /** 演示专用：允许 *.trycloudflare.com 域名的浏览器跨域访问（默认关闭） */
    private boolean corsAllowTrycloudflare = false;
}
