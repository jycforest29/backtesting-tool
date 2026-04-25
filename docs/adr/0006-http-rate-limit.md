# ADR 0006 — HTTP per-principal 레이트리밋 (Bucket4j)

- Status: **Accepted** (2026-04-24)

## Context

기존에는 KIS upstream 호출에만 레이트리밋이 있었다 (`KisRateLimiter`). HTTP 진입점(`/api/**`)에는 레이트리밋이 없어, 악의적 / 버그 있는 클라이언트가 주문 엔드포인트를 초당 수백 회 호출 → 즉시 `KisRateLimitedException` 이 발생하는 식으로 KIS 쿼터를 태워먹을 수 있었다.

## Decision

Bucket4j 기반 per-principal / per-IP 토큰 버킷 필터를 `SecurityConfig` 필터 체인의 인증 필터 **다음** 에 삽입한다.

- 키 우선순위: 인증 principal > X-API-Key 해시 > IP(X-Forwarded-For 우선).
- 엔드포인트 분류:
  - 트레이딩 (`POST /api/trading/order`, `POST /api/trading/oco`, `POST /api/strategy-events/**`) → 엄격 (기본 20 burst / 60rpm).
  - 그 외 `/api/**` → 완만 (120 burst / 60rpm).
- 한도 초과 시 429 + `Retry-After` + 표준 `ErrorResponse`.
- `/actuator/health*`, `/actuator/info`, `/actuator/prometheus`, `OPTIONS *` 는 필터 대상 외.
- 메모리 버킷 — 단일 인스턴스 배포 전제. 스케일아웃 시 `bucket4j-redis` 로 교체.

## Consequences

- 주문 엔드포인트가 한 클라이언트 당 초당 약 1건 이상 들어가지 않도록 자연 차단. KIS rate limit 쿼터가 accidental DoS 에 낭비되지 않음.
- 인증된 principal 단위 / 미인증 IP 단위로 격리 → 한 클라이언트의 과부하가 다른 고객에게 영향 없음.
- 단점: 메모리 기반이므로 버킷 정보가 재시작 시 소실. 로테이션 공격 방어 부족. 멀티 인스턴스 배포엔 부적합.

## Alternatives considered

1. **Resilience4j RateLimiter**. 추상화는 좋으나 per-principal key 스위칭이 어색함.
2. **Nginx/ingress 단 레이트리밋**. 네트워크 경계에서 처리 가능하나 인증된 principal 단위 정책을 만들기 어렵고 (인증이 backend 에 있음), 운영 부서와 조율 필요.
3. **DB 카운터 기반**. 정확도 ↑, 성능 ↓. 트레이딩 핫패스에 DB 라운드트립 추가 비용 부담.
