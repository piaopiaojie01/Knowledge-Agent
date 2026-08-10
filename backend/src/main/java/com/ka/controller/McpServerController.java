package com.ka.controller;

import com.ka.dto.ApiResponse;
import com.ka.dto.McpServerDTO;
import com.ka.service.McpServerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** MCP 服务器管理（仅 ADMIN） */
@RestController
@RequestMapping("/api/admin/mcp-servers")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService mcpServerService;

    @GetMapping
    public ApiResponse<List<McpServerDTO>> list() {
        return ApiResponse.success(mcpServerService.list());
    }

    @PostMapping
    public ApiResponse<McpServerDTO> create(@RequestBody McpServerDTO dto) {
        return ApiResponse.success(mcpServerService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<McpServerDTO> update(@PathVariable Long id, @RequestBody McpServerDTO dto) {
        return ApiResponse.success(mcpServerService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        mcpServerService.delete(id);
        return ApiResponse.success("已删除", null);
    }
}
