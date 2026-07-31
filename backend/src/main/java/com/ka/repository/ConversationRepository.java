package com.ka.repository;

import com.ka.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

/** 会话记录仓库 */
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /** 按会话 ID 查询全部消息（时间升序） */
    List<Conversation> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /** 按会话 ID + 用户 ID 查询（防止越权访问他人会话） */
    List<Conversation> findBySessionIdAndUserIdOrderByCreatedAtAsc(String sessionId, Long userId);

    /** 按用户 ID 查询最近 N 条会话 */
    List<Conversation> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    /** 按用户 ID 查询最近 50 条（用于列出会话） */
    List<Conversation> findByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);

    java.util.Optional<Conversation> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    List<Conversation> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);

    /** 汇总指定用户的 token 总量（避免全表加载与跨用户泄露） */
    @Query("SELECT COALESCE(SUM(c.inputTokens + c.outputTokens), 0) FROM Conversation c WHERE c.userId = :userId")
    Long sumTokensByUserId(Long userId);

    /** 删除指定会话的全部消息 */
    @Query("SELECT c.userId, u.username, COUNT(c) FROM Conversation c JOIN User u ON c.userId = u.id GROUP BY c.userId, u.username ORDER BY COUNT(c) DESC")
    List<Object[]> countGroupByUser();

    void deleteBySessionId(String sessionId);

    /** 删除指定用户的指定会话（越权删除防护） */
    void deleteBySessionIdAndUserId(String sessionId, Long userId);

    /** 删除指定用户的全部会话 */
    void deleteByUserId(Long userId);
}
