package com.ka.repository;

import com.ka.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配额原子更新的真实 SQL 回归测试（H2 + @DataJpaTest）：
 * 覆盖 addStorageUsedIfWithinLimit 的限额判断（含 NULL/0/负数走 5GB 默认）
 * 与 subtractStorageUsed 的 CASE WHEN 防负数兜底。
 */
@DataJpaTest
class UserRepositoryQuotaTest {

    private static final long GB5 = 5368709120L;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    /** 新建用户并落库，返回其 id；storageLimit 传 null 表示数据库列为 NULL */
    private Long newUser(String username, Long storageUsed, Long storageLimit) {
        User u = User.builder()
                .username(username)
                .passwordHash("hash")
                .storageUsed(storageUsed)
                .storageLimit(storageLimit)
                .build();
        em.persist(u);
        em.flush();
        return u.getId();
    }

    /** 绕过持久化上下文缓存，读数据库里的真实值 */
    private long storageUsedOf(Long id) {
        em.clear();
        return em.find(User.class, id).getStorageUsed();
    }

    /**
     * 实体声明 storage_limit 为 NOT NULL，但生产历史数据存在 NULL（SQL 已兼容），
     * 这里用原生 SQL 放开约束并置 NULL 来模拟历史行。
     */
    private void forceStorageLimitNull(Long id) {
        em.getEntityManager()
                .createNativeQuery("ALTER TABLE users ALTER COLUMN storage_limit SET NULL")
                .executeUpdate();
        em.getEntityManager()
                .createNativeQuery("UPDATE users SET storage_limit = NULL WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    @Test
    void 未超限时返回1行且storageUsed增加() {
        Long id = newUser("quota_ok", 100L, 1000L);

        int rows = userRepository.addStorageUsedIfWithinLimit(id, 500L, UserRepository.DEFAULT_STORAGE_LIMIT);

        assertEquals(1, rows, "未超限应更新 1 行");
        assertEquals(600L, storageUsedOf(id), "storageUsed 应累加 delta");
    }

    @Test
    void 超限时返回0行且storageUsed不变() {
        Long id = newUser("quota_over", 900L, 1000L);

        int rows = userRepository.addStorageUsedIfWithinLimit(id, 200L, UserRepository.DEFAULT_STORAGE_LIMIT);

        assertEquals(0, rows, "超限应更新 0 行（原子判断，不加不减）");
        assertEquals(900L, storageUsedOf(id), "超限时 storageUsed 必须保持不变");
    }

    @Test
    void 恰好等于上限时允许更新() {
        Long id = newUser("quota_edge", 400L, 1000L);

        int rows = userRepository.addStorageUsedIfWithinLimit(id, 600L, UserRepository.DEFAULT_STORAGE_LIMIT);

        assertEquals(1, rows, "storageUsed + delta == limit 边界应放行");
        assertEquals(1000L, storageUsedOf(id));
    }

    @Test
    void storageLimit为NULL时按5GB默认上限处理() {
        Long id = newUser("quota_null_limit", GB5 - 100, GB5);
        forceStorageLimitNull(id);

        int ok = userRepository.addStorageUsedIfWithinLimit(id, 100L, UserRepository.DEFAULT_STORAGE_LIMIT);
        assertEquals(1, ok, "NULL 上限按 5GB 计，未超应放行");
        assertEquals(GB5, storageUsedOf(id));

        int over = userRepository.addStorageUsedIfWithinLimit(id, 1L, UserRepository.DEFAULT_STORAGE_LIMIT);
        assertEquals(0, over, "NULL 上限按 5GB 计，超出 1 字节也应拒绝");
        assertEquals(GB5, storageUsedOf(id));
    }

    @Test
    void storageLimit为0或负数时按5GB默认上限处理() {
        Long zeroId = newUser("quota_zero_limit", GB5 - 100, 0L);
        Long negId = newUser("quota_neg_limit", GB5 - 100, -1L);

        assertEquals(1, userRepository.addStorageUsedIfWithinLimit(zeroId, 100L, UserRepository.DEFAULT_STORAGE_LIMIT),
                "上限为 0 应走 5GB 默认，未超放行");
        assertEquals(0, userRepository.addStorageUsedIfWithinLimit(zeroId, 1L, UserRepository.DEFAULT_STORAGE_LIMIT),
                "上限为 0 应走 5GB 默认，超出拒绝");

        assertEquals(1, userRepository.addStorageUsedIfWithinLimit(negId, 100L, UserRepository.DEFAULT_STORAGE_LIMIT),
                "上限为负数应走 5GB 默认，未超放行");
        assertEquals(0, userRepository.addStorageUsedIfWithinLimit(negId, 1L, UserRepository.DEFAULT_STORAGE_LIMIT),
                "上限为负数应走 5GB 默认，超出拒绝");
    }

    @Test
    void 用户不存在时返回0行() {
        assertEquals(0, userRepository.addStorageUsedIfWithinLimit(999999L, 100L, UserRepository.DEFAULT_STORAGE_LIMIT));
    }

    @Test
    void 扣减正常生效() {
        Long id = newUser("quota_sub", 500L, 1000L);

        int rows = userRepository.subtractStorageUsed(id, 200L);

        assertEquals(1, rows);
        assertEquals(300L, storageUsedOf(id));
    }

    @Test
    void 扣减超过已用量时兜底为0不出现负数() {
        Long id = newUser("quota_sub_floor", 100L, 1000L);

        int rows = userRepository.subtractStorageUsed(id, 500L);

        assertEquals(1, rows, "CASE WHEN 兜底后行仍应被更新");
        assertEquals(0L, storageUsedOf(id), "storageUsed 不允许出现负数");
    }
}
