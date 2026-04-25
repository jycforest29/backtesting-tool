# ADR 0007 — KIS upstream rate limiter 재설계 (ReentrantLock + Condition)

- Status: **Accepted** (2026-04-24)
- Supersedes: synchronized + Thread.sleep 구현

## Context

기존 `KisRateLimiter.acquire()` 는 `synchronized` 메서드 내부에서 `Thread.sleep(waitMs)` 를 호출했다. 효과:

- 한 스레드가 sleep 하는 동안 **다른 모든 KIS 호출 스레드가 동일 mutex 에 block**. 대기열이 길어질수록 OS 스레드가 차곡차곡 쌓임 → 스레드풀 고갈 / GC 압박.
- Interrupt 시 `RuntimeException` 으로 변환하는 처리가 호출 규약에 맞지 않음 (upstream 시그널 손실).
- 버킷 단일 lock — general 포화가 token 버킷 대기까지 유발했던 과거 버그.

## Decision

버킷별 `ReentrantLock(fair=true)` + `Condition.awaitNanos()` 기반으로 재작성.

- lock/condition 자체가 **버킷별로 독립** — general / token 상호 간섭 없음.
- `awaitNanos` 는 대기 중 **lock 을 해제**하므로 다른 스레드가 슬롯을 회수해서 signal 할 수 있다.
- `fair=true` → FIFO 공정성. 긴 대기 스레드가 subset 에게 starve 되는 것 방지.
- 내부 시계는 주입된 `Clock` — 테스트에서 `MutableClock.advance(...)` 로 윈도우 전진.

## Consequences

- 동시 20 스레드가 capacity=5 버킷을 치면, 5 개만 통과하고 나머지는 maxWaitMs 내 슬롯 열림을 대기, 그래도 실패하면 `KisRateLimitedException`. 스레드 park 중에는 lock 놓음 → CPU 부하 없음.
- 기존 test 가 단일 테스트 (`KisRateLimiterTest`) 로 동시성 정확성 검증.
- `ReentrantLock.signal()` 을 슬롯 반환 시 호출해 broadcast 효과 없이 한 명만 깨움 → 효율.

## Alternatives considered

1. **`Semaphore(fair=true)`**. 단순하나 슬라이딩 윈도우 의미(1초 경과 후 슬롯 자동 회수) 를 직접 구현해야 함. window timestamp 큐 + Semaphore 조합도 가능하나 lock + condition 직관성 우위.
2. **Resilience4j RateLimiter**. 이미 튜닝된 구현체가 있으나, KIS 의 "slotavailability 은 windowStart 후 1초" 의미와 Resilience4j 의 `refreshPeriod` 모델이 미묘하게 다름. 검증 부담.
3. **Thread-per-request 없이 리액티브**. WebClient + Reactor 전환 필요 — 전체 HTTP 스택 바꿔야 함. 범위 초과.
