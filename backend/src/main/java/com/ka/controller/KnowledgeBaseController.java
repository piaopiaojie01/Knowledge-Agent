package com.ka.controller;

import com.ka.client.AgentClient;
import com.ka.config.SecurityUtils;
import com.ka.dto.ApiResponse;
import com.ka.service.NotificationService;
import com.ka.dto.KnowledgeBaseDTO;
import com.ka.entity.Document;
import com.ka.entity.KnowledgeBase;
import com.ka.entity.Permission;
import com.ka.repository.DocumentRepository;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.PermissionRepository;
import com.ka.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final KnowledgeBaseRepository kbRepository;
    private final DocumentRepository documentRepository;
    private final PermissionRepository permissionRepository;
    private final AgentClient agentClient;

    @GetMapping
    public ApiResponse<List<KnowledgeBaseDTO>> list() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<KnowledgeBaseDTO> kbs = kbService.listAccessible(userId);
        for (KnowledgeBaseDTO kb : kbs) {
            kb.setDocCount((int) documentRepository.findByKbId(kb.getId()).stream()
                    .filter(d -> "ACTIVE".equals(d.getDocStatus())).count());
        }
        return ApiResponse.success(kbs);
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseDTO> getById(@PathVariable Long id) {
        return ApiResponse.success(kbService.getById(id, SecurityUtils.getCurrentUserId()));
    }

    @PostMapping
    public ApiResponse<KnowledgeBaseDTO> create(@RequestBody Map<String, String> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        KnowledgeBase kb = KnowledgeBase.builder().name(body.get("name"))
                .description(body.getOrDefault("description", "")).createdBy(userId).isPublic(false).build();
        kb = kbRepository.save(kb);
        permissionRepository.save(Permission.builder().userId(userId).kbId(kb.getId())
                .permissionType("ADMIN").grantedBy(userId).build());
        return ApiResponse.success("知识库创建成功",
                KnowledgeBaseDTO.builder().id(kb.getId()).name(kb.getName())
                        .description(kb.getDescription()).createdBy(userId)
                        .isPublic(false).docCount(0).createdAt(kb.getCreatedAt()).build());
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseDTO> update(@PathVariable Long id,
                                                @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (!permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(userId, id, List.of("ADMIN")))
            return ApiResponse.error(403, "无权限编辑此知识库");

        KnowledgeBase kb = kbRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("知识库不存在"));

        // Milvus 向量按 kb_name 过滤，修改 name 会导致已入库向量失配且无法迁移，
        // 因此禁止改名；如需改名请新建知识库并迁移文档。
        if (body.get("name") != null && !body.get("name").equals(kb.getName())) {
            return ApiResponse.error(400, "修改名称会导致已入库向量失效，请新建知识库并迁移文档");
        }
        if (body.get("description") != null) kb.setDescription((String) body.get("description"));
        if (body.get("isPublic") != null) kb.setIsPublic((Boolean) body.get("isPublic"));
        kb = kbRepository.save(kb);

        return ApiResponse.success("知识库已更新",
                KnowledgeBaseDTO.builder().id(kb.getId()).name(kb.getName())
                        .description(kb.getDescription()).createdBy(kb.getCreatedBy())
                        .isPublic(kb.getIsPublic()).createdAt(kb.getCreatedAt()).build());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        if (!permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(userId, id, List.of("ADMIN")))
            return ApiResponse.error(403, "无权限删除此知识库");

        KnowledgeBase kb = kbRepository.findById(id).orElseThrow(() -> new RuntimeException("知识库不存在"));

        // 1. 删 Milvus 向量
        agentClient.deleteByKb(kb.getName());
        // 2. 删文档
        List<Document> docs = documentRepository.findByKbId(id);
        documentRepository.deleteAll(docs);
        // 3. 删权限
        permissionRepository.deleteAll(permissionRepository.findByKbId(id));
        // 4. 删知识库
        kbRepository.deleteById(id);

        return ApiResponse.success("知识库及关联数据已删除", null);
    }
}
