# ADR 0004 — Bulkhead 격리: downstream 별 독립 ExecutorService

- Status: **Accepted** (pre-existing, 이 문서는 사후 기록)

## Context

애플리케이션은 KIS / DART / ELW 세 외부 시스템을 동시에 호출한다. 하나가 느려지면(429, 20s 타임아웃, …) 공용 스레드풀을 점유해 다른 downstream 호출까지 함께 고사한다 — 대표적인 **noisy-neighbor** 장애 패턴.

## Decision

`BulkheadExecutorsConfig` 에서 downstream 별 독립 `ThreadPoolExecutor` 를 생성한다 (`kisExecutor`, `dartExecutor`, `elwExecutor`).

- Core/Max 동일 (고정 pool size), `allowCoreThreadTimeOut=false`.
- 큐: `LinkedBlockingQueue` (cfg.queueCapacity). 큐가 차면 **`AbortPolicy`** — caller 스레드를 붙잡지 않고 즉시 `RejectedExecutionException` 반환. 호출자는 degraded fallback 으로 빠질 수 있음.
- 모든 executor 는 `ContextPropagatingExecutor` 로 래핑 → `RequestContext` (traceId, deadline) 자동 전파.
- `BulkheadIsolationTest` 에서 "KIS 포화 시 DART 호출이 영향받지 않아야 한다" 불변식 자동 검증.

## Consequences

- 단일 downstream 장애가 전체 시스템을 마비시키지 않음.
- 자원 격리 → 튜닝 단위 세분화 (KIS 만 풀 사이즈 ↑ 가능).
- **단점**: 총 스레드 수 증가. 운영 모니터링(`thread_pool_active` 메트릭) 필요.
- caller 가 `RejectedExecutionException` 을 잡아야 함 — 핸들링 누락 시 500 직행 위험. 관찰성(log + metric) 보강 필수.

## Alternatives considered

1. **Resilience4j Bulkhead**. 추상화가 깔끔하나 세밀 튜닝이 커스텀 ThreadPoolExecutor 대비 제약적.
2. **단일 공용 풀**. 간단하나 noisy neighbor 문제를 그대로 떠안음.
3. **가상 스레드 (Loom)**. Java 21 전제. 스레드 비용이 사라지면 이 ADR 의 전제가 약해져 재검토 필요.
