package com.fastclaim.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenApiConfiguration.class);

    /**
     * 声明 HTTP Basic 认证方案，使 Swagger UI 显示 Authorize 按钮。
     * SpringDoc 会自动扫描 @PreAuthorize 注解，将需要认证的端点标记🔒。
     */
    @Bean
    public OpenAPI openAPI() {
        log.info("初始化 OpenAPI 文档 — HTTP Basic 认证已配置");
        return new OpenAPI()
                .info(new Info()
                        .title("fast-claim-agent-api")
                        .description("智能保险 AI 客服平台 API")
                        .version("0.1.0"))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"))
                .components(new Components()
                        .addSecuritySchemes("basicAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("basic")
                                        .name("basicAuth")));
    }
}
