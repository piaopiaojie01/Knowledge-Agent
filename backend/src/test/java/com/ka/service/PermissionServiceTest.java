package com.ka.service;

import com.ka.dto.PermissionView;
import com.ka.entity.KnowledgeBase;
import com.ka.entity.Permission;
import com.ka.entity.User;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.PermissionRepository;
import com.ka.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 授权业务逻辑：已有权限升级、并发撞唯一键、自撤最后 ADMIN 守卫、矩阵视图 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionServiceTest {

    @Mock private PermissionRepository permissionRepository;
    @Mock private UserRepository userRepository;
    @Mock private KnowledgeBaseRepository kbRepository;

    private PermissionService service;

    @BeforeEach
    void setUp() {
        service = new PermissionService(permissionRepository, userRepository, kbRepository);
        when(kbRepository.existsById(10L)).thenReturn(true);
        when(userRepository.findByUsername("target"))
                .thenReturn(Optional.of(User.builder().id(2L).username("target").build()));
    }

    @Test
    void 已有权限再授权升级而不新增行() {
        Permission existing = Permission.builder()
                .id(5L).userId(2L).kbId(10L).permissionType("READ").build();
        when(permissionRepository.findAllByUserIdAndKbId(2L, 10L)).thenReturn(List.of(existing));

        service.grant("target", 10L, "ADMIN", 1L);

        assertEquals("ADMIN", existing.getPermissionType(), "已有行应被升级为 ADMIN");
        verify(permissionRepository).saveAll(List.of(existing));
        verify(permissionRepository, never()).save(any(Permission.class));
    }

    @Test
    void 并发撞唯一键时重查并升级而不是抛异常() {
        when(permissionRepository.findAllByUserIdAndKbId(2L, 10L))
                .thenReturn(List.of())
                .thenReturn(List.of(Permission.builder()
                        .id(6L).userId(2L).kbId(10L).permissionType("READ").build()));
        when(permissionRepository.save(any(Permission.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry for key uk_user_kb"));

        assertDoesNotThrow(() -> service.grant("target", 10L, "ADMIN", 1L));

        verify(permissionRepository).saveAll(argThat(rows -> {
            List<Permission> list = (List<Permission>) rows;
            return list.size() == 1 && "ADMIN".equals(list.get(0).getPermissionType());
        }));
    }

    @Test
    void 回收自己唯一ADMIN权限被拒绝() {
        when(userRepository.findByUsername("self"))
                .thenReturn(Optional.of(User.builder().id(1L).username("self").build()));
        Permission selfAdmin = Permission.builder()
                .id(9L).userId(1L).kbId(10L).permissionType("ADMIN").build();
        when(permissionRepository.findAllByUserIdAndKbId(1L, 10L)).thenReturn(List.of(selfAdmin));
        when(permissionRepository.findByKbId(10L)).thenReturn(List.of(selfAdmin));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.revoke("self", 10L, 1L));
        assertTrue(e.getMessage().contains("唯一 ADMIN"));
    }

    @Test
    void 回收权限删除行() {
        Permission row = Permission.builder()
                .id(8L).userId(2L).kbId(10L).permissionType("READ").build();
        when(permissionRepository.findAllByUserIdAndKbId(2L, 10L)).thenReturn(List.of(row));

        service.revoke("target", 10L, 1L);

        verify(permissionRepository).deleteAll(List.of(row));
    }

    @Test
    void 矩阵视图关联用户名与知识库名() {
        Permission p = Permission.builder()
                .id(1L).userId(2L).kbId(10L).permissionType("WRITE").grantedBy(1L)
                .createdAt(LocalDateTime.of(2026, 8, 10, 12, 0)).build();
        when(permissionRepository.findAll()).thenReturn(List.of(p));
        when(userRepository.findAllById(Set.of(2L))).thenReturn(List.of(
                User.builder().id(2L).username("target").build()));
        when(kbRepository.findAllById(Set.of(10L))).thenReturn(List.of(
                KnowledgeBase.builder().id(10L).name("心理学").build()));
        when(userRepository.findAllById(Set.of(1L))).thenReturn(List.of(
                User.builder().id(1L).username("admin").build()));

        List<PermissionView> views = service.overview();

        assertEquals(1, views.size());
        PermissionView v = views.get(0);
        assertEquals("target", v.username());
        assertEquals("心理学", v.kbName());
        assertEquals("WRITE", v.permissionType());
        assertEquals("admin", v.grantedByName());
    }
}
