package com.backtesting.common.error;

import org.springframework.http.HttpStatus;

/**
 * 애플리케이션 표준 에러 코드.
 *
 * 문자열 코드는 클라이언트 계약이다 — 변경 시 API 호환성 영향이 있으므로 deprecate 후 단계적 제거.
 * 한국어 기본 메시지는 fallback 이며, 호출자가 더 구체적인 메시지를 전달하면 그게 우선.
 */
public enum ErrorCode {

    // 4xx
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 필드가 올바르지 않습니다"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 메서드입니다"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type 입니다"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency-Key 재사용 — 요청 본문이 일치하지 않습니다"),
    IDEMPOTENCY_IN_FLIGHT(HttpStatus.CONFLICT, "동일 Idempotency-Key 요청이 처리 중입니다"),
    LOSS_GUARD_BLOCKED(HttpStatus.LOCKED, "일일 손실 한도를 초과해 매수가 차단되었습니다"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다 — 잠시 후 다시 시도하세요"),
    RATE_LIMITED_UPSTREAM(HttpStatus.TOO_MANY_REQUESTS, "상위 시스템 호출 한도를 초과했습니다"),

    // 5xx
    DEADLINE_EXCEEDED(HttpStatus.GATEWAY_TIMEOUT, "요청 처리 시간이 초과되었습니다"),
    OVERLOADED(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 요청을 처리할 수 없습니다 — 잠시 후 재시도하세요"),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "상위 시스템 호출에 실패했습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() { return status; }
    public String defaultMessage() { return defaultMessage; }
}
