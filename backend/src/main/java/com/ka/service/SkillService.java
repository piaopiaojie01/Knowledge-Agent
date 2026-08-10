package com.ka.service;

import com.ka.dto.SkillDTO;
import com.ka.entity.Skill;
import com.ka.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Agent 技能管理：首次访问时写入内置技能清单，支持启用/停用与扩展配置 */
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillRepository skillRepository;

    /** 内置技能清单（与 agent/core/skills.py 的 TOOLS 保持一致） */
    private static final Map<String, String> BUILTIN_SKILLS = new LinkedHashMap<>();
    static {
        BUILTIN_SKILLS.put("get_current_time", "获取当前日期和时间");
        BUILTIN_SKILLS.put("calculate", "执行数学计算");
        BUILTIN_SKILLS.put("web_search", "搜索互联网");
        BUILTIN_SKILLS.put("url_fetch", "抓取网页内容（受域名白名单限制）");
        BUILTIN_SKILLS.put("get_weather", "查询天气");
        BUILTIN_SKILLS.put("get_exchange_rate", "查询汇率");
        BUILTIN_SKILLS.put("wikipedia_lookup", "查询百科条目");
        BUILTIN_SKILLS.put("date_calc", "日期计算");
        BUILTIN_SKILLS.put("generate_password", "生成随机安全密码");
        BUILTIN_SKILLS.put("make_icon", "生成 SVG 图标");
        BUILTIN_SKILLS.put("make_chart", "生成图表（柱状/折线/饼图）");
        BUILTIN_SKILLS.put("news_headlines", "获取新闻头条");
        BUILTIN_SKILLS.put("barcode_lookup", "查询商品条码信息（Open Food Facts）");
        BUILTIN_SKILLS.put("exchange_convert", "汇率换算（跨境/电商常用）");
        BUILTIN_SKILLS.put("github_search", "搜索 GitHub 仓库（按星标）");
        BUILTIN_SKILLS.put("arxiv_search", "检索 arXiv 学术论文");
        BUILTIN_SKILLS.put("hn_search", "检索 Hacker News 讨论");
        BUILTIN_SKILLS.put("pypi_info", "查询 PyPI 包信息");
        BUILTIN_SKILLS.put("stock_quote", "查询单只股票/指数行情");
        BUILTIN_SKILLS.put("stock_digest", "多股票行情汇总报表");
        BUILTIN_SKILLS.put("make_table", "数据转 Markdown 表格 / CSV");
        BUILTIN_SKILLS.put("web_extract", "网页正文提取（Trafilatura + r.jina.ai 兜底）");
        BUILTIN_SKILLS.put("docx_extract", "提取 Word(.docx) 文本与表格");
        BUILTIN_SKILLS.put("xlsx_extract", "读取 Excel(.xlsx)：表格/数值汇总");
        BUILTIN_SKILLS.put("pptx_extract", "提取 PowerPoint(.pptx) 文本");
        BUILTIN_SKILLS.put("pdf_extract", "提取 PDF 文本与表格");
        BUILTIN_SKILLS.put("csv_tools", "CSV 数据处理（预览/汇总/去重）");
        BUILTIN_SKILLS.put("text_stats", "文本统计（字数/句子/行数）");
        BUILTIN_SKILLS.put("ip_lookup", "IP 归属地查询");
        BUILTIN_SKILLS.put("mermaid_chart", "生成 Mermaid 图代码");
        BUILTIN_SKILLS.put("qr_generate", "生成二维码（SVG）");
        BUILTIN_SKILLS.put("today_hot", "全网热榜（微博/知乎/B站等）");
    }

    @Transactional
    public List<Skill> ensureSeeded() {
        // 幂等补种：只新增缺失的内置技能，不覆盖已有配置（启用状态等）
        BUILTIN_SKILLS.forEach((name, desc) ->
                skillRepository.findByName(name).ifPresentOrElse(
                        s -> { },
                        () -> skillRepository.save(Skill.builder()
                                .name(name).description(desc).enabled(true).builtin(true).build())));
        return skillRepository.findAll();
    }

    public List<SkillDTO> list() {
        return ensureSeeded().stream().map(this::toDto).collect(Collectors.toList());
    }

    /** 随 RAG 请求下发给 Agent 的已启用技能名 */
    public List<String> listEnabledNames() {
        ensureSeeded();
        return skillRepository.findByEnabledTrue().stream()
                .map(Skill::getName).collect(Collectors.toList());
    }

    @Transactional
    public SkillDTO update(String name, SkillDTO req) {
        Skill skill = skillRepository.findByName(name)
                .orElseThrow(() -> new RuntimeException("技能不存在: " + name));
        if (req.getEnabled() != null) {
            skill.setEnabled(req.getEnabled());
        }
        if (req.getConfigJson() != null && !req.getConfigJson().isBlank()) {
            skill.setConfigJson(req.getConfigJson());
        }
        if (req.getDescription() != null && !req.getDescription().isBlank()) {
            skill.setDescription(req.getDescription());
        }
        skillRepository.save(skill);
        return toDto(skill);
    }

    private SkillDTO toDto(Skill s) {
        return SkillDTO.builder()
                .id(s.getId()).name(s.getName()).description(s.getDescription())
                .enabled(s.getEnabled()).builtin(s.getBuiltin())
                .configJson(s.getConfigJson()).updatedAt(s.getUpdatedAt())
                .build();
    }
}
