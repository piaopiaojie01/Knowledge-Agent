package com.ka.service;

import com.ka.dto.KnowledgeBaseDTO;
import com.ka.entity.KnowledgeBase;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepository;
    private final PermissionRepository permissionRepository;

    public List<KnowledgeBaseDTO> listAccessible(Long userId) {
        List<KnowledgeBase> kbs = kbRepository.findAccessibleByUser(userId);
        return kbs.stream().map(this::toDTO).collect(Collectors.toList());
    }

    public KnowledgeBaseDTO getById(Long kbId, Long userId) {
        KnowledgeBase kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new RuntimeException("知识库不存在"));

        // 检查权限
        if (!kb.getIsPublic()) {
            boolean hasAccess = permissionRepository
                    .existsByUserIdAndKbIdAndPermissionTypeIn(userId, kbId,
                            List.of("READ", "WRITE", "ADMIN"));
            if (!hasAccess) {
                throw new RuntimeException("无权限访问该知识库");
            }
        }

        return toDTO(kb);
    }

    private KnowledgeBaseDTO toDTO(KnowledgeBase kb) {
        return KnowledgeBaseDTO.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .createdBy(kb.getCreatedBy())
                .isPublic(kb.getIsPublic())
                .createdAt(kb.getCreatedAt())
                .build();
    }
}
