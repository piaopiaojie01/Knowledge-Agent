package com.ka.service;

import com.ka.client.AgentClient;
import com.ka.entity.Document;
import com.ka.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 入库状态轮询器回归测试：done → ACTIVE + chunkCount；
 * failed/interrupted → FAILED；processing → 不动；
 * unknown 超过 10 分钟才判 FAILED。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestStatusPollerTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private AgentClient agentClient;

    private IngestStatusPoller poller;

    @BeforeEach
    void setUp() {
        poller = new IngestStatusPoller(documentRepository, agentClient);
    }

    private Document processingDoc(Long id) {
        return Document.builder().id(id).kbId(10L).docStatus("PROCESSING")
                .chunkCount(0).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    void agent返回done时落定ACTIVE并写入chunkCount() {
        Document doc = processingDoc(1L);
        when(documentRepository.findByDocStatus("PROCESSING")).thenReturn(List.of(doc));
        when(agentClient.ingestStatus(1L))
                .thenReturn(new AgentClient.IngestStatusResponse("done", "", 42));

        poller.poll();

        assertEquals("ACTIVE", doc.getDocStatus());
        assertEquals(42, doc.getChunkCount());
        verify(documentRepository).save(doc);
    }

    @Test
    void agent返回failed时落定FAILED() {
        Document doc = processingDoc(1L);
        when(documentRepository.findByDocStatus("PROCESSING")).thenReturn(List.of(doc));
        when(agentClient.ingestStatus(1L))
                .thenReturn(new AgentClient.IngestStatusResponse("failed", "未能从文档中提取到有效文本内容", 0));

        poller.poll();

        assertEquals("FAILED", doc.getDocStatus());
        verify(documentRepository).save(doc);
    }

    @Test
    void agent返回interrupted时落定FAILED() {
        Document doc = processingDoc(1L);
        when(documentRepository.findByDocStatus("PROCESSING")).thenReturn(List.of(doc));
        when(agentClient.ingestStatus(1L))
                .thenReturn(new AgentClient.IngestStatusResponse("interrupted", "agent 重启", 0));

        poller.poll();

        assertEquals("FAILED", doc.getDocStatus());
        verify(documentRepository).save(doc);
    }

    @Test
    void agent返回processing时状态不变也不落库() {
        Document doc = processingDoc(1L);
        when(documentRepository.findByDocStatus("PROCESSING")).thenReturn(List.of(doc));
        when(agentClient.ingestStatus(1L))
                .thenReturn(new AgentClient.IngestStatusResponse("processing", "向量化中", 0));

        poller.poll();

        assertEquals("PROCESSING", doc.getDocStatus());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void unknown未超10分钟时跳过不判死() {
        Document doc = processingDoc(1L); // updatedAt = now
        when(documentRepository.findByDocStatus("PROCESSING")).thenReturn(List.of(doc));
        when(agentClient.ingestStatus(1L))
                .thenReturn(new AgentClient.IngestStatusResponse("unknown", "", 0));

        poller.poll();

        assertEquals("PROCESSING", doc.getDocStatus());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void unknown超过10分钟时判FAILED() {
        Document doc = processingDoc(1L);
        doc.setUpdatedAt(LocalDateTime.now().minusMinutes(11));
        when(documentRepository.findByDocStatus("PROCESSING")).thenReturn(List.of(doc));
        when(agentClient.ingestStatus(1L))
                .thenReturn(new AgentClient.IngestStatusResponse("unknown", "", 0));

        poller.poll();

        assertEquals("FAILED", doc.getDocStatus());
        verify(documentRepository).save(doc);
    }

    @Test
    void 没有PROCESSING文档时不查agent() {
        when(documentRepository.findByDocStatus("PROCESSING")).thenReturn(List.of());

        poller.poll();

        verify(agentClient, never()).ingestStatus(any());
    }

    @Test
    void 单条文档异常不影响其余文档轮询() {
        Document bad = processingDoc(1L);
        Document good = processingDoc(2L);
        when(documentRepository.findByDocStatus("PROCESSING")).thenReturn(List.of(bad, good));
        when(agentClient.ingestStatus(1L)).thenThrow(new RuntimeException("boom"));
        when(agentClient.ingestStatus(2L))
                .thenReturn(new AgentClient.IngestStatusResponse("done", "", 3));

        poller.poll();

        assertEquals("ACTIVE", good.getDocStatus());
        verify(documentRepository).save(good);
    }
}
