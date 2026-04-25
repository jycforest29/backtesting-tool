package com.backtesting.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 클라이언트에 반환되는 표준 에러 응답.
 *
 * 계약 (변경 시 호환성 고려):
 *   code      — 기계판독용 에러 코드 (stable identifier)
 *   message   — 사람이 읽을 메시지 (i18n 여지 있음, 변경 가능)
 *   traceId   — 서버 로그 / 분산 트레이싱 상관 키
 *   timestamp — 서버 기준 에러 발생 시각 (ISO-8601 UTC)
 *   path      — 에러가 발생한 요청 경로
 *   details   — (선택) 필드 단위 상세 (검증 실패 등)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        String traceId,
        Instant timestamp,
        String path,
        List<FieldError> details) {

    public static ErrorResponse of(ErrorCode code, String message, String traceId, String path) {
        return new ErrorResponse(
                code.name(),
                message != null ? message : code.defaultMessage(),
                traceId,
                Instant.now(),
                path,
                null);
    }

    public static ErrorResponse of(ErrorCode code, String message, String traceId, String path,
                                   List<FieldError> details) {
        return new ErrorResponse(
                code.name(),
                message != null ? message : code.defaultMessage(),
                traceId,
                Instant.now(),
                path,
                details == null || details.isEmpty() ? null : details);
    }

    /** 검증 실패 등 필드 단위 에러. */
    public record FieldError(String field, String message, Object rejectedValue) {
        public static FieldError of(String field, String message) {
            return new FieldError(field, message, null);
        }
    }
}
