package com.ka.controller;

import com.ka.client.AgentClient;
import com.ka.dto.ApiResponse;
import com.ka.dto.KnowledgeBaseDTO;
import com.ka.entity.KnowledgeBase;
import com.ka.repository.DocumentRepository;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.PermissionRepository;
import com.ka.service.KnowledgeBaseService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 知识库改名保护回归测试：Milvus 向量按 kb_name 过滤，改名会使向量失配，
 * 因此 PUT 改 name 必须 400；只改 description/isPublic 应放行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeBaseControllerTest {

    @Mock private KnowledgeBaseService kbService;
    @Mock private KnowledgeBaseRepository kbRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private AgentClient agentClient;

    private KnowledgeBaseController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeBaseController(kbService, kbRepository,
                documentRepository, permissionRepository, agentClient);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
        when(permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(anyLong(), anyLong(), anyList()))
                .thenReturn(true);
        when(kbRepository.findById(10L)).thenReturn(Optional.of(
                KnowledgeBase.builder().id(10L).name("原名称").description("旧描述")
                        .isPublic(false).createdBy(1L).build()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void PUT修改name返回400向量失效保护() {
        ApiResponse<KnowledgeBaseDTO> resp = controller.update(10L, Map.of("name", "新名称"));

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("向量"));
        verify(kbRepository, never()).save(any());
    }

    @Test
    void PUT传相同name不算改名可放行() {
        when(kbRepository.save(any(KnowledgeBase.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<KnowledgeBaseDTO> resp = controller.update(10L,
                Map.of("name", "原名称", "description", "新描述"));

        assertEquals(200, resp.getCode());
        verify(kbRepository).save(any(KnowledgeBase.class));
    }

    @Test
    void 只改description和isPublic正常放行() {
        when(kbRepository.save(any(KnowledgeBase.class))).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> body = new HashMap<>();
        body.put("description", "全新描述");
        body.put("isPublic", true);

        ApiResponse<KnowledgeBaseDTO> resp = controller.update(10L, body);

        assertEquals(200, resp.getCode());
        assertEquals("全新描述", resp.getData().getDescription());
        assertEquals(Boolean.TRUE, resp.getData().getIsPublic());
        assertEquals("原名称", resp.getData().getName(), "名称不应被改动");
    }

    @Test
    void 非管理员编辑返回403() {
        when(permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(anyLong(), anyLong(), anyList()))
                .thenReturn(false);

        ApiResponse<KnowledgeBaseDTO> resp = controller.update(10L, Map.of("description", "x"));

        assertEquals(403, resp.getCode());
        verify(kbRepository, never()).save(any());
    }
}
