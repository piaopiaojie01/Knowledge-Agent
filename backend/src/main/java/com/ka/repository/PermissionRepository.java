package com.ka.repository;

import com.ka.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByUserIdAndKbId(Long userId, Long kbId);

    /** 某用户在某 KB 下的全部权限行（回收时删除所有行） */
    List<Permission> findAllByUserIdAndKbId(Long userId, Long kbId);

    List<Permission> findByUserId(Long userId);

    List<Permission> findByKbId(Long kbId);

    boolean existsByUserIdAndKbIdAndPermissionTypeIn(Long userId, Long kbId, List<String> permissionTypes);

    void deleteByUserId(Long userId);
}
