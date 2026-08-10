package com.ka.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 大模型成本估算 */
class LlmCostServiceTest {

    private final LlmCostService service = new LlmCostService();

    @Test
    void deepseek_百万token输入输出各10元() {
        assertEquals(10.0, service.estimate("deepseek-v4-flash", 1_000_000, 1_000_000));
    }

    @Test
    void 未知模型使用默认档() {
        assertEquals(10.0, service.estimate("unknown-model", 1_000_000, 1_000_000));
    }

    @Test
    void 小请求成本为小数() {
        assertEquals(0.0082, service.estimate("deepseek-v4-flash", 100, 1000));
    }
}
