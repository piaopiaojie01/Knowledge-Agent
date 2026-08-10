package com.ka.service;

import com.ka.dto.SkillDTO;
import com.ka.entity.Skill;
import com.ka.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 技能管理：内置清单种子、启用状态切换、启用名单下发 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillServiceTest {

    @Mock
    private SkillRepository repo;

    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(repo);
    }

    @Test
    void ensureSeeded_首次访问写入内置技能清单() {
        List<Skill> seeded = new ArrayList<>();
        when(repo.findAll()).thenReturn(List.of(), seeded);
        when(repo.save(any(Skill.class))).thenAnswer(inv -> {
            Skill s = inv.getArgument(0);
            seeded.add(s);
            return s;
        });

        service.ensureSeeded();

        assertEquals(12, seeded.size());
        assertTrue(seeded.stream().anyMatch(s -> "make_chart".equals(s.getName())));
    }

    @Test
    void update_切换启用状态() {
        Skill s = Skill.builder().id(1L).name("calculate")
                .description("x").enabled(true).builtin(true).build();
        when(repo.findByName("calculate")).thenReturn(Optional.of(s));

        service.update("calculate", SkillDTO.builder().enabled(false).build());

        assertFalse(s.getEnabled());
    }

    @Test
    void listEnabledNames_只返回启用项() {
        when(repo.findByEnabledTrue()).thenReturn(List.of(
                Skill.builder().name("a").enabled(true).build(),
                Skill.builder().name("b").enabled(true).build()));

        assertEquals(List.of("a", "b"), service.listEnabledNames());
    }

    @Test
    void list_返回DTO列表() {
        when(repo.findAll()).thenReturn(List.of(
                Skill.builder().id(1L).name("calculate").description("d")
                        .enabled(true).builtin(true).build()));

        List<SkillDTO> dtos = service.list();

        assertEquals(1, dtos.size());
        assertEquals("calculate", dtos.get(0).getName());
    }
}
