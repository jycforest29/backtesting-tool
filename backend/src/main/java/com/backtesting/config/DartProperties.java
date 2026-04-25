package com.backtesting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "dart")
public class DartProperties {
    /** DART_OPEN_API_KEY 환경변수. 비어있으면 계층 B 전략 사용 불가. */
    private String apiKey;
    private String baseUrl = "https://opendart.fss.or.kr/api";
    /** DART 공식 한도는 관용적이나 안전하게 8rps 기본. */
    private int requestsPerSecond = 8;
    /** 재무제표 in-memory 캐시 유효시간(시간). 기본 24h. */
    private int financialCacheHours = 24;
    /** 기본 보고서 구분: 11011=사업보고서(연), 11012=Q1, 11013=Q2(반기), 11014=Q3. */
    private String defaultReportCode = "11011";

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
