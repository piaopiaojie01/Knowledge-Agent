package com.ka.service;

import com.ka.dto.DocumentDTO;
import com.ka.entity.Document;
import com.ka.entity.KnowledgeBase;
import com.ka.repository.DocumentRepository;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.PermissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 全文搜索关键词清洗与 DTO content 裁剪回归测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentServiceTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private KnowledgeBaseRepository knowledgeBaseRepository;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(documentRepository, permissionRepository, knowledgeBaseRepository);
        // 公开知识库，免权限行
        when(knowledgeBaseRepository.findById(10L)).thenReturn(Optional.of(
                KnowledgeBase.builder().id(10L).name("kb").isPublic(true).build()));
    }

    @Test
    void 布尔模式特殊字符被清洗后再下推SQL() {
        when(documentRepository.searchByKeyword(eq(10L), anyString())).thenReturn(List.of());

        documentService.searchInKb(10L, "hello +\"*@ world", 1L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(documentRepository).searchByKeyword(eq(10L), captor.capture());
        String safe = captor.getValue();
        assertFalse(safe.matches(".*[+\\-><()~\"*@|].*"),
                "清洗后的关键词不应再含 BOOLEAN MODE 特殊字符: " + safe);
        assertTrue(safe.contains("hello") && safe.contains("world"), "普通字符应保留");
    }

    @Test
    void 清洗后为空的关键词直接返回空列表不查库() {
        List<DocumentDTO> result = documentService.searchInKb(10L, "+\"*@>()~", 1L);

        assertTrue(result.isEmpty(), "全是特殊字符的关键词清洗后为空，应返回空列表");
        verify(documentRepository, never()).searchByKeyword(anyLong(), anyString());
    }

    @Test
    void keyword为null时返回空列表() {
        List<DocumentDTO> result = documentService.searchInKb(10L, null, 1L);

        assertTrue(result.isEmpty());
        verify(documentRepository, never()).searchByKeyword(anyLong(), anyString());
    }

    @Test
    void listByKb返回的DTO不含content() {
        Document doc = Document.builder().id(1L).kbId(10L).title("t")
                .content("大段正文内容").fileType("text").docStatus("ACTIVE").build();
        when(documentRepository.findByKbIdAndDocStatusIn(eq(10L), anyList())).thenReturn(List.of(doc));

        List<DocumentDTO> list = documentService.listByKb(10L, 1L);

        assertEquals(1, list.size());
        assertNull(list.get(0).getContent(), "列表 DTO 必须裁剪掉 content，避免响应过大");
        assertEquals("t", list.get(0).getTitle());
    }

    @Test
    void getById返回的DTO含content() {
        Document doc = Document.builder().id(1L).kbId(10L).title("t")
                .content("大段正文内容").fileType("text").docStatus("ACTIVE").build();
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));

        DocumentDTO dto = documentService.getById(1L, 1L);

        assertEquals("大段正文内容", dto.getContent(), "详情接口应返回完整 content");
    }

    @Test
    void 私有KB无权限时抛异常() {
        when(knowledgeBaseRepository.findById(20L)).thenReturn(Optional.of(
                KnowledgeBase.builder().id(20L).name("private").isPublic(false).build()));
        when(permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(eq(1L), eq(20L), anyList()))
                .thenReturn(false);

        assertThrows(RuntimeException.class, () -> documentService.listByKb(20L, 1L));
    }
}
