package com.ka.controller;

import com.ka.config.SecurityUtils;
import com.ka.dto.ApiResponse;
import com.ka.entity.Notification;
import com.ka.repository.UserRepository;
import com.ka.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;
    private final UserRepository userRepository;

    /** 解析当前登录用户名（通知按用户名隔离） */
    private String currentUsername() {
        Long userId = SecurityUtils.getCurrentUserId();
        return userRepository.findById(userId)
                .map(u -> u.getUsername())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
    }

    @GetMapping
    public ApiResponse<List<Notification>> list() {
        return ApiResponse.success(service.getRecent(currentUsername()));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.success(service.getUnreadCount(currentUsername()));
    }

    @PostMapping("/mark-read")
    public ApiResponse<String> markRead() {
        service.markAllRead(currentUsername());
        return ApiResponse.success("ok");
    }
}
