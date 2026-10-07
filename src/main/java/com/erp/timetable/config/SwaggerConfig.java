package com.erp.timetable.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3 / Swagger UI configuration.
 * Accessible at /swagger-ui.html (no auth required).
 */
@Configuration
public class SwaggerConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(apiInfo())
            .servers(List.of(
                new Server().url("/api/v1").description("API v1"),
                new Server().url("http://localhost:8080/api/v1").description("Local Dev")
            ))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
            .components(new Components()
                .addSecuritySchemes(BEARER_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Enter JWT token obtained from /auth/login")
                )
            );
    }

    private Info apiInfo() {
        return new Info()
            .title("AI-Powered College Timetable Scheduler ERP")
            .description("""
                Production-ready REST API for managing departments, faculty,
                subjects, classrooms, and automatically generating
                conflict-free academic timetables.
                """)
            .version("1.0.0")
            .contact(new Contact()
                .name("ERP Admin")
                .email("admin@college.edu")
            )
            .license(new License()
                .name("Proprietary")
            );
    }
}
