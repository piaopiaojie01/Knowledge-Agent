package com.ka.controller;

import com.ka.client.AgentClient;
import com.ka.dto.ApiResponse;
import com.ka.dto.DocumentDTO;
import com.ka.entity.Document;
import com.ka.entity.KnowledgeBase;
import com.ka.repository.DocumentRepository;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.PermissionRepository;
import com.ka.repository.UserRepository;
import com.ka.service.DocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 文档上传/编辑守卫回归测试：空文件、配额、重复 hash、图片编辑、
 * UTF-8 校验、PDF 解析失败、超长文件名截断。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentControllerTest {

    @Mock private DocumentService documentService;
    @Mock private DocumentRepository documentRepository;
    @Mock private KnowledgeBaseRepository kbRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private UserRepository userRepository;
    @Mock private AgentClient agentClient;
    @Mock private MultipartFile file;

    private DocumentController controller;

    @BeforeEach
    void setUp() {
        controller = new DocumentController(documentService, documentRepository, kbRepository,
                permissionRepository, userRepository, agentClient);
        // 当前登录用户 id=1，默认对该 KB 有写权限
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
        when(permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(anyLong(), anyLong(), anyList()))
                .thenReturn(true);
        when(kbRepository.findById(10L)).thenReturn(Optional.of(
                KnowledgeBase.builder().id(10L).name("kb").build()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void mockFile(byte[] bytes, String filename) throws IOException {
        when(file.getBytes()).thenReturn(bytes);
        when(file.getSize()).thenReturn((long) bytes.length);
        when(file.getOriginalFilename()).thenReturn(filename);
    }

    @Test
    void 上传0字节文件返回400() throws IOException {
        mockFile(new byte[0], "empty.txt");

        ApiResponse<DocumentDTO> resp = controller.upload(file, 10L, null);

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("空文件"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void 超过大小上限返回413() throws IOException, NoSuchFieldException, IllegalAccessException {
        // 把上限临时压到 1MB，验证服务端强制大小校验
        java.lang.reflect.Field f = DocumentController.class.getDeclaredField("maxUploadMb");
        f.setAccessible(true);
        f.setLong(controller, 1L);
        mockFile(new byte[2 * 1024 * 1024], "big.pdf");

        ApiResponse<DocumentDTO> resp = controller.upload(file, 10L, null);

        assertEquals(413, resp.getCode());
        assertTrue(resp.getMessage().contains("大小超过上限"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void 非法device参数返回400() throws IOException {
        mockFile("hello".getBytes(StandardCharsets.UTF_8), "a.txt");

        ApiResponse<DocumentDTO> resp = controller.upload(file, 10L, "gpu");

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("cpu / cuda"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void 同KB重复contentHash返回409() throws IOException {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        mockFile(bytes, "a.txt");
        when(documentRepository.findFirstByKbIdAndContentHashAndDocStatus(eq(10L), anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(Document.builder().id(99L).build()));

        ApiResponse<DocumentDTO> resp = controller.upload(file, 10L, null);

        assertEquals(409, resp.getCode());
        assertTrue(resp.getMessage().contains("文档已存在"));
        verify(documentRepository, never()).save(any());
        verify(userRepository, never()).addStorageUsedIfWithinLimit(anyLong(), anyLong());
    }

    @Test
    void 原子配额更新返回0时上传被拒400() throws IOException {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        mockFile(bytes, "a.txt");
        when(documentRepository.findFirstByKbIdAndContentHashAndDocStatus(eq(10L), anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        // 模拟超限：原子更新影响 0 行
        when(userRepository.addStorageUsedIfWithinLimit(1L, (long) bytes.length)).thenReturn(0);

        ApiResponse<DocumentDTO> resp = controller.upload(file, 10L, null);

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("配额不足"));
        verify(documentRepository, never()).save(any());
        verify(agentClient, never()).ingest(any(), any(), any(), any());
    }

    @Test
    void 非UTF8字节的txt文件返回400() throws IOException {
        // 0xC3 0x28 是非法 UTF-8 序列
        mockFile(new byte[]{(byte) 0xC3, 0x28}, "bad.txt");
        when(documentRepository.findFirstByKbIdAndContentHashAndDocStatus(eq(10L), anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());

        ApiResponse<DocumentDTO> resp = controller.upload(file, 10L, null);

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("UTF-8"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void PDF解析抛IOException时返回400() throws IOException {
        // 非 PDF 内容的字节流，Loader.loadPDF 会抛 IOException
        mockFile("这不是PDF内容".getBytes(StandardCharsets.UTF_8), "broken.pdf");
        when(documentRepository.findFirstByKbIdAndContentHashAndDocStatus(eq(10L), anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());

        ApiResponse<DocumentDTO> resp = controller.upload(file, 10L, null);

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("PDF"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    void 超长文件名截断到500字符且保留扩展名() throws IOException {
        String longName = "超".repeat(600) + ".txt"; // 长度远超 500
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        mockFile(bytes, longName);
        when(documentRepository.findFirstByKbIdAndContentHashAndDocStatus(eq(10L), anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        when(userRepository.addStorageUsedIfWithinLimit(1L, (long) bytes.length)).thenReturn(1);
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            d.setId(1L);
            return d;
        });
        when(agentClient.ingest(any(), any(), any(), any()))
                .thenReturn(new AgentClient.IngestResponse(true, "ok", "ok"));

        ApiResponse<DocumentDTO> resp = controller.upload(file, 10L, null);

        assertEquals(200, resp.getCode());
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        // 受理成功会二次落库（初始 ACTIVE → PROCESSING），取任意一次检查标题即可
        verify(documentRepository, times(2)).save(captor.capture());
        String title = captor.getValue().getTitle();
        assertEquals(500, title.length(), "标题应截断到 500 字符");
        assertTrue(title.endsWith(".txt"), "截断后应保留扩展名");
    }

    @Test
    void 上传受理成功后docStatus为PROCESSING而非ACTIVE() throws IOException {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        mockFile(bytes, "a.txt");
        when(documentRepository.findFirstByKbIdAndContentHashAndDocStatus(eq(10L), anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        when(userRepository.addStorageUsedIfWithinLimit(1L, (long) bytes.length)).thenReturn(1);
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            if (d.getId() == null) d.setId(1L);
            return d;
        });
        when(agentClient.ingest(any(), any(), any(), any()))
                .thenReturn(new AgentClient.IngestResponse(true, "ok", "processing"));

        ApiResponse<DocumentDTO> resp = controller.upload(file, 10L, null);

        assertEquals(200, resp.getCode());
        assertTrue(resp.getMessage().contains("正在后台解析入库"));
        assertEquals("PROCESSING", resp.getData().getDocStatus(), "agent 受理后应处于 PROCESSING，等待轮询落定");
    }

    @Test
    void 上传受理失败时docStatus为FAILED() throws IOException {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        mockFile(bytes, "a.txt");
        when(documentRepository.findFirstByKbIdAndContentHashAndDocStatus(eq(10L), anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        when(userRepository.addStorageUsedIfWithinLimit(1L, (long) bytes.length)).thenReturn(1);
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            if (d.getId() == null) d.setId(1L);
            return d;
        });
        when(agentClient.ingest(any(), any(), any(), any()))
                .thenReturn(new AgentClient.IngestResponse(false, "Agent 不可达", null));

        ApiResponse<DocumentDTO> resp = controller.upload(file, 10L, null);

        assertEquals(200, resp.getCode());
        assertEquals("FAILED", resp.getData().getDocStatus());
    }

    @Test
    void PUT内容变更重新向量化受理成功后docStatus为PROCESSING() {
        Document doc = Document.builder().id(1L).kbId(10L).fileType("text")
                .content("old").version(1).docStatus("ACTIVE").build();
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        when(documentRepository.findFirstByKbIdAndContentHashAndDocStatus(eq(10L), anyString(), eq("ACTIVE")))
                .thenReturn(Optional.empty());
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(agentClient.ingest(any(), any(), any(), any()))
                .thenReturn(new AgentClient.IngestResponse(true, "ok", "processing"));

        ApiResponse<DocumentDTO> resp = controller.update(1L, Map.of("content", "new content"));

        assertEquals(200, resp.getCode());
        assertEquals("PROCESSING", resp.getData().getDocStatus());
    }

    @Test
    void 图片文档PUT传content返回400() {
        Document img = Document.builder().id(1L).kbId(10L).fileType("image")
                .content("").version(1).docStatus("ACTIVE").build();
        when(documentRepository.findById(1L)).thenReturn(Optional.of(img));

        ApiResponse<DocumentDTO> resp = controller.update(1L, Map.of("content", "new text"));

        assertEquals(400, resp.getCode());
        assertTrue(resp.getMessage().contains("图片"));
        verify(documentRepository, never()).save(any());
        verify(agentClient, never()).deleteByDoc(any());
    }

    @Test
    void PUT新内容与KB内其他ACTIVE文档hash冲突返回409() {
        Document doc = Document.builder().id(1L).kbId(10L).fileType("text")
                .content("old").version(1).docStatus("ACTIVE").build();
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        // 新内容 hash 撞上了 KB 内另一篇文档（id=2）
        when(documentRepository.findFirstByKbIdAndContentHashAndDocStatus(eq(10L), anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(Document.builder().id(2L).build()));

        ApiResponse<DocumentDTO> resp = controller.update(1L, Map.of("content", "duplicated content"));

        assertEquals(409, resp.getCode());
        assertTrue(resp.getMessage().contains("文档已存在"));
        verify(documentRepository, never()).save(any());
        verify(agentClient, never()).deleteByDoc(any());
    }

    @Test
    void PUT与自身hash相同不算冲突可正常更新() {
        Document doc = Document.builder().id(1L).kbId(10L).fileType("text")
                .content("old").version(1).docStatus("ACTIVE").build();
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc));
        // 撞的是自己（id 相同），应放行
        when(documentRepository.findFirstByKbIdAndContentHashAndDocStatus(eq(10L), anyString(), eq("ACTIVE")))
                .thenReturn(Optional.of(doc));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(kbRepository.findById(10L)).thenReturn(Optional.of(
                KnowledgeBase.builder().id(10L).name("kb").build()));
        when(agentClient.ingest(any(), any(), any(), any()))
                .thenReturn(new AgentClient.IngestResponse(true, "ok", "ok"));

        ApiResponse<DocumentDTO> resp = controller.update(1L, Map.of("content", "new content"));

        assertEquals(200, resp.getCode());
        verify(agentClient).deleteByDoc(1L);
        verify(agentClient).ingest(eq(1L), any(), eq("kb"), eq("new content"));
    }
}
