package com.ka.service;

import com.ka.entity.Notification;
import com.ka.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository repo;
    private final org.springframework.mail.MailSender mailSender;
    private static NotificationService INSTANCE; // 静态单例，供各处埋点调用

    @jakarta.annotation.PostConstruct
    void init() { INSTANCE = this; }

    /** 静态快捷方法——各处埋点调用 */
    /** 静态快捷方法 —— 各 Controller 埋点调用此方法即可触发通知 */
    public static void notify(String username, String type, String message) {
        if (INSTANCE == null) return;
        INSTANCE.create(username, type, message);
    }

        /** 发送邮件通知 */
    @Value("${spring.mail.to:}")
    private String mailTo;
    public void sendEmail(String subject, String body) {
        if (mailTo == null || mailTo.isBlank()) return;
        try {
            org.springframework.mail.SimpleMailMessage msg = new org.springframework.mail.SimpleMailMessage();
            msg.setTo(mailTo); msg.setSubject("[KA] " + subject); msg.setText(body);
            mailSender.send(msg);
        } catch (Exception e) { log.warn("邮件发送失败: {}", e.getMessage()); }
    }


    public void create(String username, String type, String message) {
        try {
            repo.save(Notification.builder()
                    .username(username).type(type).message(message).build());
        } catch (Exception e) { log.warn("通知创建失败: {}", e.getMessage()); }
    }

    public List<Notification> getRecent(String username) {
        return repo.findTop50ByUsernameOrderByCreatedAtDesc(username);
    }

    public long getUnreadCount(String username) {
        return repo.countByUsernameAndIsReadFalse(username);
    }

    @Transactional
    public void markAllRead(String username) {
        repo.markAllReadByUsername(username);
    }
}
