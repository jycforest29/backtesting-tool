# Runbook — 온콜 / 장애대응

대상 시스템: 백테스팅 + 자동매매 백엔드 (Spring Boot 3, Java 17). 이 문서는 일반적인 장애 시나리오별 **"무엇을 언제 무엇을"** 기술한다. 의사결정 트리가 필요한 순간에 참고.

---

## 1. 핵심 상태 확인

```bash
# 1) 애플리케이션 헬스
curl -s localhost:8080/actuator/health | jq .
#    status=UP 전제. OUT_OF_SERVICE 구성요소별 원인 확인.

# 2) 분산 트레이스 확인 (traceId 가 로그/에러 응답에 포함)
#    grep "traceId=<id>" ./logs/app.log

# 3) 비즈니스 메트릭 (Prometheus)
curl -s localhost:8080/actuator/prometheus | grep trading_
#    trading_orders_submitted_total, trading_orders_accepted_total,
#    trading_orders_rejected_total, trading_orders_failed_total,
#    trading_loss_guard_blocked_total

# 4) KIS 연결/토큰
curl -s localhost:8080/actuator/health | jq '.components.trading'
```

---

## 2. 증상 → 조치 매트릭스

### 2.1 주문 API 가 429 반환

- **내용**: HTTP 레이트리밋(Bucket4j) 또는 KIS upstream rate limit.
- **구분**:
  - 응답 `code=RATE_LIMITED` → 우리 HTTP 필터. `X-Rate-Limit-Remaining: 0`. 호출자 튜닝 필요.
  - 응답 `code=RATE_LIMITED_UPSTREAM` → KIS 초당 한도 초과. `Retry-After` 만큼 대기 후 재시도.
- **조치**:
  - 빈번하면 `HANTOO_RATE_PER_SEC` 환경변수를 KIS 계정의 실제 한도에 맞춰 조정 (신규 3일간 3, 그 이후 실전 18, 모의 1).
  - 내부 과다 호출이면 `AutoTradingService.refresh` 주기 / 감시 종목 수 검토.

### 2.2 주문이 PLACING 상태에서 움직이지 않음

- **내용**: `OrderExecutionService` phase 1 commit 후 KIS 응답 대기 중 앱이 크래시/재배포된 흔적.
- **확인**:
  ```sql
  SELECT id, symbol, side, quantity, created_at
  FROM order_record
  WHERE status = 'PLACING' AND created_at < NOW() - INTERVAL 5 MINUTE;
  ```
- **조치**:
  1. 각 레코드 `traceId` 로 로그 grep → 어디까지 진행됐는지 확인.
  2. KIS 미체결 조회 API 호출 → `symbol + side + quantity` 일치하는 주문이 있으면 수동 ACCEPTED 로 UPDATE.
  3. 없다면 REJECTED/FAILED 로 마킹하고 사용자에게 통지.
  4. 차후 이 단계를 자동화하는 reconciliation job 이 필요 (TODO).

### 2.3 `trading_loss_guard_blocked_total` 이 급증

- 일일 손실 한도 초과. 이건 **정상 보호 동작** — 알람 메일이 1회 발송됐는지 확인.
- 사용자가 리셋 원하면 `DAILY_LOSS_LIMIT_KRW` 를 올리거나, 자정(KST) 자동 리셋 기다림.
- 강제 리셋이 필요하면 `POST /actuator/…` (미구현 — 스케줄 수동 트리거를 추가 후).

### 2.4 Kafka 가 내려감

- 헬스체크 `kafkaHealth` → `OUT_OF_SERVICE`.
- **영향**:
  - Outbox 이벤트가 DB 에 쌓이되 발행 안 됨 (publisher 가 지수 백오프 재시도).
  - 주문 API 응답은 정상 — 쓰기 경로는 Kafka 에 의존하지 않음 (outbox 패턴 효과).
- **조치**:
  - Kafka 복구 후 publisher 가 자동 재개. `outbox_event` 테이블 `sent_at IS NULL` 인 건이 감소하는지 확인.
  - 장시간 지연 시 downstream 소비자 (체결 확정 등) 에게 사전 공지.

### 2.5 Redis 가 내려감

- 헬스체크 `redisHealth` → `OUT_OF_SERVICE`.
- **영향**: 캐시 미사용 (`@Cacheable` 무효) → KIS/DART 호출 빈도 증가 → upstream rate limit 위험.
- **조치**:
  - `CACHE_TYPE=simple` 로 임시 전환 (in-process 캐시로 degrade).
  - Redis 복구 후 원복.

### 2.6 일시적으로 HTTP 503 Overloaded

- AdmissionController (in-flight 전역 한도) 가 거부한 것. 응답 `code=OVERLOADED`.
- 조치: 트래픽 spike 원인 파악 (어떤 path 에서 증가? 어떤 principal?). `X-Priority: CRITICAL` 헤더 과용 검토.

### 2.7 OCO 포지션이 손절/익절 반응하지 않음

- **확인**:
  1. `GET /api/trading/oco` 로 상태 점검.
  2. 로그에서 `OcoOrderService.onTick` 호출 여부 확인.
  3. `kisWs` 연결 상태 — WebSocket 재접속 로그 (`reconnectAttempts`) 가 쌓이면 KIS 쪽 네트워크 / 승인키 문제.
  4. **복구 중(`recoveryComplete=false`) tick 은 드롭**된다 — 시작 직후 수분간은 정상. 로그 `OCO recovery: …` 완료 확인.
- **조치**: WS 재접속 유도(`POST /api/trading/refresh` 는 HTTP 폴링 트리거). 근본원인은 승인키 만료일 확인.

### 2.8 배포 후 첫 요청부터 500

- **주요 원인 체크**:
  - `SECURITY_FAIL_ON_MISSING_KEYS=true` 인데 `*_API_KEY` env 누락.
  - `HANTOO_API_KEY/SECRET/ACCOUNT` 누락 — `TradingHealthIndicator` 가 OUT_OF_SERVICE 로 표시.
  - `DB_PATH` 쓰기 권한 없음 (H2 파일).
  - logback-spring.xml 의 `spring.profiles.active=prod` 인데 로그가 엉뚱한 포맷 → `SPRING_PROFILES_ACTIVE` 재확인.

### 2.9 "요청 시간 초과" (DEADLINE_EXCEEDED, 504)

- `X-Deadline-Ms` 헤더가 짧거나, downstream 이 느린 상태.
- 조치:
  - 클라이언트가 보낸 deadline 재확인.
  - 내부: 어떤 downstream 에서 시간이 썼는지 분산트레이스 / upstream_http_seconds 메트릭 분위수로 파악.

---

## 3. 배포 / 롤백

- 표준 배포: `mvn -B verify && docker build -t …`.
- 롤백:
  - 주문 엔드포인트에 변경이 있었다면 **롤백 전에** `SELECT COUNT(*) FROM order_record WHERE status = 'PLACING'` 점검 — 있으면 먼저 reconcile.
  - Outbox 스키마 변경은 rolling 불가 — downtime 공지.
- Feature flag / env 토글:
  - `HTTP_RATE_LIMIT_ENABLED=false` 로 레이트리밋 즉시 off (진단용).
  - `ELW_ENABLED=false` 로 ELW 스캐너 off.

---

## 4. 데이터 위생

- H2 파일 DB → 프로덕션에서는 PostgreSQL 로 이관 예정 (ADR 작성 중).
- 감사로그 보관 60일 (`AuditLogService.RETENTION_DAYS`). 감사 쿼리가 느려지면 인덱스 점검.

---

## 5. 에스컬레이션

- 장애 레벨:
  - P0: 주문 성공 후 "로컬 기록 없음" 사례 발견 (이론상 불가) → 즉시 리더 에스컬레이션.
  - P1: 주문 API 5xx 2% 이상 or KIS 인증 실패 지속.
  - P2: 스캐너 / 리포트 잡 지연.
- Slack 채널: (미정) · 이메일: `ALERT_RECIPIENT` env 값.
