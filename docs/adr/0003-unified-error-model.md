# ADR 0003 — 통합 ErrorResponse 모델 + GlobalExceptionHandler

- Status: **Accepted** (2026-04-24)

## Context

기존엔 컨트롤러마다 자체 `try/catch` 로 `Map.of("error", msg)` 반환, 일부는 `@RestControllerAdvice(KisExceptionHandler)` 에서 별도 포맷. 결과:

- 클라이언트가 에러 타입을 기계 판독 못함 (code 필드 일관성 없음).
- trace 상관관계 없음 — 서버 로그와 클라이언트 에러를 연결하려면 timestamp grep.
- 필드 단위 validation 에러를 구조화해서 돌려주지 못함.
- 필터 계층(`DeadlineFilter`, `AdmissionFilter`) 이 자체 JSON 을 쓰서 스키마 불일치.

## Decision

- `ErrorCode` enum — stable string code + HTTP status + 기본 메시지. 클라이언트 계약.
- `ErrorResponse(code, message, traceId, timestamp, path, details[])` — 모든 에러 응답의 공통 스키마.
- `GlobalExceptionHandler` (`@RestControllerAdvice`, `HIGHEST_PRECEDENCE`) — 컨트롤러에서 던져진 모든 예외를 매핑. **컨트롤러 내 try/catch 금지**.
- `ErrorResponseWriter` — 서블릿 필터에서도 같은 스키마를 쓰도록 유틸.
- 4xx는 WARN / 5xx는 ERROR 로 로깅. 5xx 바디는 내부 메시지 누수 방지를 위해 마스킹.

## Consequences

- 클라이언트가 `response.code` 로 분기 가능 (`VALIDATION_FAILED`, `LOSS_GUARD_BLOCKED`, …).
- traceId 필드로 서버 로그 즉시 추적 (MDC 와 같은 값).
- 에러 핸들링이 한 곳에 집중 → 컨트롤러 코드가 얇아짐.
- 에러 매핑 행렬을 테스트 하나(`GlobalExceptionHandlerTest`)로 보호.

## Alternatives considered

1. **RFC 7807 Problem Details**. 표준 호환성이 매력적이나 Spring Boot 3 이전 한국어 메시지 UX 가 좋지 않고, 필드별 상세 표현 스키마를 별도로 정의해야 함. 차후 재검토 여지.
2. **에러 코드 없이 HTTP status 만**. 클라이언트가 200 vs 400 만 구분 가능. 한도 초과(423)와 인증 실패(401)를 세분화해야 하는 트레이딩 도메인에 부적합.
