package com.ka.service;

import com.ka.dto.McpServerDTO;
import com.ka.entity.McpServer;
import com.ka.repository.McpServerRepository;
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

/** MCP 服务器管理：校验、CRUD、启用名单下发 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpServerServiceTest {

    @Mock
    private McpServerRepository repo;

    private McpServerService service;

    @BeforeEach
    void setUp() {
        service = new McpServerService(repo);
    }

    @Test
    void create_名称地址必填() {
        assertThrows(RuntimeException.class,
                () -> service.create(McpServerDTO.builder().name("").url("").build()));
        verify(repo, never()).save(any());
    }

    @Test
    void create_保存成功() {
        when(repo.save(any(McpServer.class))).thenAnswer(inv -> {
            McpServer s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        McpServerDTO dto = service.create(
                McpServerDTO.builder().name("mcp1").url("http://localhost:9000").enabled(true).build());

        assertEquals("mcp1", dto.getName());
        assertEquals("http://localhost:9000", dto.getUrl());
    }

    @Test
    void listEnabledForAgent_只返回启用服务器() {
        when(repo.findByEnabledTrue()).thenReturn(List.of(
                McpServer.builder().id(1L).name("a").url("http://a").enabled(true).build()));

        List<Map<String, String>> list = service.listEnabledForAgent();

        assertEquals(1, list.size());
        assertEquals("http://a", list.get(0).get("url"));
        assertEquals("a", list.get(0).get("name"));
    }
}
