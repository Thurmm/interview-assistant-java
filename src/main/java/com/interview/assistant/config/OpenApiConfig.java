package com.interview.assistant.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI 3 配置
 *
 * 访问地址：
 * - Swagger UI:  http://localhost:5000/swagger-ui.html
 * - OpenAPI JSON: http://localhost:5000/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:5000}")
    private int serverPort;

    @Bean
    public OpenAPI interviewAssistantOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("面试助手 API")
                        .description("AI 驱动的面试模拟助手 REST API\n\n" +
                                "功能模块：\n" +
                                "- 会话管理：创建/删除/控制面试会话\n" +
                                "- AI 问答：实时语音识别 + LLM 回答\n" +
                                "- 简历解析：PDF/DOCX 上传与向量化\n" +
                                "- 参考知识库：RAG 问答检索\n" +
                                "- 面试报告：生成评估报告与分数")
                        .version("2.0.0")
                        .contact(new Contact()
                                .name("Thurmm")
                                .url("https://github.com/Thurmm"))
                        .license(new License()
                                .name("MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("本地开发服务器")
                ));
    }
}
