package com.ka.service;

import com.ka.config.AppConstants;
import com.ka.config.JwtUtil;
import com.ka.dto.LoginRequest;
import com.ka.dto.LoginResponse;
import com.ka.entity.User;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuthServiceTest {

    @Mock private AppConstants constants;
    @Mock private UserRepository userRepository;
    @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        lenient().when(constants.getLoginMaxFailures()).thenReturn(5);
        when(constants.getLoginLockMinutes()).thenReturn(30);
        authService = new AuthService(constants, userRepository, knowledgeBaseRepository, passwordEncoder, jwtUtil);
    }

    private LoginRequest req(String u, String p) {
        LoginRequest r = new LoginRequest();
        r.setUsername(u);
        r.setPassword(p);
        return r;
    }

    @Test
    void loginSuccess() {
        User user = User.builder().id(1L).username("admin")
                .passwordHash("hashed").role("ADMIN").isActive(true).build();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("admin123", "hashed")).thenReturn(true);
        when(jwtUtil.generateToken(1L, "admin", "ADMIN")).thenReturn("jwt-token");

        LoginResponse resp = authService.login(req("admin", "admin123"));
        assertEquals("admin", resp.getUsername());
        assertEquals("jwt-token", resp.getToken());
    }

    @Test
    void loginWrongPassword() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class,
                () -> authService.login(req("admin", "wrong")));
    }

    @Test
    void createUserWeakPassword() {
        assertThrows(RuntimeException.class,
                () -> authService.createUser("u123", "123"));
    }

    @Test
    void createUserStrongPassword() {
        when(userRepository.findByUsername("u123")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        assertDoesNotThrow(() -> authService.createUser("u123", "StrongP@ss1"));
        verify(userRepository).save(any(User.class));
    }
}
