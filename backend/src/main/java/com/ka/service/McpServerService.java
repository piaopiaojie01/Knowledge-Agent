package com.ka.service;

import com.ka.dto.McpServerDTO;
import com.ka.entity.McpServer;
import com.ka.repository.McpServerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** MCP 服务器管理 */
@Service
@RequiredArgsConstructor
public class McpServerService {

    private final McpServerRepository mcpServerRepository;

    public List<McpServerDTO> list() {
        return mcpServerRepository.findAll().stream().map(this::toDto).collect(Collectors.toList());
    }

    /** 随 RAG 请求下发给 Agent 的已启用 MCP 服务器 */
    public List<Map<String, String>> listEnabledForAgent() {
        return mcpServerRepository.findByEnabledTrue().stream()
                .map(s -> Map.of("name", s.getName(), "url", s.getUrl()))
                .collect(Collectors.toList());
    }

    @Transactional
    public McpServerDTO create(McpServerDTO req) {
        if (req.getName() == null || req.getName().isBlank()
                || req.getUrl() == null || req.getUrl().isBlank()) {
            throw new RuntimeException("名称与地址必填");
        }
        McpServer server = McpServer.builder()
                .name(req.getName().trim())
                .url(req.getUrl().trim())
                .description(req.getDescription())
                .enabled(req.getEnabled() == null ? true : req.getEnabled())
                .build();
        return toDto(mcpServerRepository.save(server));
    }

    @Transactional
    public McpServerDTO update(Long id, McpServerDTO req) {
        McpServer server = mcpServerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("MCP 服务器不存在"));
        if (req.getName() != null && !req.getName().isBlank()) {
            server.setName(req.getName().trim());
        }
        if (req.getUrl() != null && !req.getUrl().isBlank()) {
            server.setUrl(req.getUrl().trim());
        }
        if (req.getDescription() != null) {
            server.setDescription(req.getDescription());
        }
        if (req.getEnabled() != null) {
            server.setEnabled(req.getEnabled());
        }
        return toDto(mcpServerRepository.save(server));
    }

    @Transactional
    public void delete(Long id) {
        mcpServerRepository.deleteById(id);
    }

    private McpServerDTO toDto(McpServer s) {
        return McpServerDTO.builder()
                .id(s.getId()).name(s.getName()).url(s.getUrl())
                .description(s.getDescription()).enabled(s.getEnabled())
                .updatedAt(s.getUpdatedAt())
                .build();
    }
}
