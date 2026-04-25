package com.backtesting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ELW 스큐 스캐너 설정.
 * KIS ELW 엔드포인트는 환경별로 다를 수 있어 URL/tr_id 전부 config 주입.
 * enabled=false (기본)이면 서비스는 degraded 응답만 반환.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "elw")
public class ElwProperties {
    private boolean enabled = false;
    private String chainUrl;
    private String chainTrId;
    /** 무위험금리 (연, continuous). 기본 3.5% (CD91). */
    private double riskFreeRate = 0.035;
    /** 배당률 (KOSPI200 기초 기본 2%). */
    private double dividendYield = 0.02;
    /** 체인 IV 병렬 계산 워커 수. */
    private int concurrency = 8;
    /** 개별 IV 계산 타임아웃(ms). */
    private long ivTimeoutMs = 5000;
}
