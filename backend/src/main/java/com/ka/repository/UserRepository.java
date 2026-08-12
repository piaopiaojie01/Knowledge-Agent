package com.ka.repository;

import com.ka.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    /** 默认存储上限 5GB（storageLimit 为 NULL/0/负数时按此值处理） */
    long DEFAULT_STORAGE_LIMIT = 5368709120L;

    /**
     * 原子增加已用存储；仅在不超过上限时生效。
     * 返回更新行数，0 行表示用户不存在或超出配额。
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.storageUsed = u.storageUsed + :delta WHERE u.id = :id " +
           "AND u.storageUsed + :delta <= (CASE WHEN u.storageLimit IS NULL OR u.storageLimit <= 0 " +
           "THEN :defaultLimit ELSE u.storageLimit END)")
    int addStorageUsedIfWithinLimit(@Param("id") Long id, @Param("delta") long delta,
                                    @Param("defaultLimit") long defaultLimit);

    /** 原子扣减已用存储，下限为 0 */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.storageUsed = (CASE WHEN u.storageUsed - :delta < 0 THEN 0 " +
           "ELSE u.storageUsed - :delta END) WHERE u.id = :id")
    int subtractStorageUsed(@Param("id") Long id, @Param("delta") long delta);
}
