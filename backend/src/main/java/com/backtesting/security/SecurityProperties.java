package com.backtesting.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 인증 / CORS 설정.
 *
 * API 키는 {@code security.api-keys} 아래 프로필별 혹은 env 주입으로 관리한다.
 * 빈 키 (env 미설정) 는 자동으로 제외된다 — 미설정 + "dev" 프로파일이면 부팅 시 WARN 하고
 * open 모드 (anonymous VIEWER) 로 폴백. 그 외 프로파일은 boot-fail.
 *
 * @see SecurityConfig#apiKeyRegistry
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "security")
public class SecurityProperties {

    /** 등록된 API 키 목록. */
    private List<ApiKey> apiKeys = new ArrayList<>();

    /** CORS 설정. */
    @NotNull
    private Cors cors = new Cors();

    /**
     * true: 키가 하나도 설정되지 않은 상태로 운영 프로파일에 부팅하면 오히려 실패시킨다.
     * false (기본): 경고 후 open 모드. 로컬 개발 편의성.
     * 프로덕션에서는 반드시 {@code application-prod.yml} 등에서 true 로 오버라이드.
     */
    private boolean failOnMissingKeys = false;

    @Data
    public static class ApiKey {
        /** 헤더 X-API-Key 로 전송될 값. 평문 비교 (로컬 SECRET 관리 전제). */
        @NotBlank
        private String key;
        /** 감사 로그 등에 기록될 주체 이름. */
        @NotBlank
        private String principal;
        /** 부여할 권한. 예: VIEWER / TRADER / ADMIN / SCRAPER */
        @NotNull
        private Set<Role> roles;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of(
                "http://localhost:5173", "http://localhost:3000");
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
        private List<String> allowedHeaders = List.of(
                "Content-Type", "Authorization", "X-API-Key",
                "X-Idempotency-Key", "X-Trace-Id", "X-Deadline-Ms", "X-Priority");
        private List<String> exposedHeaders = List.of(
                "X-Trace-Id", "X-Idempotent-Replay", "Retry-After");
        private long maxAgeSeconds = 3600;
    }

    public enum Role {
        /** 읽기 전용 — 백테스트, 조회 */
        VIEWER,
        /** VIEWER + 거래/감시/OCO */
        TRADER,
        /** TRADER + 감사로그 열람 + 스케줄 수동 트리거 + h2-console */
        ADMIN,
        /** Prometheus 스크래핑 전용 */
        SCRAPER;

        public String authority() { return "ROLE_" + name(); }
    }
}
