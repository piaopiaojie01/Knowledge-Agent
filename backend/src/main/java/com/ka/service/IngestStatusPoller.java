package com.ka.service;

import com.ka.client.AgentClient;
import com.ka.entity.Document;
import com.ka.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库状态轮询器：agent 的 ingest 是异步的，上传受理后文档处于 PROCESSING，
 * 这里定时向 agent 查询后台任务结果，把 docStatus 落定到 ACTIVE/FAILED，
 * 避免向量化失败时文档永远停在 ACTIVE（静默失败）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestStatusPoller {

    /** agent 状态持续 unknown（如 agent 重启丢任务表）超过该时长则判 FAILED */
    private static final long UNKNOWN_TIMEOUT_MINUTES = 10;

    private final DocumentRepository documentRepository;
    private final AgentClient agentClient;

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void poll() {
        List<Document> processing = documentRepository.findByDocStatus("PROCESSING");
        for (Document doc : processing) {
            try {
                settle(doc);
            } catch (Exception e) {
                log.warn("入库状态轮询单条失败（跳过）: docId={}, error={}", doc.getId(), e.getMessage());
            }
        }
    }

    private void settle(Document doc) {
        AgentClient.IngestStatusResponse s = agentClient.ingestStatus(doc.getId());
        switch (s.getStatus() == null ? "unknown" : s.getStatus()) {
            case "done" -> {
                doc.setDocStatus("ACTIVE");
                doc.setChunkCount(s.getInserted() != null ? s.getInserted() : 0);
                documentRepository.save(doc);
                log.info("文档入库完成: docId={}, chunkCount={}", doc.getId(), doc.getChunkCount());
            }
            case "failed", "interrupted" -> {
                doc.setDocStatus("FAILED");
                documentRepository.save(doc);
                log.error("文档入库失败: docId={}, message={}", doc.getId(), s.getMessage());
            }
            case "processing" -> { /* 仍在处理，下轮再看 */ }
            default -> {
                // unknown：agent 重启丢状态或查询异常，不立刻判死；
                // updated_at 超过 10 分钟仍 unknown 才标记 FAILED
                if (doc.getUpdatedAt() != null
                        && doc.getUpdatedAt().isBefore(LocalDateTime.now().minusMinutes(UNKNOWN_TIMEOUT_MINUTES))) {
                    doc.setDocStatus("FAILED");
                    documentRepository.save(doc);
                    log.error("文档入库状态超过 {} 分钟仍未知，标记 FAILED: docId={}",
                            UNKNOWN_TIMEOUT_MINUTES, doc.getId());
                }
            }
        }
    }
}
