package com.backtesting.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Springdoc OpenAPI 3 설정. 노출 경로:
 *   /v3/api-docs   — JSON
 *   /swagger-ui.html — 웹 UI
 *
 * 보안 스키마를 등록해 Swagger UI 에서 "Authorize" 버튼으로 X-API-Key 를 입력할 수 있게 한다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Backtesting & Auto-Trading API")
                        .version("1.0.0")
                        .description("백테스트 · 자동매매 · ELW · 퀀트 전략 API. " +
                                "주문 엔드포인트는 TRADER 이상 권한이 필요하며 X-Idempotency-Key 를 권장한다."))
                .components(new Components()
                        .addSecuritySchemes("ApiKeyAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-API-Key")
                                        .description("환경변수로 주입된 API 키 (ADMIN / TRADER / VIEWER / SCRAPER 역할).")))
                .addSecurityItem(new SecurityRequirement().addList("ApiKeyAuth"));
    }
}
