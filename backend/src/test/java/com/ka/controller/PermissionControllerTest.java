package com.ka.controller;

import com.ka.dto.ApiResponse;
import com.ka.entity.Permission;
import com.ka.entity.User;
import com.ka.repository.PermissionRepository;
import com.ka.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 授权接口守卫回归测试：非法入参 400、已有权限升级、并发撞唯一键走升级而非 500。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionControllerTest {

    @Mock private PermissionRepository permissionRepository;
    @Mock private UserRepository userRepository;

    private PermissionController controller;

    @BeforeEach
    void setUp() {
        controller = new PermissionController(permissionRepository, userRepository);
        // 当前登录用户 id=1（操作者）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
        // 默认操作者是该 KB 的 ADMIN
        when(permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(anyLong(), anyLong(), anyList()))
                .thenReturn(true);
        when(userRepository.findByUsername("target"))
                .thenReturn(Optional.of(User.builder().id(2L).username("target").build()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 非法permissionType返回400() {
        ApiResponse<Void> resp = controller.grant(Map.of(
                "kbId", "10", "username", "target", "permissionType", "SUPERADMIN"));

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("permissionType"));
        verify(permissionRepository, never()).save(any());
    }

    @Test
    void kbId缺失返回400() {
        ApiResponse<Void> resp = controller.grant(Map.of(
                "username", "target", "permissionType", "READ"));

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("kbId"));
    }

    @Test
    void kbId非数字返回400() {
        ApiResponse<Void> resp = controller.grant(Map.of(
                "kbId", "abc", "username", "target", "permissionType", "READ"));

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("kbId"));
    }

    @Test
    void 已有READ权限再授权ADMIN则更新并提示权限已更新() {
        Permission existing = Permission.builder()
                .id(5L).userId(2L).kbId(10L).permissionType("READ").build();
        when(permissionRepository.findAllByUserIdAndKbId(2L, 10L)).thenReturn(List.of(existing));

        ApiResponse<Void> resp = controller.grant(Map.of(
                "kbId", "10", "username", "target", "permissionType", "ADMIN"));

        assertEquals(200, resp.getCode());
        assertEquals("权限已更新", resp.getMessage());
        assertEquals("ADMIN", existing.getPermissionType(), "已有行应被升级为 ADMIN");
        verify(permissionRepository).saveAll(List.of(existing));
        verify(permissionRepository, never()).save(any(Permission.class));
    }

    @Test
    void 并发撞唯一键时重查并升级而不是抛500() {
        when(permissionRepository.findAllByUserIdAndKbId(2L, 10L))
                // 第一次查（先查后插）：没有行；并发冲突后重查：竞争方已插入 READ 行
                .thenReturn(List.of())
                .thenReturn(List.of(Permission.builder()
                        .id(6L).userId(2L).kbId(10L).permissionType("READ").build()));
        when(permissionRepository.save(any(Permission.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key uk_user_kb"));

        ApiResponse<Void> resp = controller.grant(Map.of(
                "kbId", "10", "username", "target", "permissionType", "ADMIN"));

        assertEquals(200, resp.getCode(), "撞唯一键应走升级逻辑成功返回，而不是 500");
        assertEquals("授权成功", resp.getMessage());
        verify(permissionRepository).saveAll(argThat(rows -> {
            List<Permission> list = (List<Permission>) rows;
            return list.size() == 1 && "ADMIN".equals(list.get(0).getPermissionType());
        }));
    }

    @Test
    void 操作者非KB管理员返回403() {
        when(permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(anyLong(), anyLong(), anyList()))
                .thenReturn(false);

        ApiResponse<Void> resp = controller.grant(Map.of(
                "kbId", "10", "username", "target", "permissionType", "READ"));

        assertEquals(403, resp.getCode());
        verify(permissionRepository, never()).save(any());
    }
}
