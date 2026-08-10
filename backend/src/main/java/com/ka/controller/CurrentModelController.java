package com.ka.controller;

import com.ka.dto.ApiResponse;
import com.ka.dto.ModelConfigDTO;
import com.ka.service.ModelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 当前生效模型（所有登录用户可读，供前端展示；不包含 API Key） */
@RestController
@RequestMapping("/api/model-config")
@RequiredArgsConstructor
public class CurrentModelController {

    private final ModelConfigService modelConfigService;

    @GetMapping("/current")
    public ApiResponse<Map<String, Object>> current() {
        ModelConfigDTO dto = modelConfigService.getMasked();
        return ApiResponse.success(Map.of(
                "model", dto.getModelName(),
                "enabled", dto.getEnabled() != null ? dto.getEnabled() : true));
    }
}
