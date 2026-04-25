package com.backtesting.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * HTTP 레이트리밋 구성. per-principal (로그인 된 경우) 또는 per-IP (미인증) 버킷.
 *
 * 한도 초과 시 429 + Retry-After. 표준 ErrorResponse.
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "http-rate-limit")
public class HttpRateLimitProperties {

    private boolean enabled = true;

    /** 기본 엔드포인트 한도. */
    @NotNull @Valid
    private Bucket defaultBucket = new Bucket();

    /** 트레이딩 엔드포인트 (/api/trading/order, /api/trading/oco) 의 보수적 한도. */
    @NotNull @Valid
    private Bucket trading = new Bucket();

    /** HashMap-in-memory 버킷 스토어 최대 엔트리 — 메모리 폭주 방지. */
    @Min(1000)
    private int maxBuckets = 200_000;

    /** 버킷 생성 후 아무 활동 없을 때 유효기간 (분). */
    @Min(1)
    private int idleExpiryMinutes = 30;

    @Data
    public static class Bucket {
        /** 버스트 허용량 (초기 토큰 수). */
        @Min(1)
        private int capacity = 60;

        /** 분당 토큰 보충량. */
        @Min(1)
        private int refillPerMinute = 60;
    }

    /** Spring Boot 의 "default" 프로퍼티 키 충돌 회피: YAML 은 `default:` 로 읽음. */
    public Bucket getDefault() { return defaultBucket; }
    public void setDefault(Bucket b) { this.defaultBucket = b; }
}
