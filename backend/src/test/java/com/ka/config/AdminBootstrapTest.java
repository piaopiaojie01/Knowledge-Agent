package com.ka.config;

import com.ka.entity.User;
import com.ka.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 初始管理员引导：创建、默认密码升级、不覆盖已自定义密码、未配置时跳过 */
class AdminBootstrapTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() throws Exception {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("encoded-new");
        bootstrap = new AdminBootstrap(userRepository, passwordEncoder);
        setField("adminUsername", "admin");
        setField("adminPassword", "My@Pass123");
    }

    private void setField(String name, String value) throws Exception {
        var f = AdminBootstrap.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(bootstrap, value);
    }

    @Test
    void 未配置密码时跳过() throws Exception {
        setField("adminPassword", "");
        bootstrap.run(null);
        verify(userRepository, never()).save(any());
    }

    @Test
    void 管理员不存在时创建() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        bootstrap.run(null);
        verify(userRepository).save(argThat(u ->
                "admin".equals(u.getUsername())
                        && "ADMIN".equals(u.getRole())
                        && "encoded-new".equals(u.getPasswordHash())
                        && Boolean.TRUE.equals(u.getIsActive())));
    }

    @Test
    void 仍是默认种子密码时升级() {
        User admin = User.builder().id(1L).username("admin").role("ADMIN")
                .passwordHash("seed-hash").isActive(true).build();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("admin123", "seed-hash")).thenReturn(true);

        bootstrap.run(null);

        assertEquals("encoded-new", admin.getPasswordHash());
        verify(userRepository).save(admin);
    }

    @Test
    void 已自定义密码时不覆盖() {
        User admin = User.builder().id(1L).username("admin").role("ADMIN")
                .passwordHash("custom-hash").isActive(true).build();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("admin123", "custom-hash")).thenReturn(false);

        bootstrap.run(null);

        assertEquals("custom-hash", admin.getPasswordHash());
        verify(userRepository, never()).save(any());
    }
}
