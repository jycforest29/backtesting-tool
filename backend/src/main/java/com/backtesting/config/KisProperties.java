package com.backtesting.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "kis")
public class KisProperties {
    private String appKey;
    private String appSecret;

    /** 한국투자증권 계좌번호 8자리. 미설정 시 주문 API 호출 불가 (헬스체크에서 OUT_OF_SERVICE). */
    private String accountNumber;

    @Pattern(regexp = "^\\d{2}$", message = "accountProduct 는 2자리 숫자")
    private String accountProduct = "01";

    private boolean paperTrading = true;

    @NotBlank private String baseUrlReal;
    @NotBlank private String baseUrlPaper;
    @NotBlank private String wsUrlReal;
    @NotBlank private String wsUrlPaper;

    /** 일반 API 초당 호출 한도. 신규 가입 후 3일간은 3, 이후 실전은 18, 모의는 1. */
    @Min(1) @Max(100)
    private int requestsPerSecond = 3;

    /** 토큰 발급 초당 한도 (KIS 고정 1). */
    @Min(1) @Max(10)
    private int tokenRequestsPerSecond = 1;

    /** 한도 초과 시 대기 최대 ms. 이 이상 대기해야 하면 클라이언트에 429 반환. */
    @Min(0) @Max(60_000)
    private long maxWaitMs = 2000;

    /** 발급받은 access_token을 저장할 파일 경로. 서버 재시작 시 토큰 재사용. */
    @NotBlank
    private String tokenCachePath = System.getProperty("user.home") + "/.kis-token.json";

    public String getBaseUrl() {
        return paperTrading ? baseUrlPaper : baseUrlReal;
    }

    public String getWsUrl() {
        return paperTrading ? wsUrlPaper : wsUrlReal;
    }

    public boolean isConfigured() {
        return appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }
}
