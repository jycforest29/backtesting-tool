package com.backtesting.security;

import com.backtesting.common.AdmissionFilter;
import com.backtesting.common.error.GlobalExceptionHandler;
import com.backtesting.config.AuditFilterConfig;
import com.backtesting.config.AuditInterceptor;
import com.backtesting.config.WebConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SecurityFilterChain end-to-end 슬라이스 테스트.
 *
 * 풀 SpringBootTest 가 아닌 @WebMvcTest 슬라이스로 가동 — 보안 검증과 무관한 service-layer 빈
 * (EmailService, KisHttpCaller, OpenTelemetry 등) 의 부팅 비용·취약성을 회피.
 * 진짜 컨트롤러 대신 {@link RouteStubController} 를 두어 SecurityConfig 가 정의한 경로 패턴을
 * 그대로 시뮬레이션한다 — 보안 게이트가 컨트롤러 도달 전에 어떤 status 를 반환하는지만 검증.
 *
 * 시나리오:
 *   - permitAll: actuator/health, /v3/api-docs, OPTIONS preflight
 *   - 보호 경로 + (키 없음 / 잘못된 키) → 401
 *   - 보호 경로 + 권한 부족 키 → 403
 *   - 보호 경로 + 알맞은 권한 키 → 200 (스텁 컨트롤러까지 도달)
 *
 * @see ApiKeyAuthenticationFilterTest 단위 테스트 — 필터 단독 동작
 */
@WebMvcTest(controllers = SecurityE2ETest.RouteStubController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {AdmissionFilter.class, AuditInterceptor.class, AuditFilterConfig.class, WebConfig.class}))
@Import({
        SecurityConfig.class,
        // ApiKeyAuthenticationFilter / HttpRateLimitFilter 는 SecurityConfig 의 @Bean — 중복 등록 금지.
        // JsonAuthenticationEntryPoint / JsonAccessDeniedHandler 는 @Component 이지만 슬라이스 스캔 영역
        // 밖이라 명시 import.
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        SecurityE2ETest.TestSupport.class,
})
@EnableConfigurationProperties({SecurityProperties.class, HttpRateLimitProperties.class})
@TestPropertySource(properties = {
        // SecurityFilterChain 와인딩에 필요한 4개 role 키 직접 주입
        "security.fail-on-missing-keys=false",
        "security.api-keys[0].key=admin-test-key",
        "security.api-keys[0].principal=admin-test",
        "security.api-keys[0].roles=ADMIN,TRADER,VIEWER",
        "security.api-keys[1].key=trader-test-key",
        "security.api-keys[1].principal=trader-test",
        "security.api-keys[1].roles=TRADER,VIEWER",
        "security.api-keys[2].key=viewer-test-key",
        "security.api-keys[2].principal=viewer-test",
        "security.api-keys[2].roles=VIEWER",
        "security.api-keys[3].key=scraper-test-key",
        "security.api-keys[3].principal=scraper-test",
        "security.api-keys[3].roles=SCRAPER",
        // 레이트리밋이 우연히 막지 않도록 비활성
        "http-rate-limit.enabled=false",
})
class SecurityE2ETest {

    private static final String HDR = ApiKeyAuthenticationFilter.HEADER;

    @Autowired private MockMvc mvc;

    // ApiKeyAuthenticationFilter 가 인증 정보를 SecurityContext 에 attach 하면 통과.
    // 실제 컨트롤러 핸들러 도달 시 200 을 그대로 응답하도록 stub.
    @RestController
    @RequestMapping
    static class RouteStubController {
        @GetMapping("/api/portfolio-backtest")    public String portfolio()  { return "ok"; }
        @PostMapping("/api/trading/order")        public String order()      { return "ok"; }
        @PostMapping("/api/trading/oco")          public String ocoCreate()  { return "ok"; }
        @DeleteMapping("/api/trading/oco/{id}")   public String ocoCancel()  { return "ok"; }
        @GetMapping("/api/audit-log/recent")      public String audit()      { return "ok"; }
        @GetMapping("/actuator/health")           public String health()     { return "{\"status\":\"UP\"}"; }
        @GetMapping("/v3/api-docs")               public String openApi()    { return "{}"; }
        @GetMapping("/actuator/prometheus")       public String prometheus() { return "# metrics"; }
    }

    /**
     * 테스트 슬라이스에서는 ObjectMapper 와 같은 일부 빈을 명시적으로 노출해야 한다.
     * GlobalExceptionHandler 는 ObjectMapper 의존성이 있을 수 있어 안전하게 추가.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestSupport {
        @Bean @Primary
        public ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    // ---------- 익명 ----------
    @Nested @DisplayName("익명 접근")
    class Anonymous {

        @Test @DisplayName("permitAll: /actuator/health 는 키 없이 통과")
        void healthIsPublic() throws Exception {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }

        @Test @DisplayName("permitAll: /v3/api-docs 는 키 없이 통과")
        void openApiSpecIsPublic() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        }

        @Test @DisplayName("보호 경로 + 키 없음 → 401")
        void protectedRouteRejectsAnonymous() throws Exception {
            mvc.perform(get("/api/portfolio-backtest"))
                    .andExpect(status().isUnauthorized());
        }

        @Test @DisplayName("CORS preflight (OPTIONS) 는 무조건 허용")
        void corsPreflightIsAllowed() throws Exception {
            mvc.perform(options("/api/portfolio-backtest")
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "X-API-Key,Content-Type"))
                    .andExpect(status().isOk());
        }
    }

    // ---------- 잘못된 키 ----------
    @Nested @DisplayName("잘못된 키")
    class BadKey {

        @Test @DisplayName("존재하지 않는 키 → 401")
        void unknownKeyIsUnauthorized() throws Exception {
            mvc.perform(get("/api/portfolio-backtest").header(HDR, "this-key-does-not-exist"))
                    .andExpect(status().isUnauthorized());
        }

        @Test @DisplayName("빈 키 헤더 → 401 (인증 정보 미설정)")
        void blankKeyIsUnauthorized() throws Exception {
            mvc.perform(get("/api/portfolio-backtest").header(HDR, ""))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ---------- 권한 매트릭스 ----------
    @Nested @DisplayName("권한 매트릭스")
    class RoleMatrix {

        @Test @DisplayName("VIEWER 키 → /api/portfolio-backtest (authenticated) 통과")
        void viewerCanReadBacktest() throws Exception {
            mvc.perform(get("/api/portfolio-backtest").header(HDR, "viewer-test-key"))
                    .andExpect(status().isOk());
        }

        @Test @DisplayName("VIEWER 키 → POST /api/trading/order (TRADER 전용) → 403")
        void viewerCannotPlaceOrder() throws Exception {
            mvc.perform(post("/api/trading/order").header(HDR, "viewer-test-key")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test @DisplayName("TRADER 키 → POST /api/trading/order → 200 (인가 통과)")
        void traderCanPlaceOrder() throws Exception {
            mvc.perform(post("/api/trading/order").header(HDR, "trader-test-key")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
        }

        @Test @DisplayName("TRADER 키 → /api/audit-log/recent (ADMIN 전용) → 403")
        void traderCannotReadAuditLog() throws Exception {
            mvc.perform(get("/api/audit-log/recent").header(HDR, "trader-test-key"))
                    .andExpect(status().isForbidden());
        }

        @Test @DisplayName("ADMIN 키 → /api/audit-log/recent → 200")
        void adminCanReadAuditLog() throws Exception {
            mvc.perform(get("/api/audit-log/recent").header(HDR, "admin-test-key"))
                    .andExpect(status().isOk());
        }

        @Test @DisplayName("VIEWER 키 → /actuator/prometheus (SCRAPER/ADMIN 전용) → 403")
        void viewerCannotScrapePrometheus() throws Exception {
            mvc.perform(get("/actuator/prometheus").header(HDR, "viewer-test-key"))
                    .andExpect(status().isForbidden());
        }

        @Test @DisplayName("SCRAPER 키 → /actuator/prometheus → 200")
        void scraperCanScrapePrometheus() throws Exception {
            mvc.perform(get("/actuator/prometheus").header(HDR, "scraper-test-key"))
                    .andExpect(status().isOk());
        }

        @Test @DisplayName("403 응답은 JSON content-type 으로 (AccessDeniedHandler)")
        void forbiddenIsJson() throws Exception {
            String contentType = mvc.perform(post("/api/trading/order").header(HDR, "viewer-test-key")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getContentType();
            assertThat(contentType).contains("json");
        }
    }
}
