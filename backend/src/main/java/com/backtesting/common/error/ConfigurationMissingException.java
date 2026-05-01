package com.backtesting.common.error;

/**
 * 외부 의존성(API 키 / 자격 증명 / URL 등) 이 설정되지 않아 기능을 수행할 수 없는 상태.
 *
 * 의미상 "서버 버그" 가 아니라 "운영자가 채워야 할 환경변수 부재" — HTTP 500 (INTERNAL_ERROR) 가 아니라
 * 503 (SERVICE_UNAVAILABLE) 로 매핑하고 메시지를 운영자에게 노출한다.
 *
 * 사용 예: KIS API 키 미설정 시 잔고 조회 불가, DART 키 미설정 시 SPAC 유니버스 빌드 불가 등.
 *
 * @see GlobalExceptionHandler#handleConfigurationMissing
 * @see ErrorCode#DEPENDENCY_NOT_CONFIGURED
 */
public class ConfigurationMissingException extends RuntimeException {

    /** 사람이 읽기 좋은 의존성 이름 (예: "KIS API", "DART OpenAPI"). */
    private final String dependency;

    public ConfigurationMissingException(String dependency, String message) {
        super(message);
        this.dependency = dependency;
    }

    public String getDependency() { return dependency; }
}
