# ADR 0002 — 주문 2-phase persistence + Outbox

- Status: **Accepted** (2026-04-24)
- Supersedes: 1-phase execute-then-save (기존 Phase 0 구현)

## Context

기존 `OrderExecutionService.execute()` 는 "KIS 호출 → DB save + outbox" 순서였다. 실패 시나리오:

| 단계 | 결과 |
|---|---|
| HTTP 호출 도중 서버 크래시 | KIS 에는 주문 있을 수 있음, DB엔 아무 기록 없음 → 불가시 상태 |
| HTTP 응답 후 DB commit 실패 | KIS 는 ACCEPTED, 로컬은 기록 없음 → 재시도 시 KIS 중복 주문 |
| DB commit 후 Kafka publish 실패 | Outbox 에 남아 publisher 가 재시도 (at-least-once) — 이건 OK |

가장 위험한 건 **"KIS 에는 있는데 우리 쪽엔 없음"** — 고객에게 책임을 전가하거나 포지션을 잃을 수 있다.

## Decision

2-phase flow 를 도입한다.

1. **phase 1 (PLACING)** — KIS 호출 전에 `OrderRecord(status=PLACING, createdAt=now, principal, traceId)` 를 별도 tx 로 INSERT 한다.
2. **KIS HTTP** — 트랜잭션 밖.
3. **phase 2 (ACCEPTED / REJECTED / FAILED)** — 결과를 같은 레코드에 UPDATE + outbox event INSERT (한 tx).

상태 기계:
```
PLACING → ACCEPTED → (outbox 이벤트 발행)
        → REJECTED → (outbox 이벤트 발행)
        → FAILED   → (outbox 없음 — KIS 도달 여부 모호)
```

## Consequences

- **Recoverability**: phase 1 commit 만 성공했다면, 서버 크래시 후 `PLACING` 레코드가 DB 에 남아 있으므로 스케줄 잡이 KIS 미체결 조회로 reconcile 가능.
- **FAILED 는 outbox 로 가지 않는다**: HTTP 예외 상황에서 KIS 실제 처리 여부가 모호하므로 downstream 시스템에 "성공" 암시를 보내지 않는다. 운영자가 수동 reconcile.
- **감사 기록이 두 단계**: "언제 요청이 들어왔는지" 와 "언제 결과가 확정되었는지" 를 분리 추적. 지연 시 병목 구간을 latency 로 식별.
- **idempotencyKey** 는 phase 1 에 저장 — 동일 키 재요청 시 DB UNIQUE 제약으로 차단.

## Alternatives considered

1. **Saga + Compensation**. 마이크로서비스가 여럿이면 유효하지만, 모놀리스에는 오버엔지니어링.
2. **TCC (Try-Confirm-Cancel)**. 외부 KIS 에 "Try" 의미 엔드포인트가 없어 불가.
3. **기존 1-phase 유지**. 위 실패 시나리오 방치 — 실매매 환경에서 금전 손실 리스크.
