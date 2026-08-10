package com.ka.controller;

import com.ka.dto.ApiResponse;
import com.ka.repository.PermissionRepository;
import com.ka.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 授权接口守卫回归测试：非法入参 400、非 KB 管理员 403、授权/回收委托 PermissionService。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionControllerTest {

    @Mock private PermissionRepository permissionRepository;
    @Mock private PermissionService permissionService;

    private PermissionController controller;

    @BeforeEach
    void setUp() {
        controller = new PermissionController(permissionRepository, permissionService);
        // 当前登录用户 id=1（操作者）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
        // 默认操作者是该 KB 的 ADMIN
        when(permissionService.isKbAdmin(1L, 10L)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 非法permissionType返回400() {
        doThrow(new RuntimeException("permissionType 仅支持 READ / WRITE / ADMIN"))
                .when(permissionService).grant(anyString(), anyLong(), anyString(), anyLong());

        ApiResponse<Void> resp = controller.grant(Map.of(
                "kbId", "10", "username", "target", "permissionType", "SUPERADMIN"));

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("permissionType"));
        verify(permissionService).grant("target", 10L, "SUPERADMIN", 1L);
    }

    @Test
    void kbId缺失返回400() {
        ApiResponse<Void> resp = controller.grant(Map.of(
                "username", "target", "permissionType", "READ"));

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("kbId"));
        verify(permissionService, never()).grant(anyString(), anyLong(), anyString(), anyLong());
    }

    @Test
    void kbId非数字返回400() {
        ApiResponse<Void> resp = controller.grant(Map.of(
                "kbId", "abc", "username", "target", "permissionType", "READ"));

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("kbId"));
    }

    @Test
    void 授权成功委托服务() {
        ApiResponse<Void> resp = controller.grant(Map.of(
                "kbId", "10", "username", "target", "permissionType", "ADMIN"));

        assertEquals(200, resp.getCode());
        verify(permissionService).grant("target", 10L, "ADMIN", 1L);
    }

    @Test
    void 操作者非KB管理员返回403() {
        when(permissionService.isKbAdmin(1L, 10L)).thenReturn(false);

        ApiResponse<Void> resp = controller.grant(Map.of(
                "kbId", "10", "username", "target", "permissionType", "READ"));

        assertEquals(403, resp.getCode());
        verify(permissionService, never()).grant(anyString(), anyLong(), anyString(), anyLong());
    }

    @Test
    void 回收权限委托服务() {
        ApiResponse<Void> resp = controller.revoke(Map.of(
                "kbId", "10", "username", "target"));

        assertEquals(200, resp.getCode());
        verify(permissionService).revoke("target", 10L, 1L);
    }

    @Test
    void 回收被拒时返回400() {
        doThrow(new RuntimeException("不能回收自己在该知识库的唯一 ADMIN 权限"))
                .when(permissionService).revoke(anyString(), anyLong(), anyLong());

        ApiResponse<Void> resp = controller.revoke(Map.of(
                "kbId", "10", "username", "target"));

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("唯一 ADMIN"));
    }
}
