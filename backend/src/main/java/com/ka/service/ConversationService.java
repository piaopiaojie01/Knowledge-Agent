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
        // 会话以首条用户消息命名（前端/历史会话无标题时回退 sessionId）
        boolean isFirst = repo.findBySessionIdAndUserIdOrderByCreatedAtAsc(sessionId, userId).isEmpty();
        String title = null;
        if (isFirst && "user".equals(role) && content != null) {
            String trimmed = content.trim();
            if (!trimmed.isEmpty()) {
                title = trimmed.length() > 30 ? trimmed.substring(0, 30) + "…" : trimmed;
            }
        }
        Conversation conv = Conversation.builder()
                .sessionId(sessionId)
                .userId(userId)
                .role(role)
                .content(content)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .title(title)
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
                // 先用最近消息占位（通常无标题），有标题的消息随后覆盖
                sessionTitles.put(sid, c.getTitle() != null ? c.getTitle() : sid.substring(0, Math.min(16, sid.length())));
            }
            if (c.getTitle() != null && !c.getTitle().isBlank()) {
                sessionTitles.put(sid, c.getTitle());
            }
        }
        // 每个会话的最近活动时间（覆盖全部会话，按时间倒序）
        Map<String, LocalDateTime> sessionLatest = new java.util.HashMap<>();
        List<String> ordered = new java.util.ArrayList<>();
        for (Object[] row : repo.findSessionLatestByUserId(userId)) {
            String sid = String.valueOf(row[0]);
            if (!ordered.contains(sid)) {
                ordered.add(sid);
            }
            if (row[1] instanceof java.sql.Timestamp ts) {
                sessionLatest.put(sid, ts.toLocalDateTime());
            } else if (row[1] instanceof LocalDateTime ldt) {
                sessionLatest.put(sid, ldt);
            }
        }
        // 补上未出现在时间查询里的会话（兜底）
        for (String sid : sessionTitles.keySet()) {
            if (!ordered.contains(sid)) {
                ordered.add(sid);
            }
        }
        List<Map<String, String>> result = new java.util.ArrayList<>();
        for (String sid : ordered) {
            Map<String, String> m = new java.util.HashMap<>();
            m.put("sessionId", sid);
            m.put("title", sessionTitles.getOrDefault(sid, sid));
            LocalDateTime latest = sessionLatest.get(sid);
            if (latest != null) {
                m.put("updatedAt", latest.toString());
            }
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
        result.put("total30dInput", totalInput30d);
        result.put("total30dOutput", totalOutput30d);

        String sid = repo.findTopByUserIdOrderByCreatedAtDesc(userId)
                .map(Conversation::getSessionId).orElse("");
        List<Conversation> sessionMsgs = sid.isEmpty() ? List.of()
                : repo.findBySessionIdAndUserIdOrderByCreatedAtAsc(sid, userId);
        int sessionInput = sessionMsgs.stream().mapToInt(Conversation::getInputTokens).sum();
        int sessionOutput = sessionMsgs.stream().mapToInt(Conversation::getOutputTokens).sum();
        result.put("session", sessionInput + sessionOutput);
        result.put("sessionInput", sessionInput);
        result.put("sessionOutput", sessionOutput);

        Long allTokens = repo.sumTokensByUserId(userId);
        result.put("totalAll", allTokens == null ? 0L : allTokens);
        List<Object[]> split = repo.sumTokensSplitByUserId(userId);
        long allIn = 0L, allOut = 0L;
        if (split != null && !split.isEmpty() && split.get(0) != null) {
            allIn = ((Number) split.get(0)[0]).longValue();
            allOut = ((Number) split.get(0)[1]).longValue();
        }
        result.put("totalAllInput", allIn);
        result.put("totalAllOutput", allOut);
        return result;
    }

    /** 清空指定用户的全部会话 */
    @Transactional
    public void clearUserSessions(Long userId) {
        repo.deleteByUserId(userId);
    }
}
