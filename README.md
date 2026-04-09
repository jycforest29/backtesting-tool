# 그때 샀으면 지금 얼마?

과거 특정 시점에 투자했다면 현재 얼마가 되었을지 시뮬레이션하는 백테스팅 도구입니다.

## 지원 자산

- 미국 / 한국 / 일본 주식
- 환율 (USD/KRW, USD/JPY, KRW/JPY, EUR/USD)
- 금 / 은 선물
- 비트코인

Yahoo Finance 데이터를 사용하며, 별도의 API 키가 필요하지 않습니다.

## 기술 스택

| 구분 | 기술 |
|------|------|
| Backend | Java 17, Spring Boot 3.2 |
| Frontend | React 18, Vite 5, Recharts |
| 데이터 | Yahoo Finance API |

## 시작하기

### 사전 요구사항

- Java 17+
- Node.js 18+
- Maven

### Backend

```bash
cd backend
./mvnw spring-boot:run
```

서버가 `http://localhost:8080`에서 실행됩니다.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

`http://localhost:5173`에서 접속할 수 있으며, API 요청은 백엔드로 프록시됩니다.

## API

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/api/backtest` | 백테스트 실행 |
| GET | `/api/search?q={query}&market={US\|KR\|JP}` | 종목 검색 |
| GET | `/api/presets` | 환율/원자재 프리셋 목록 |
