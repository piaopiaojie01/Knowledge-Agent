package com.ka.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * 静态资源配置 —— 图表/图标从文件系统读取（而非 Jar 内）
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // charts 目录（agent 运行时生成）
        String chartsPath = new File("charts").getAbsolutePath();
        registry.addResourceHandler("/charts/**")
                .addResourceLocations("file:" + chartsPath + "/");

        // icons 目录
        String iconsPath = new File("icons").getAbsolutePath();
        registry.addResourceHandler("/icons/**")
                .addResourceLocations("file:" + iconsPath + "/");
    }
}
