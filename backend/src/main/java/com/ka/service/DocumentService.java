package com.ka.service;

import com.ka.dto.DocumentDTO;
import com.ka.entity.Document;
import com.ka.repository.DocumentRepository;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final PermissionRepository permissionRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public List<DocumentDTO> listByKb(Long kbId, Long userId) {
        checkReadAccess(kbId, userId);
        List<Document> docs = documentRepository.findByKbIdAndDocStatusIn(
                kbId, List.of("ACTIVE", "PROCESSING", "FAILED"));
        // 列表接口不返回 content，避免响应过大
        return docs.stream().map(this::toListDTO).collect(Collectors.toList());
    }

    public DocumentDTO getById(Long docId, Long userId) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在"));
        checkReadAccess(doc.getKbId(), userId);
        return toDTO(doc);
    }

    public List<DocumentDTO> searchInKb(Long kbId, String keyword, Long userId) {
        checkReadAccess(kbId, userId);
        // 清洗 MySQL BOOLEAN MODE 特殊字符，避免全文检索语法错误
        String safeKeyword = keyword == null ? "" : keyword.replaceAll("[+\\-><()~\"*@|]", " ").trim();
        if (safeKeyword.isEmpty()) {
            return List.of();
        }
        List<Document> docs = documentRepository.searchByKeyword(kbId, safeKeyword);
        return docs.stream().map(this::toListDTO).collect(Collectors.toList());
    }

    private void checkReadAccess(Long kbId, Long userId) {
        var kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new RuntimeException("知识库不存在"));
        if (Boolean.TRUE.equals(kb.getIsPublic())) {
            return;
        }
        boolean hasAccess = permissionRepository
                .existsByUserIdAndKbIdAndPermissionTypeIn(userId, kbId,
                        List.of("READ", "WRITE", "ADMIN"));
        if (!hasAccess) {
            throw new RuntimeException("无权限访问该知识库文档");
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

    /** 列表/搜索场景用 DTO：不带 content，避免大字段拖垮响应 */
    private DocumentDTO toListDTO(Document doc) {
        return DocumentDTO.builder()
                .id(doc.getId())
                .kbId(doc.getKbId())
                .title(doc.getTitle())
                .fileType(doc.getFileType())
                .docStatus(doc.getDocStatus())
                .chunkCount(doc.getChunkCount())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
