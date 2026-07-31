package com.ka.service;

import com.ka.entity.Conversation;
import com.ka.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 会话持久化服务——消息存 MySQL，刷新不丢 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository repo; // 会话记录仓库

    /** 保存一条对话消息到 MySQL */
    @Transactional
    public Conversation save(String sessionId, Long userId, String role, String content, int inputTokens, int outputTokens) {
        Conversation conv = Conversation.builder()
                .sessionId(sessionId)
                .userId(userId)
                .role(role)
                .content(content)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .build();
        return repo.save(conv);
    }

    /** 加载整个会话的全部消息（时间升序），仅限会话所有者 */
    public List<Conversation> loadSession(String sessionId, Long userId) {
        return repo.findBySessionIdAndUserIdOrderByCreatedAtAsc(sessionId, userId);
    }

    /** 清空指定会话的所有消息，仅限会话所有者 */
    @Transactional
    public void clearSession(String sessionId, Long userId) {
        repo.deleteBySessionIdAndUserId(sessionId, userId);
    }

    /** 列出用户的所有会话 ID（去重，按最近活动排序） */
    public List<Map<String, String>> listSessions(Long userId) {
        // 收集每个 session 的最新消息（用于提取标题）
        Map<String, String> sessionTitles = new java.util.LinkedHashMap<>();
        List<Conversation> all = repo.findTop50ByUserIdOrderByCreatedAtDesc(userId);
        for (Conversation c : all) {
            String sid = c.getSessionId();
            if (!sessionTitles.containsKey(sid)) {
                sessionTitles.put(sid, c.getTitle() != null ? c.getTitle() : sid.substring(0, Math.min(16, sid.length())));
            }
        }
        List<Map<String, String>> result = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : sessionTitles.entrySet()) {
            Map<String, String> m = new java.util.HashMap<>();
            m.put("sessionId", e.getKey()); m.put("title", e.getValue());
            result.add(m);
        }
        return result;
    }

    /** 重命名会话，仅限会话所有者 */
    @Transactional
    public void renameSession(String sessionId, Long userId, String newTitle) {
        List<Conversation> msgs = repo.findBySessionIdAndUserIdOrderByCreatedAtAsc(sessionId, userId);
        for (Conversation m : msgs) {
            m.setTitle(newTitle);
        }
        repo.saveAll(msgs);
    }

    /** 汇总 token 统计 */
    /** 汇总 token 统计：当前会话 / 30天 / 全部累计（仅当前用户） */
    public Map<String, Object> getTokenStats(Long userId) {
        Map<String, Object> result = new java.util.HashMap<>();
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<Conversation> recent = repo.findByUserIdAndCreatedAtAfter(userId, since);
        int totalInput30d = recent.stream().mapToInt(Conversation::getInputTokens).sum();
        int totalOutput30d = recent.stream().mapToInt(Conversation::getOutputTokens).sum();
        result.put("total30d", totalInput30d + totalOutput30d);

        String sid = repo.findTopByUserIdOrderByCreatedAtDesc(userId)
                .map(Conversation::getSessionId).orElse("");
        List<Conversation> sessionMsgs = sid.isEmpty() ? List.of()
                : repo.findBySessionIdAndUserIdOrderByCreatedAtAsc(sid, userId);
        int sessionInput = sessionMsgs.stream().mapToInt(Conversation::getInputTokens).sum();
        int sessionOutput = sessionMsgs.stream().mapToInt(Conversation::getOutputTokens).sum();
        result.put("session", sessionInput + sessionOutput);

        result.put("totalAll", repo.sumTokensByUserId(userId));
        return result;
    }

    /** 清空指定用户的全部会话 */
    @Transactional
    public void clearUserSessions(Long userId) {
        repo.deleteByUserId(userId);
    }
}
