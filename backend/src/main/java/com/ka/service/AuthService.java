package com.ka.service;

import com.ka.config.AppConstants;
import com.ka.config.JwtUtil;
import com.ka.dto.LoginRequest;
import com.ka.dto.LoginResponse;
import com.ka.entity.User;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 认证服务：登录/用户管理/失败限流 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppConstants constants;
    private final UserRepository userRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    private static final Map<String, FailRecord> loginFailures = new ConcurrentHashMap<>();

    private static final Pattern PW_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).{8,}$");

    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername();
        FailRecord record = loginFailures.get(username);
        if (record != null && record.isLocked()) {
            long remain = (record.lockUntil - System.currentTimeMillis()) / 60000 + 1;
            throw new RuntimeException("账户已锁定，请" + remain + "分钟后再试");
        }
        // 统一错误提示，避免用户名枚举
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            incrementFail(username);
            throw new RuntimeException("用户名或密码错误");
        }
        if (!user.getIsActive()) throw new RuntimeException("账户已被禁用");
        loginFailures.remove(username);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, "Bearer", user.getId(), user.getUsername(),
                user.getDisplayName(), user.getRole(), user.getStorageUsed(), user.getStorageLimit());
    }

    private void incrementFail(String username) {
        loginFailures.computeIfAbsent(username,
            k -> new FailRecord(0, constants.getLoginMaxFailures(), constants.getLoginLockMinutes()))
            .increment(constants.getLoginMaxFailures(), constants.getLoginLockMinutes());
    }

    public List<User> listUsers() { return userRepository.findAll(); }

    public void createUser(String name, String pw) {
        if (name == null || name.isBlank()) throw new RuntimeException("用户名不能为空");
        if (name.length() < 3) throw new RuntimeException("用户名至少3个字符");
        if (pw == null || !PW_PATTERN.matcher(pw).matches())
            throw new RuntimeException("密码需8位以上，且包含大小写字母、数字和特殊字符");
        if (userRepository.findByUsername(name).isPresent()) throw new RuntimeException("用户名已存在");
        userRepository.save(User.builder().username(name).passwordHash(passwordEncoder.encode(pw)).role("USER").build());
    }

    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        // 防止删除建有知识库的用户导致外键约束 500
        if (!knowledgeBaseRepository.findByCreatedBy(id).isEmpty()) {
            throw new RuntimeException("该用户名下还有知识库，请先转移或删除后再删除用户");
        }
        userRepository.deleteById(id);
    }

    private static class FailRecord {
        int count; long lockUntil;
        FailRecord(int count, int maxFails, long lockMins) { this.count = count; }
        void increment(int maxFails, long lockMins) { count++; if (count >= maxFails) lockUntil = System.currentTimeMillis() + lockMins * 60000; }
        boolean isLocked() { return lockUntil > System.currentTimeMillis(); }
    }
}
