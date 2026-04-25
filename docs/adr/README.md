# Architecture Decision Records

이 디렉터리는 시스템의 중요한 설계 결정을 문서화한다. 각 ADR은 상태(Proposed / Accepted / Superseded)를 명시하고, 결정의 **맥락**과 **대안** 그리고 **결과(trade-off)**를 함께 남긴다. 목적은 "왜 이렇게 짰는가?"를 6개월 뒤에 도 추적할 수 있도록 하는 것이다.

## 포맷
- 파일명: `NNNN-short-slug.md`
- 섹션: `Status`, `Context`, `Decision`, `Consequences`, `Alternatives considered`

## 현재 ADR
- [0001 — API 키 기반 인증 (X-API-Key 헤더)](0001-api-key-auth.md)
- [0002 — 주문 2-phase persistence + outbox](0002-order-two-phase-persistence.md)
- [0003 — 도메인 예외 + 통합 ErrorResponse 모델](0003-unified-error-model.md)
- [0004 — Bulkhead 격리 (downstream 별 executor)](0004-bulkhead-isolation.md)
- [0005 — Clock 주입 / 가상 시간 테스트](0005-clock-injection.md)
- [0006 — HTTP per-principal 레이트리밋 (Bucket4j)](0006-http-rate-limit.md)
- [0007 — KIS rate limiter ReentrantLock 재설계](0007-kis-rate-limiter-redesign.md)
