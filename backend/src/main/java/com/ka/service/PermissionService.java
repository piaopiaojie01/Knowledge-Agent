package com.ka.service;

import com.ka.dto.PermissionView;
import com.ka.entity.KnowledgeBase;
import com.ka.entity.Permission;
import com.ka.entity.User;
import com.ka.repository.KnowledgeBaseRepository;
import com.ka.repository.PermissionRepository;
import com.ka.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 知识库授权服务：校验、授权、回收、矩阵视图（供 KB 管理员与全局管理员复用） */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private static final Set<String> PERMISSION_TYPES = Set.of("READ", "WRITE", "ADMIN");

    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final KnowledgeBaseRepository kbRepository;

    public void grant(String username, Long kbId, String permissionType, Long operatorId) {
        if (permissionType == null || !PERMISSION_TYPES.contains(permissionType)) {
            throw new RuntimeException("permissionType 仅支持 READ / WRITE / ADMIN");
        }
        if (kbId == null || !kbRepository.existsById(kbId)) {
            throw new RuntimeException("知识库不存在");
        }
        User target = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + username));

        List<Permission> existing = permissionRepository.findAllByUserIdAndKbId(target.getId(), kbId);
        if (!existing.isEmpty()) {
            for (Permission p : existing) {
                p.setPermissionType(permissionType);
            }
            permissionRepository.saveAll(existing);
            return;
        }
        try {
            permissionRepository.save(Permission.builder()
                    .userId(target.getId()).kbId(kbId)
                    .permissionType(permissionType).grantedBy(operatorId).build());
        } catch (DataIntegrityViolationException e) {
            // 并发授权撞 uk_user_kb 唯一键：重查并升级为更新
            List<Permission> rows = permissionRepository.findAllByUserIdAndKbId(target.getId(), kbId);
            if (rows.isEmpty()) {
                throw new RuntimeException("授权冲突，请重试");
            }
            for (Permission p : rows) {
                p.setPermissionType(permissionType);
            }
            permissionRepository.saveAll(rows);
        }
    }

    public void revoke(String username, Long kbId, Long operatorId) {
        if (kbId == null || !kbRepository.existsById(kbId)) {
            throw new RuntimeException("知识库不存在");
        }
        User target = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + username));

        List<Permission> rows = permissionRepository.findAllByUserIdAndKbId(target.getId(), kbId);
        if (rows.isEmpty()) {
            return;
        }

        // 不允许回收自己在该 KB 的最后一个 ADMIN 权限
        boolean targetIsSelfAdmin = target.getId().equals(operatorId)
                && rows.stream().anyMatch(p -> "ADMIN".equals(p.getPermissionType()));
        if (targetIsSelfAdmin) {
            long adminCount = permissionRepository.findByKbId(kbId).stream()
                    .filter(p -> "ADMIN".equals(p.getPermissionType())).count();
            if (adminCount <= 1) {
                throw new RuntimeException("不能回收自己在该知识库的唯一 ADMIN 权限");
            }
        }
        permissionRepository.deleteAll(rows);
    }

    public boolean isKbAdmin(Long userId, Long kbId) {
        return permissionRepository.existsByUserIdAndKbIdAndPermissionTypeIn(
                userId, kbId, List.of("ADMIN"));
    }

    /** 权限矩阵总览：全部权限行 + 用户名/知识库名/授权人 */
    public List<PermissionView> overview() {
        List<Permission> all = permissionRepository.findAll();
        if (all.isEmpty()) return List.of();
        Map<Long, String> usernames = userRepository.findAllById(
                        all.stream().map(Permission::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, User::getUsername));
        Map<Long, String> kbNames = kbRepository.findAllById(
                        all.stream().map(Permission::getKbId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(KnowledgeBase::getId, KnowledgeBase::getName));
        Set<Long> grantorIds = all.stream().map(Permission::getGrantedBy)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> grantorNames = grantorIds.isEmpty() ? Map.of()
                : userRepository.findAllById(grantorIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getUsername));
        return all.stream()
                .map(p -> PermissionView.of(p,
                        usernames.get(p.getUserId()),
                        kbNames.get(p.getKbId()),
                        grantorNames.get(p.getGrantedBy())))
                .toList();
    }
}
