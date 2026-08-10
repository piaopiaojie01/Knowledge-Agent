package com.ka.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 大模型成本估算（CNY / 百万 token）。
 * 价格表按主流公开价维护，可在后续版本配置化；未知模型使用 DeepSeek 默认档。
 */
@Service
public class LlmCostService {

    /** 模型名 → [输入价(¥/1M), 输出价(¥/1M)] */
    private static final Map<String, double[]> PRICING = Map.of(
            "deepseek-v4-flash", new double[]{2.0, 8.0},
            "deepseek-chat", new double[]{2.0, 8.0},
            "gpt-4o", new double[]{17.5, 70.0},
            "gpt-4o-mini", new double[]{1.1, 4.4},
            "qwen-plus", new double[]{0.8, 2.0},
            "glm-4-plus", new double[]{50.0, 50.0}
    );

    private static final double[] FALLBACK = {2.0, 8.0};

    /** 估算一次调用的成本（元），结果保留 6 位小数 */
    public double estimate(String model, long inputTokens, long outputTokens) {
        double[] price = PRICING.getOrDefault(model == null ? "" : model.trim(), FALLBACK);
        double cost = inputTokens / 1_000_000.0 * price[0]
                + outputTokens / 1_000_000.0 * price[1];
        return Math.round(cost * 1_000_000.0) / 1_000_000.0;
    }
}
