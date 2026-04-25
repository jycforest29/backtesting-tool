# Backtesting + Auto-Trading + Quant Platform

과거 시점 투자 시뮬레이션(백테스팅) 에서 출발해 **한국투자증권(KIS) 연동 자동매매 + 퀀트 전략 실행 + ELW 스캐너 + DART 공시 브리핑** 까지 포함하는 통합 트레이딩 플랫폼.

## 핵심 가치
- 국내/해외 주식 실매매 — idempotent 주문 API, 2-phase persistence, OCO (손절/익절) 자동화
- 퀀트 전략 백테스트 + 실행 (듀얼모멘텀, LAA, VAA, DAA, 매직포뮬러, 할로윈, SPAC 이벤트, 아리랑 팩터 등)
- ELW 내재변동성 스큐 스캐너 (Black-Scholes, 500+ 회 property-based test)
- DART 공시 일일 브리핑 (호재/악재 분류 메일)
- 일일 실현손실 한도 자동 차단 (DailyLossGuard)
- 실시간 시세 WebSocket 스트림 + REST 폴링 하이브리드

## 아키텍처 개요

```
┌──────────────┐       ┌─────────────────────────┐
│  React SPA   │ HTTP  │    Spring Boot Backend  │
│   (Vite)     │◄─────►│  - Security (API Key)   │
└──────────────┘  WS   │  - Bucket4j rate limit  │
                       │  - OrderExecution       │       ┌─────────┐
                       │  - OCO engine           │◄──►───│   KIS   │
                       │  - Quant strategies     │ HTTPS │ OpenAPI │
                       │  - ELW skew scanner     │  WS   └─────────┘
                       │  - DART disclosure      │       ┌─────────┐
                       │  - DailyLossGuard       │◄──►───│  DART   │
                       │  - Outbox publisher     │ HTTPS └─────────┘
                       └────┬──────┬──────┬──────┘
                            │      │      │
                        ┌───▼──┐ ┌─▼──┐ ┌─▼────┐
                        │ H2   │ │Redis│ │Kafka │
                        │ (JPA)│ │cache│ │events│
                        └──────┘ └────┘ └──────┘
```

## 주요 설계 결정 (ADR)
- [0001 — API 키 인증](docs/adr/0001-api-key-auth.md)
- [0002 — 주문 2-phase persistence + outbox](docs/adr/0002-order-two-phase-persistence.md)
- [0003 — 통합 ErrorResponse](docs/adr/0003-unified-error-model.md)
- [0004 — Bulkhead 격리](docs/adr/0004-bulkhead-isolation.md)
- [0005 — Clock 주입](docs/adr/0005-clock-injection.md)
- [0006 — HTTP per-principal rate limit](docs/adr/0006-http-rate-limit.md)
- [0007 — KIS rate limiter 재설계](docs/adr/0007-kis-rate-limiter-redesign.md)

## 기술 스택

| 구분 | 기술 |
|------|------|
| Backend | Java 17, Spring Boot 3.2, JPA, Spring Security, Spring Kafka, Spring Data Redis |
| 테스트 | JUnit 5, Mockito, jqwik (property-based), WireMock, JaCoCo, JMH |
| 관측성 | Micrometer → Prometheus, OpenTelemetry, Logstash JSON encoder |
| 레이트리밋 | Bucket4j (HTTP), ReentrantLock 슬라이딩 윈도우 (KIS upstream) |
| 프론트 | React 18, Vite 5, Recharts |
| 인프라 | Docker multi-stage, docker-compose (Kafka + Redis + Kafka-UI), K8s manifests |

## 시작하기

### 사전 요구사항
- Java 17+
- Node.js 20+
- Docker (Kafka/Redis 구동용, 선택사항)

### 백엔드

```bash
cd backend

# 최소 설정 (로컬, open 모드)
./mvnw spring-boot:run

# 풀 설정 (KIS 연동 포함) — .env 로 주입
cp .env.example .env
# 편집: HANTOO_API_KEY, HANTOO_API_SECRET, HANTOO_ACCOUNT, ADMIN_API_KEY 등
./mvnw spring-boot:run
```

헬스체크:
```bash
curl localhost:8080/actuator/health
```

API 문서 (Swagger UI): http://localhost:8080/swagger-ui.html

### 프론트엔드

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173 — 백엔드로 프록시
npm run lint         # ESLint (errors 0 게이트, warnings 허용)
npm run typecheck    # tsc --noEmit (TS 점진 도입)
npm test             # Vitest + RTL + MSW (단위/통합)
npm run test:e2e:install  # (최초 1회) Playwright chromium 다운로드
npm run test:e2e     # Playwright 스모크 — dev 서버 자동 기동, /api/* 는 page.route() stub
npm run build        # 프로덕션 번들
```

- 스택: React 18 + Vite 5 + Vitest + Testing Library + MSW. ESLint 9 flat config + Prettier.
- TypeScript 점진 도입 (`allowJs: true`). 첫 마이그레이션 대상은 `src/utils/formatters.ts`.
- 전역 `ErrorBoundary` 가 `main.jsx` 에서 `<App/>` 을 감싸 컴포넌트 단위 throw 를 격리.
- 폼 이중제출 방지: `useSubmitting` 훅 (ref 기반 동기 lock + state UI).

### Docker Compose (전체)

```bash
docker compose up --build
```

## 테스트 / 검증

```bash
cd backend
mvn verify          # 단위·통합 테스트 + JaCoCo 커버리지 게이트
mvn test            # 테스트만
```

- 백엔드 단위 테스트 200+ 개. `DailyLossGuardTest` 등은 동시성 32 스레드 x 200 반복 스트레스 포함.
- 백엔드 커버리지 게이트: `com.backtesting.security` / `domain` / `common.error` 각 ≥55% 라인, 전체 ≥25%.
- 프론트 테스트: `formatters` 포맷 / `useSubmitting` race / `ErrorBoundary` fallback / `AlertsToggle` STOMP 재구독.
- 프론트 E2E (Playwright): 포트폴리오 폼 골든패스 + 제출 버튼 race 차단 (실제 브라우저, 백엔드 없이 stub).
- 백엔드 보안 E2E: `SecurityE2ETest` — `@WebMvcTest` 슬라이스에 진짜 `SecurityFilterChain` 와이어링 + 4개 role 매트릭스.

## 운영
- [Runbook — 장애대응 매뉴얼](docs/RUNBOOK.md)
- 주요 메트릭: `trading_orders_{submitted,accepted,rejected,failed}_total`, `trading_loss_guard_blocked_total`, `upstream_http_seconds{upstream,endpoint}`.
- K8s manifests: `k8s/` 아래. StatefulSet + Ingress + Secrets + ConfigMap.

## 환경변수

필수 (운영):
- `HANTOO_API_KEY`, `HANTOO_API_SECRET`, `HANTOO_ACCOUNT` — KIS OpenAPI
- `ADMIN_API_KEY` (최소 1개 이상의 role key)
- `SECURITY_FAIL_ON_MISSING_KEYS=true` — 운영에서 키 없으면 부팅 실패시키는 안전장치
- `DB_PASSWORD`, `REDIS_PASSWORD`

선택:
- `DART_API_KEY` — 공시 브리핑
- `GMAIL_USERNAME`, `GMAIL_APP_PASSWORD`, `ALERT_RECIPIENT` — 메일 알림
- `KAFKA_BOOTSTRAP` — 이벤트 파이프라인
- `CORS_ALLOWED_ORIGINS` — 프론트 도메인
- `LOG_LEVEL_COM_BACKTESTING` — 애플리케이션 로그 레벨 (기본 DEBUG)

전체 목록: `.env.example` 참조.
