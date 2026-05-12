package com.bipros.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class OpenApiConfig {

    @Value("${springdoc.server-url:}")
    private String serverUrl;

    @Bean
    public OpenAPI biprosOpenAPI() {
        OpenAPI api = new OpenAPI()
                .info(new Info()
                        .title("Bipros EPPM API")
                        .description("Enterprise Project Portfolio Management System")
                        .version("0.1.0")
                        .license(new License().name("Proprietary")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer"))
                .components(new Components()
                        .addSecuritySchemes("Bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT access token")));

        if (StringUtils.hasText(serverUrl)) {
            api.addServersItem(new Server().url(serverUrl).description("API server"));
        }

        return api;
    }
}
