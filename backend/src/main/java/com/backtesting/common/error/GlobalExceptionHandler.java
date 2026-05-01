package com.backtesting.common.error;

import com.backtesting.common.RequestContext;
import com.backtesting.service.IdempotencyService;
import com.backtesting.service.kis.KisRateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 전역 에러 핸들러 — 모든 컨트롤러에서 발생한 예외를 표준 {@link ErrorResponse} 로 변환.
 *
 * 원칙:
 *   1) 컨트롤러에서 try/catch 금지. 도메인/인프라 예외를 그대로 던진다.
 *   2) 4xx 는 클라이언트 책임 → WARN 로깅 (stack trace 생략).
 *   3) 5xx 는 서버 책임 → ERROR 로깅 (stack trace 포함).
 *   4) 에러 코드(code) 는 클라이언트 계약이다 — 메시지만 바뀌더라도 code 는 보존.
 *   5) Filter 계층 에러는 {@link ErrorResponseWriter} 가 동일한 스키마로 작성.
 *
 * 우선순위(@Order): Spring 기본 ResponseEntityExceptionHandler 보다 먼저 매칭되도록 HIGHEST_PRECEDENCE.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    // ========================= 4xx =========================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest req) {
        List<ErrorResponse.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .collect(Collectors.toList());
        log.warn("Validation failed path={} errors={}", req.getRequestURI(), fields);
        return build(ErrorCode.VALIDATION_FAILED, "요청 필드 검증 실패", req, fields, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(ConstraintViolationException ex,
                                                                HttpServletRequest req) {
        List<ErrorResponse.FieldError> fields = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .collect(Collectors.toList());
        log.warn("Constraint violation path={} errors={}", req.getRequestURI(), fields);
        return build(ErrorCode.VALIDATION_FAILED, "요청 파라미터 검증 실패", req, fields, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex,
                                                              HttpServletRequest req) {
        log.warn("Malformed request body path={} msg={}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.BAD_REQUEST, "요청 본문을 파싱할 수 없습니다", req, null, null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                              HttpServletRequest req) {
        log.warn("Missing param path={} param={}", req.getRequestURI(), ex.getParameterName());
        List<ErrorResponse.FieldError> fields = List.of(
                ErrorResponse.FieldError.of(ex.getParameterName(), "필수 파라미터가 누락되었습니다"));
        return build(ErrorCode.VALIDATION_FAILED, "필수 파라미터 누락", req, fields, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                              HttpServletRequest req) {
        Class<?> type = ex.getRequiredType();
        String expected = type != null ? type.getSimpleName() : "적절한 타입";
        List<ErrorResponse.FieldError> fields = List.of(
                new ErrorResponse.FieldError(ex.getName(), "타입 불일치 — " + expected + " 필요", ex.getValue()));
        return build(ErrorCode.VALIDATION_FAILED, "요청 파라미터 타입 오류", req, fields, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                 HttpServletRequest req) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, ex.getMessage(), req, null, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                          HttpServletRequest req) {
        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ex.getMessage(), req, null, null);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoHandlerFoundException ex, HttpServletRequest req) {
        return build(ErrorCode.NOT_FOUND, "경로를 찾을 수 없습니다: " + ex.getRequestURL(), req, null, null);
    }

    // ========================= 도메인 예외 =========================

    @ExceptionHandler(IdempotencyService.Conflict.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyService.Conflict ex,
                                                                    HttpServletRequest req) {
        log.warn("Idempotency conflict path={} msg={}", req.getRequestURI(), ex.getMessage());
        return build(ErrorCode.IDEMPOTENCY_CONFLICT, ex.getMessage(), req, null, null);
    }

    @ExceptionHandler(IdempotencyService.InFlight.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyInFlight(IdempotencyService.InFlight ex,
                                                                    HttpServletRequest req) {
        log.warn("Idempotency in-flight path={} msg={}", req.getRequestURI(), ex.getMessage());
        return build(ErrorCode.IDEMPOTENCY_IN_FLIGHT, ex.getMessage(), req, null, null);
    }

    @ExceptionHandler(LossGuardBlockedException.class)
    public ResponseEntity<ErrorResponse> handleLossGuard(LossGuardBlockedException ex, HttpServletRequest req) {
        // INFO — 비정상이 아니라 정상 보호 동작.
        log.info("Loss guard blocked path={} netKrw={} limitKrw={}",
                req.getRequestURI(), ex.getNetRealizedKrw(), ex.getLimitKrw());
        return build(ErrorCode.LOSS_GUARD_BLOCKED, ex.getMessage(), req, null, null);
    }

    @ExceptionHandler(ConfigurationMissingException.class)
    public ResponseEntity<ErrorResponse> handleConfigurationMissing(ConfigurationMissingException ex,
                                                                     HttpServletRequest req) {
        // 외부 의존성(API 키 등) 부재. 서버 *버그* 가 아니라 *환경* 문제 — 503 + 메시지 노출.
        // INFO 로 로깅 (스택트레이스 없음): 환경변수 채우면 사라지는 운영자 안내성 사건.
        log.info("Dependency not configured path={} dependency={} msg={}",
                req.getRequestURI(), ex.getDependency(), ex.getMessage());
        return build(ErrorCode.DEPENDENCY_NOT_CONFIGURED, ex.getMessage(), req, null, null);
    }

    @ExceptionHandler(KisRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleKisRateLimit(KisRateLimitedException ex, HttpServletRequest req) {
        long retrySec = Math.max(1, ex.getRetryAfterMs() / 1000);
        log.warn("Upstream rate limit path={} retryMs={}", req.getRequestURI(), ex.getRetryAfterMs());
        return build(ErrorCode.RATE_LIMITED_UPSTREAM,
                "한국투자증권 API 초당 호출 한도 초과 — " + retrySec + "초 후 재시도",
                req, null, retrySec);
    }

    @ExceptionHandler(RequestContext.DeadlineExceededException.class)
    public ResponseEntity<ErrorResponse> handleDeadline(RequestContext.DeadlineExceededException ex,
                                                         HttpServletRequest req) {
        log.warn("Deadline exceeded path={} msg={}", req.getRequestURI(), ex.getMessage());
        return build(ErrorCode.DEADLINE_EXCEEDED, ex.getMessage(), req, null, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("Bad request path={} msg={}", req.getRequestURI(), ex.getMessage());
        return build(ErrorCode.BAD_REQUEST, ex.getMessage(), req, null, null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
        // IllegalStateException 은 보통 "설정 누락" 류 — 5xx 로 본다.
        log.error("Illegal state path={} msg={}", req.getRequestURI(), ex.getMessage(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ex.getMessage(), req, null, null);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex, HttpServletRequest req) {
        ErrorCode code = Arrays.stream(ErrorCode.values())
                .filter(c -> c.status().value() == ex.getStatusCode().value())
                .findFirst()
                .orElse(ex.getStatusCode().is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.BAD_REQUEST);
        if (ex.getStatusCode().is5xxServerError()) {
            log.error("ResponseStatusException path={} status={} msg={}",
                    req.getRequestURI(), ex.getStatusCode(), ex.getReason(), ex);
        } else {
            log.warn("ResponseStatusException path={} status={} msg={}",
                    req.getRequestURI(), ex.getStatusCode(), ex.getReason());
        }
        return build(code, ex.getReason(), req, null, null);
    }

    // ========================= Security (fallback) =========================
    //  - 평상시 Security EntryPoint / AccessDeniedHandler 가 먼저 처리해 여기로 오지 않는다.
    //  - MVC 내부에서 던져진 경우(@PreAuthorize 등) 를 위한 fallback.

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuth(AuthenticationException ex, HttpServletRequest req) {
        log.warn("Authentication failed path={} msg={}", req.getRequestURI(), ex.getMessage());
        return build(ErrorCode.UNAUTHENTICATED, null, req, null, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        log.warn("Access denied path={} msg={}", req.getRequestURI(), ex.getMessage());
        return build(ErrorCode.FORBIDDEN, null, req, null, null);
    }

    // ========================= 5xx fallback =========================

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Throwable ex, HttpServletRequest req) {
        log.error("Unexpected error path={}", req.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR,
                // 운영에서 내부 에러 메시지를 그대로 노출하지 않는다 — 스택트레이스 누수 방지.
                ErrorCode.INTERNAL_ERROR.defaultMessage(),
                req, null, null);
    }

    // ========================= helpers =========================

    private static ResponseEntity<ErrorResponse> build(ErrorCode code, String message,
                                                        HttpServletRequest req,
                                                        List<ErrorResponse.FieldError> details,
                                                        Long retryAfterSec) {
        ErrorResponse body = ErrorResponse.of(code, message, currentTraceId(), req.getRequestURI(), details);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status());
        if (retryAfterSec != null && retryAfterSec > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSec));
        }
        return builder.body(body);
    }

    private static String currentTraceId() {
        RequestContext ctx = RequestContext.current();
        return ctx != null ? ctx.traceId() : null;
    }

    private static ErrorResponse.FieldError toFieldError(FieldError e) {
        return new ErrorResponse.FieldError(e.getField(), e.getDefaultMessage(), e.getRejectedValue());
    }

    private static ErrorResponse.FieldError toFieldError(ConstraintViolation<?> v) {
        String path = v.getPropertyPath() != null ? v.getPropertyPath().toString() : "";
        // PropertyPath 는 "methodName.argName" 형태 — argName 만 쓴다.
        int dot = path.lastIndexOf('.');
        String field = dot >= 0 ? path.substring(dot + 1) : path;
        return new ErrorResponse.FieldError(field, v.getMessage(), v.getInvalidValue());
    }
}
