package com.backtesting.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 보안 구성.
 *
 * 모델:
 *  - API 키 (X-API-Key 헤더) 기반 인증. 세션 없음. CSRF off (stateless API).
 *  - 역할: VIEWER / TRADER / ADMIN / SCRAPER. 경로별 최소권한.
 *  - 인증 실패 / 권한 부족 → 표준 ErrorResponse JSON (EntryPoint, AccessDeniedHandler).
 *
 * 부팅 안전장치:
 *  - 키가 하나도 설정 안된 상태 + {@code security.fail-on-missing-keys=true} → boot fail.
 *  - 그렇지 않으면 WARN 후 open-dev 모드 (anonymous 접근 허용) 로 폴백.
 *    운영 배포 시에는 반드시 flag 을 true 로.
 */
@Slf4j
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final SecurityProperties props;
    private final HttpRateLimitProperties rateLimitProps;
    private final JsonAuthenticationEntryPoint authEntryPoint;
    private final JsonAccessDeniedHandler accessDeniedHandler;

    @Bean
    public ApiKeyRegistry apiKeyRegistry() {
        ApiKeyRegistry reg = new ApiKeyRegistry(props.getApiKeys());
        if (reg.size() == 0) {
            if (props.isFailOnMissingKeys()) {
                throw new IllegalStateException(
                        "security.api-keys is empty but security.fail-on-missing-keys=true — "
                                + "refusing to start. Configure X-API-Key via env vars.");
            }
            log.warn("===========================================================");
            log.warn("  SECURITY OPEN MODE — no API keys configured.");
            log.warn("  /api/** endpoints are accessible WITHOUT authentication.");
            log.warn("  Set security.api-keys[*].key via env vars for production,");
            log.warn("  and set security.fail-on-missing-keys=true.");
            log.warn("===========================================================");
        } else {
            log.info("Security strict mode: {} API key(s) loaded", reg.size());
        }
        return reg;
    }

    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ApiKeyRegistry registry) {
        return new ApiKeyAuthenticationFilter(registry);
    }

    @Bean
    public HttpRateLimitFilter httpRateLimitFilter() {
        return new HttpRateLimitFilter(rateLimitProps);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    ApiKeyAuthenticationFilter apiKeyFilter,
                                                    HttpRateLimitFilter rateLimitFilter,
                                                    ApiKeyRegistry registry) throws Exception {
        boolean openMode = registry.size() == 0;

        http
            .csrf(AbstractHttpConfigurer::disable)                 // 무상태 API — CSRF 무의미
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .headers(h -> h
                    // H2 콘솔용 — 운영에서는 H2_CONSOLE=false 로 비활성화.
                    .frameOptions(f -> f.sameOrigin())
            )
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            // 레이트리밋은 auth 필터 다음에 — principal 이 결정된 뒤 per-principal 키잉 가능.
            .addFilterAfter(rateLimitFilter, ApiKeyAuthenticationFilter.class)
            .exceptionHandling(e -> e
                    .authenticationEntryPoint(authEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
            .authorizeHttpRequests(auth -> {
                // ---- 무조건 공개: 헬스체크 / 프리플라이트 ----
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                auth.requestMatchers("/actuator/health", "/actuator/health/**",
                                     "/actuator/info").permitAll();

                // ---- OpenAPI 문서 (로컬/스테이징 탐색용) ----
                auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();

                // ---- 관측성 ----
                auth.requestMatchers("/actuator/prometheus").hasAnyRole("SCRAPER", "ADMIN");
                auth.requestMatchers("/actuator/**").hasRole("ADMIN");

                // ---- H2 Console (dev 한정) ----
                auth.requestMatchers("/h2-console/**").hasRole("ADMIN");

                if (openMode) {
                    // 키 미설정 — 전부 열기 (WARN 로그 이미 출력됨).
                    auth.requestMatchers("/api/**").permitAll();
                    auth.anyRequest().denyAll();
                    return;
                }

                // ---- 거래/주문/OCO — TRADER ----
                auth.requestMatchers(HttpMethod.POST, "/api/trading/order").hasRole("TRADER");
                auth.requestMatchers(HttpMethod.POST, "/api/trading/oco").hasRole("TRADER");
                auth.requestMatchers(HttpMethod.DELETE, "/api/trading/oco/**").hasRole("TRADER");
                auth.requestMatchers(HttpMethod.POST, "/api/trading/watchlist").hasRole("TRADER");
                auth.requestMatchers(HttpMethod.DELETE, "/api/trading/watchlist").hasRole("TRADER");
                auth.requestMatchers(HttpMethod.POST, "/api/trading/refresh").hasRole("TRADER");

                // ---- 수동 스케줄 트리거 / 이메일 테스트 — ADMIN ----
                auth.requestMatchers("/api/trading/report/**",
                                     "/api/trading/dart/**",
                                     "/api/trading/alert/**",
                                     "/api/trading/scanner/**").hasRole("ADMIN");

                // ---- 감사 로그 — ADMIN ----
                auth.requestMatchers("/api/audit-log/**").hasRole("ADMIN");

                // ---- 그 외 /api/trading/** (read) 및 /api/** (backtest 등) — authenticated ----
                auth.requestMatchers("/api/**").authenticated();

                auth.anyRequest().denyAll();
            });

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.getCors().getAllowedOrigins());
        c.setAllowedMethods(props.getCors().getAllowedMethods());
        c.setAllowedHeaders(props.getCors().getAllowedHeaders());
        c.setExposedHeaders(props.getCors().getExposedHeaders());
        c.setMaxAge(props.getCors().getMaxAgeSeconds());
        c.setAllowCredentials(false);  // API 키 기반 — 쿠키 의존성 없음.
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        src.registerCorsConfiguration("/actuator/**", c);
        return src;
    }

    @PostConstruct
    void validate() {
        if (props.getCors().getAllowedOrigins().contains("*")
                || props.getCors().getAllowedOrigins().stream().anyMatch(o -> o.equals("*"))) {
            // credentials=false 이므로 치명 결함은 아니지만, 운영자 주의 환기.
            log.warn("CORS allowedOrigins=* detected — tighten for production deployments.");
        }
    }
}
