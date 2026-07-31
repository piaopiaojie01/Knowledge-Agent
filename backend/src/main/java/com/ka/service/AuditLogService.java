package com.ka.service;

import com.ka.entity.AuditLog;
import com.ka.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 审计日志服务
 * 提供静态 log() 方法，方便在任何 Controller 中一行调用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    /** 静态代理实例，由 Spring 注入 */
    private static AuditLogService instance;
    private final AuditLogRepository repo;

    @jakarta.annotation.PostConstruct
    public void init() { instance = this; }

    /**
     * 记录审计日志（静态方法，全局可用）
     * @param action  操作类型：LOGIN / QUERY / UPLOAD / DELETE_DOC / CREATE_USER / DELETE_USER
     * @param username 操作人
     * @param userId   操作人ID
     * @param target   操作对象（文档名、KB名等）
     * @param detail   详情
     * @param ip       客户端IP
     */
    public java.util.List<com.ka.entity.AuditLog> getRecent() { return repo.findTop100ByOrderByCreatedAtDesc(); }

    public static void log(String action, String username, Long userId,
                           String target, String detail, String ip) {
        if (instance == null) return;
        try {
            AuditLog entry = AuditLog.builder()
                    .action(action).username(username).userId(userId)
                    .target(target).detail(detail).ip(ip).build();
            instance.repo.save(entry);
        } catch (Exception e) { log.warn("audit log save failed: {}", e.getMessage()); }
    }

    /** 最近100条审计记录 */
    public List<AuditLog> listRecent() {
        return repo.findTop100ByOrderByCreatedAtDesc();
    }
}
