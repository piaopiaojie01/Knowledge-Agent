package com.ka.config;

import com.ka.entity.User;
import com.ka.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 初始管理员引导：
 * - 配置了 KA_ADMIN_USERNAME/KA_ADMIN_PASSWORD 时，首次启动自动创建管理员；
 * - 若已有管理员仍在使用默认种子密码（admin123），自动升级为配置的密码；
 * - 管理员已改过密码则不动，避免每次启动覆盖。
 * 用途：一键部署脚本把用户自选的账号密码写进 .env，由这里落库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ka.admin.username:admin}")
    private String adminUsername;

    @Value("${ka.admin.password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (adminPassword == null || adminPassword.isBlank()) {
            return;
        }
        User admin = userRepository.findByUsername(adminUsername).orElse(null);
        if (admin == null) {
            userRepository.save(User.builder()
                    .username(adminUsername)
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .role("ADMIN")
                    .isActive(true)
                    .build());
            log.info("已按部署配置创建初始管理员: {}", adminUsername);
            return;
        }
        // 仍为 sql/init.sql 默认种子密码（admin123）时才升级；已自定义过密码的不覆盖
        if ("ADMIN".equals(admin.getRole())
                && passwordEncoder.matches("admin123", admin.getPasswordHash())) {
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            userRepository.save(admin);
            log.info("已将默认管理员密码升级为部署配置值: {}", adminUsername);
        }
    }
}
