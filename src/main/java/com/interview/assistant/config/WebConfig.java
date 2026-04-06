package com.interview.assistant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;
import java.nio.file.Paths;

/**
 * Web 静态资源配置
 *
 * 使用外部 static 目录而非 classpath，
 * 解决 Spring Boot fat JAR 内置资源在某些环境下的路径问题。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 静态资源根目录：与 src/main/resources/static 对应
     * 在 IDE 环境指向 src/main/resources/static/
     * 在 JAR 运行时指向 {jar所在目录}/src/main/resources/static/
     */
    private String getStaticPath() {
        String userDir = System.getProperty("user.dir", "");
        String resourcesPath = Paths.get(userDir, "src", "main", "resources", "static").toString();
        return "file:" + resourcesPath + File.separator;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String staticPath = getStaticPath();

        registry.addResourceHandler("/static/**")
                .addResourceLocations(staticPath)
                .setCachePeriod(0);

        System.out.println("[WebConfig] Static resources from: " + staticPath);

        // 验证目录存在
        File dir = new File(staticPath.replace("file:", ""));
        if (!dir.exists()) {
            System.out.println("[WebConfig] WARNING: Static dir not found at " + dir.getAbsolutePath());
        } else {
            System.out.println("[WebConfig] Static dir exists, listing:");
            for (File f : dir.listFiles()) {
                System.out.println("  " + f.getName());
            }
        }
    }
}
