package com.ka.service;

import com.ka.entity.Conversation;
import com.ka.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 会话命名：首条用户消息作为标题，后续消息不覆盖，超长截断 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationServiceTest {

    @Mock
    private ConversationRepository repo;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(repo);
    }

    @Test
    void save_首条用户消息写入标题() {
        when(repo.findBySessionIdAndUserIdOrderByCreatedAtAsc("s1", 1L)).thenReturn(List.of());
        when(repo.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        Conversation c = service.save("s1", 1L, "user", "今天天气怎么样", 0, 0);

        assertEquals("今天天气怎么样", c.getTitle());
    }

    @Test
    void save_后续消息不覆盖标题() {
        when(repo.findBySessionIdAndUserIdOrderByCreatedAtAsc("s1", 1L))
                .thenReturn(List.of(Conversation.builder().title("已有标题").build()));
        when(repo.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        Conversation c = service.save("s1", 1L, "user", "第二个问题", 0, 0);

        assertNull(c.getTitle());
    }

    @Test
    void save_助手首条消息不命名() {
        when(repo.findBySessionIdAndUserIdOrderByCreatedAtAsc("s1", 1L)).thenReturn(List.of());
        when(repo.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        Conversation c = service.save("s1", 1L, "assistant", "你好", 0, 0);

        assertNull(c.getTitle());
    }

    @Test
    void save_超长首问截断30字并加省略号() {
        String longQ = "测".repeat(50);
        when(repo.findBySessionIdAndUserIdOrderByCreatedAtAsc("s1", 1L)).thenReturn(List.of());
        when(repo.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        Conversation c = service.save("s1", 1L, "user", longQ, 0, 0);

        assertEquals("测".repeat(30) + "…", c.getTitle());
    }

    @Test
    void listSessions_有非空标题时优先使用而非回退sessionId() {
        Conversation latest = Conversation.builder().sessionId("s1").title(null).build();
        Conversation first = Conversation.builder().sessionId("s1").title("首问标题").build();
        when(repo.findTop50ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(latest, first));

        List<Map<String, String>> list = service.listSessions(1L);

        assertEquals(1, list.size());
        assertEquals("首问标题", list.get(0).get("title"));
    }

    @Test
    void listSessions_包含最近活动时间并按时间倒序() {
        Conversation s1 = Conversation.builder().sessionId("s1").title("A").build();
        Conversation s2 = Conversation.builder().sessionId("s2").title("B").build();
        when(repo.findTop50ByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(s2, s1));
        when(repo.findSessionLatestByUserId(1L)).thenReturn(List.of(
                new Object[]{"s2", java.sql.Timestamp.valueOf("2026-08-10 10:00:00")},
                new Object[]{"s1", java.sql.Timestamp.valueOf("2026-08-09 09:00:00")}));

        List<Map<String, String>> list = service.listSessions(1L);

        assertEquals(2, list.size());
        assertEquals("s2", list.get(0).get("sessionId"));
        assertEquals("2026-08-10T10:00", list.get(0).get("updatedAt"));
        assertEquals("s1", list.get(1).get("sessionId"));
        assertEquals("2026-08-09T09:00", list.get(1).get("updatedAt"));
    }
}
