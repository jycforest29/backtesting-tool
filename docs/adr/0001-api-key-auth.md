# ADR 0001 — API 키 기반 인증 (X-API-Key 헤더)

- Status: **Accepted** (2026-04-24)
- Supersedes: —

## Context

Phase 1 이전까지 백엔드 `/api/**` 전체가 **인증 없이 오픈**되어 있었다. `/api/trading/order` 역시 누구나 호출 가능 → 실제 KIS 주문이 발생할 수 있는 구조였다. 즉시 차단이 필요했다.

팀 규모(1인)와 인프라 상태(사설망 배포, 외부 SSO 미구축)를 고려할 때, JWT/OAuth 연동은 오버킬이었다.

## Decision

- `X-API-Key` 헤더 기반 **공유 비밀 키 인증**을 도입한다.
- 역할 모델: `VIEWER / TRADER / ADMIN / SCRAPER`. 경로별 최소권한.
- 키는 환경변수 (`ADMIN_API_KEY`, `TRADER_API_KEY`, …) 로 주입. 설정 시 SHA-256 해시로 저장하고 요청 시 `MessageDigest.isEqual` 로 **타이밍 공격 방어**.
- `security.fail-on-missing-keys=true` (운영 강제) 로 키 없이 부팅 실패하도록 안전장치.
- 인증 실패 / 권한 부족은 통합 `ErrorResponse` (ADR-0003) 로 반환.

## Consequences

### Pros
- 즉시 적용 가능, 인프라 의존성 없음.
- TEST/DEV 에서는 키를 비워 open 모드로 돌릴 수 있음 (부팅 경고 후).
- 비밀 키가 HTTP 헤더로만 이동하므로 쿠키 탈취 벡터 없음 (CSRF 불필요 → stateless).

### Cons / 알려진 제약
- 사용자 단위 권한 분리가 없다 — 키마다 고정된 역할. 사용자별 감사는 principal 단위까지만.
- 키 로테이션이 수동 — 환경변수 갱신 후 재배포.
- OIDC/SAML 미연동 — 팀 스케일 커지면 JWT + IdP 도입 필요.

### Follow-up
- 차후 JWT 플러그-인 필러로 교체 (authenticated principal 은 동일하게 표현).
- 키 최종 사용 시각 추적 + 미사용 키 알림 (미구현).

## Alternatives considered

1. **Basic Auth**. 보안 수준은 비슷하나 브라우저 프롬프트와 쿠키 취급이 깔끔하지 않아 기각.
2. **JWT + 외부 IdP**. 운영 복잡도 급증, 현재 팀 규모 대비 오버킬.
3. **Mutual TLS (mTLS)**. 내부 서비스간 호출에 강력하지만 유저 엔드포인트엔 부적합.
