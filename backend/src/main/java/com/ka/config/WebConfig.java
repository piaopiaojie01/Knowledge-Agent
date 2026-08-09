package com.ka.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * 静态资源配置 —— 图表/图标从文件系统读取（而非 Jar 内）
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 图表/图标目录（容器部署用 KA_CHART_DIR / KA_ICON_DIR 指向挂载卷） */
    @Value("${ka.chart-dir:charts}")
    private String chartDir;

    @Value("${ka.icon-dir:icons}")
    private String iconDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // charts 目录（agent 运行时生成）
        String chartsPath = new File(chartDir).getAbsolutePath();
        registry.addResourceHandler("/charts/**")
                .addResourceLocations("file:" + chartsPath + "/");

        // icons 目录
        String iconsPath = new File(iconDir).getAbsolutePath();
        registry.addResourceHandler("/icons/**")
                .addResourceLocations("file:" + iconsPath + "/");
    }
}
