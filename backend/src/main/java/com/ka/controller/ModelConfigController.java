package com.ka.controller;

import com.ka.dto.ApiResponse;
import com.ka.dto.ModelConfigDTO;
import com.ka.service.ModelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 大模型配置管理（仅 ADMIN，由 SecurityConfig 的 /api/admin/** 规则保护） */
@RestController
@RequestMapping("/api/admin/model-config")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    @GetMapping
    public ApiResponse<ModelConfigDTO> get() {
        return ApiResponse.success(modelConfigService.getMasked());
    }

    @PutMapping
    public ApiResponse<ModelConfigDTO> update(@RequestBody ModelConfigDTO dto) {
        return ApiResponse.success(modelConfigService.update(dto));
    }
}
