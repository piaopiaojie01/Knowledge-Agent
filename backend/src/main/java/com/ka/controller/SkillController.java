package com.ka.controller;

import com.ka.dto.ApiResponse;
import com.ka.dto.SkillDTO;
import com.ka.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 技能管理（仅 ADMIN） */
@RestController
@RequestMapping("/api/admin/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public ApiResponse<List<SkillDTO>> list() {
        return ApiResponse.success(skillService.list());
    }

    @PutMapping("/{name}")
    public ApiResponse<SkillDTO> update(@PathVariable String name, @RequestBody SkillDTO dto) {
        return ApiResponse.success(skillService.update(name, dto));
    }
}
