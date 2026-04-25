# ADR 0005 — Clock 주입 / 가상 시간

- Status: **Accepted**

## Context

트레이딩 도메인은 시간 의존성이 많다.
- `DailyLossGuard` — 자정 KST 리셋
- `IdempotencyService` — 24h TTL
- `OcoOrderService` — cooldown, 손절 발동 시각
- `OutboxPublisher` — 지수 백오프 `nextAttemptAt`

`Instant.now()` / `LocalDate.now()` 직접 호출은 테스트에서:
- 자정 경계 시나리오 재현 불가 (`Thread.sleep` 으로 86400초 기다릴 수는 없음)
- Deterministic simulation (outbox 장애 주입) 구현 불가

## Decision

모든 시간-민감 코드는 주입된 `java.time.Clock` 을 쓴다.
- `ClockConfig` 가 기본 `Clock.system(Asia/Seoul)` 빈을 노출.
- 테스트는 `MutableClock` (testsupport) 으로 수동 전진: `clock.advance(Duration.ofDays(1))`.
- `OutboxDeterministicSimulationTest` 는 같은 Clock 을 공유해 **재현 가능한** 장애 시나리오를 돌린다 (동일 seed → 동일 결과).

## Consequences

- 자정 rollover, TTL 만료, 재시도 백오프 등이 **ms 단위 테스트 실행시간** 내에 검증됨.
- 운영 경로는 동작 변화 없음 (`Clock.system`).
- 작업자가 실수로 `Instant.now()` 를 쓰지 못하도록 `checkstyle` / `forbidden-apis` 플러그인으로 강제하는 것이 다음 단계.

## Alternatives considered

1. **`System.currentTimeMillis()` 직접 + test 용 `Thread.sleep`**. 느리고 flaky. 한국 주식 자정 경계 테스트는 아예 불가능.
2. **Mockito `mockStatic(Instant.class)`**. 동작은 되지만 동시성 테스트에서 thread-local 한 mock 이 새어나가 이상 동작.
