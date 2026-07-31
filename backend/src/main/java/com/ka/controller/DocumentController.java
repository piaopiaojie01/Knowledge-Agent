package com.ka.controller;

import com.ka.client.AgentClient;
import com.ka.config.SecurityUtils;
import com.ka.dto.ApiResponse;
import com.ka.dto.DocumentDTO;
import com.ka.entity.Document;
import com.ka.entity.KnowledgeBase;
import com.ka.repository.DocumentRepository;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.PermissionRepository;
import com.ka.repository.UserRepository;
import com.ka.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/docs")
@Slf4j
@RequiredArgsConstructor
public class DocumentController {

    /** 文档标题（文件名）最大长度，超长时截断并保留扩展名 */
    private static final int MAX_TITLE_LENGTH = 500;

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final AgentClient agentClient;

    @GetMapping("/kb/{kbId}")
    public ApiResponse<List<DocumentDTO>> listByKb(@PathVariable Long kbId) {
        return ApiResponse.success(documentService.listByKb(kbId, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentDTO> getById(@PathVariable Long id) {
        return ApiResponse.success(documentService.getById(id, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/kb/{kbId}/search")
    public ApiResponse<List<DocumentDTO>> searchInKb(@PathVariable Long kbId,
                                                     @RequestParam String keyword) {
        return ApiResponse.success(documentService.searchInKb(kbId, keyword, SecurityUtils.getCurrentUserId()));
    }

    @PostMapping("/upload")
    public ApiResponse<DocumentDTO> upload(@RequestParam("file") MultipartFile file,
                                           @RequestParam("kbId") Long kbId) throws IOException {
        Long userId = SecurityUtils.getCurrentUserId();
        if (!hasWriteAccess(kbId, userId)) {
            return ApiResponse.error(403, "无权限向该知识库上传文档");
        }
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new RuntimeException("知识库不存在"));

        byte[] bytes = file.getBytes();
        if (bytes.length == 0) {
            return ApiResponse.error(400, "不允许上传空文件");
        }
        String contentHash = sha256Hex(bytes);

        // 同 KB 下内容完全相同的 ACTIVE 文档视为重复上传
        if (documentRepository.findFirstByKbIdAndContentHashAndDocStatus(kbId, contentHash, "ACTIVE").isPresent()) {
            return ApiResponse.error(409, "文档已存在");
        }

        String filename = truncateTitle(file.getOriginalFilename() != null ? file.getOriginalFilename() : "unnamed");
        String ext = extensionOf(filename);

        String content = "";
        String fileType;
        boolean isImage;
        switch (ext) {
            case "txt" -> { content = decodeUtf8(bytes); if (content == null) return ApiResponse.error(400, "文件不是有效的 UTF-8 编码"); fileType = "text"; isImage = false; }
            case "md"  -> { content = decodeUtf8(bytes); if (content == null) return ApiResponse.error(400, "文件不是有效的 UTF-8 编码"); fileType = "markdown"; isImage = false; }
            case "pdf" -> {
                try {
                    content = extractPdfText(bytes);
                } catch (IOException e) {
                    log.warn("PDF 解析失败: {}", e.getMessage());
                    return ApiResponse.error(400, "无法解析 PDF 文件");
                }
                fileType = "pdf"; isImage = false;
            }
            case "png", "jpg", "jpeg" -> { fileType = "image"; isImage = true; }
            default -> { return ApiResponse.error(400, "不支持的文件类型: " + ext); }
        }

        // 原子占用配额：0 行 = 用户不存在或超出上限
        if (userRepository.addStorageUsedIfWithinLimit(userId, file.getSize()) == 0) {
            return ApiResponse.error(400, "存储配额不足，无法上传");
        }

        Document doc = Document.builder()
                .kbId(kbId)
                .title(filename)
                .content(content)
                .fileType(fileType)
                .fileSize(file.getSize())
                .contentHash(contentHash)
                .uploadedBy(userId)
                .docStatus("ACTIVE")
                .build();
        try {
            doc = documentRepository.save(doc);
        } catch (DataIntegrityViolationException e) {
            // 并发上传撞重复（先查后插竞态）：回补配额并按冲突处理
            userRepository.subtractStorageUsed(userId, file.getSize());
            log.warn("文档保存撞唯一约束，按重复处理: kbId={}, hash={}", kbId, contentHash);
            return ApiResponse.error(409, "文档已存在");
        }

        // 同步向量化：文本/PDF 走 ingest，图片走 ingestImage（OCR 由 Agent 侧完成）
        AgentClient.IngestResponse ingestResp = isImage
                ? agentClient.ingestImage(doc.getId(), filename, kb.getName(), bytes)
                : agentClient.ingest(doc.getId(), filename, kb.getName(), content);
        if (!ingestResp.isSuccess()) {
            log.error("文档向量化失败: docId={}, message={}", doc.getId(), ingestResp.getMessage());
            doc.setDocStatus("FAILED");
            doc = documentRepository.save(doc);
        }

        return ApiResponse.success("上传成功", toDTO(doc));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("文档不存在"));
        if (!hasWriteAccess(doc.getKbId(), userId)) {
            return ApiResponse.error(403, "无权限删除该文档");
        }

        // 1. 删 Milvus 向量（失败只记日志，不阻塞 MySQL 删除）
        agentClient.deleteByDoc(id);
        // 2. 删 MySQL 行
        documentRepository.delete(doc);
        // 3. 回收存储配额（扣到上传者头上；上传者缺失时记日志跳过）
        long size = doc.getFileSize() == null ? 0 : doc.getFileSize();
        if (doc.getUploadedBy() == null) {
            log.warn("文档无上传者记录，跳过配额回收: docId={}, size={}", id, size);
        } else if (userRepository.existsById(doc.getUploadedBy())) {
            userRepository.subtractStorageUsed(doc.getUploadedBy(), size);
        } else {
            log.warn("文档上传者不存在，跳过配额回收: docId={}, uploadedBy={}, size={}",
                    id, doc.getUploadedBy(), size);
        }

        return ApiResponse.success("文档已删除", null);
    }

    @PutMapping("/{id}")
    public ApiResponse<DocumentDTO> update(@PathVariable Long id,
                                           @RequestBody Map<String, String> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("文档不存在"));
        if (!hasWriteAccess(doc.getKbId(), userId)) {
            return ApiResponse.error(403, "无权限编辑该文档");
        }

        // 图片文档的 content 为空（内容在图片二进制里），不允许编辑内容
        if ("image".equals(doc.getFileType()) && body.get("content") != null) {
            return ApiResponse.error(400, "图片文档不支持编辑内容");
        }

        if (body.get("title") != null) {
            doc.setTitle(truncateTitle(body.get("title")));
        }
        String newContent = body.get("content");
        boolean contentChanged = newContent != null && !newContent.equals(doc.getContent());
        if (contentChanged) {
            String newHash = sha256Hex(newContent.getBytes(StandardCharsets.UTF_8));
            // 新内容不得与 KB 内其他 ACTIVE 文档重复（去重不变量）
            var dup = documentRepository.findFirstByKbIdAndContentHashAndDocStatus(
                    doc.getKbId(), newHash, "ACTIVE");
            if (dup.isPresent() && !dup.get().getId().equals(id)) {
                return ApiResponse.error(409, "文档已存在");
            }
            doc.setContent(newContent);
            doc.setContentHash(newHash);
        }
        doc.setVersion(doc.getVersion() + 1);
        doc = documentRepository.save(doc);

        // 内容变化时先删旧向量再重新向量化
        if (contentChanged) {
            KnowledgeBase kb = kbRepository.findById(doc.getKbId())
                    .orElseThrow(() -> new RuntimeException("知识库不存在"));
            agentClient.deleteByDoc(id);
            AgentClient.IngestResponse ingestResp = agentClient.ingest(id, doc.getTitle(), kb.getName(), newContent);
            if (!ingestResp.isSuccess()) {
                log.error("文档更新后重新向量化失败: docId={}, message={}", id, ingestResp.getMessage());
                doc.setDocStatus("FAILED");
                doc = documentRepository.save(doc);
            }
        }

        return ApiResponse.success("文档已更新", toDTO(doc));
    }

    /** 当前用户对该 KB 是否有 WRITE 或 ADMIN 权限 */
    private boolean hasWriteAccess(Long kbId, Long userId) {
        return permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(
                userId, kbId, List.of("WRITE", "ADMIN"));
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    /** 标题超长时截断到 500 字符，尽量保留扩展名 */
    private String truncateTitle(String title) {
        if (title.length() <= MAX_TITLE_LENGTH) {
            return title;
        }
        int dot = title.lastIndexOf('.');
        String ext = dot >= 0 ? title.substring(dot) : "";
        int keep = MAX_TITLE_LENGTH - ext.length();
        return keep > 0 ? title.substring(0, keep) + ext : title.substring(0, MAX_TITLE_LENGTH);
    }

    /** 严格 UTF-8 解码，含非法字节时返回 null */
    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private String extractPdfText(byte[] bytes) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(pdf);
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }

    private DocumentDTO toDTO(Document doc) {
        return DocumentDTO.builder()
                .id(doc.getId())
                .kbId(doc.getKbId())
                .title(doc.getTitle())
                .content(doc.getContent())
                .fileType(doc.getFileType())
                .docStatus(doc.getDocStatus())
                .chunkCount(doc.getChunkCount())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
