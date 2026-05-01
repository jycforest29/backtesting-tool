// /api/trading/* 호출의 단일 진입점.
//
// 왜 한 모듈에 모으나:
//   - DTO 타입을 호출부에 노출 → 컴포넌트는 string 가공 부담 없음
//   - 테스트에서 vi.mock('../api/trading') 한 번으로 트레이딩 화면 전체 stub 가능
//   - 추후 X-Idempotency-Key 헤더, 401 시 재시도, 타임아웃 등을 한 곳에서 추가
//   - 서버 페이로드 변경 시 영향 범위가 이 파일 + 콜러 타입 에러로 한정 (광역 grep 불필요)

import type { Market, Side, OrderType } from '../domain/market'
import type { Currency } from '../utils/formatters'

// ─── DTO ──────────────────────────────────────────────────────────────────────

export interface TradingStatus {
  configured: boolean
  paperTrading: boolean
  accountConfigured: boolean
}

export interface Quote {
  symbol: string
  name: string
  /** 백엔드 BigDecimal 직렬화 → string. 클라에서 parseFloat 책임 */
  price: string
  change: string
  changePercent: string
  currency: Currency
  /** 클라이언트 측 transient flag. 가격 갱신 직후 1.5초간 true → 깜빡임 효과 */
  flash?: boolean
}

export interface Holding {
  symbol: string
  name?: string
  quantity: number
  avgPrice: string
  currentPrice: string
  evalAmount: string
  pnl: string
  pnlRate: string
}

export interface Balance {
  deposit: string
  totalEvalAmount: string
  totalPnl: string
  totalPnlRate: string
  holdings: Holding[]
}

export interface OpenOrder {
  orderNo: string
  symbol: string
  name?: string
  side: Side
  quantity: number
  price: string
  remaining: number
}

export interface OrderRequest {
  market: Market
  symbol: string
  /** KR_STOCK 은 단일 거래소(KRX)라 null. 그 외는 NAS/NYS/AMS/TSE 중 하나 */
  exchange: string | null
  side: Side
  orderType: OrderType
  quantity: number
  /** LIMIT 일 때만 의미. MARKET 이면 undefined. string 그대로 보내 BigDecimal 정밀도 보존 */
  price?: string
}

export interface OrderResponse {
  success: boolean
  orderNo?: string
  message?: string
  error?: string
}

export interface WatchAddRequest {
  market: Market
  code: string
  exchange?: string
}

// ─── fetch 래퍼 ────────────────────────────────────────────────────────────────

/**
 * 백엔드 GlobalExceptionHandler 가 만드는 표준 에러 페이로드.
 * 변경 시 백엔드 ErrorResponse 와 동기화 필요.
 */
export interface ApiErrorPayload {
  code?: string
  message?: string
  traceId?: string
  /** legacy — 일부 컨트롤러가 여전히 {error: "..."} 형식을 반환할 수 있어 호환 유지. */
  error?: string
}

/**
 * fetch 실패 시 던져지는 에러. status/code 를 보존해 호출자가 분기 가능.
 *  - 503 + code='DEPENDENCY_NOT_CONFIGURED': 외부 의존성 미설정 (UX 상 inline 배너)
 *  - 401/403: 인증/권한
 *  - 그 외: 일반 에러 토스트
 */
export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly traceId?: string
  constructor(status: number, payload: ApiErrorPayload, fallback: string) {
    super(payload.message || payload.error || fallback)
    this.name = 'ApiError'
    this.status = status
    this.code = payload.code
    this.traceId = payload.traceId
  }
  /** 의존성 미설정 — UI 가 토스트 대신 inline 배너로 보여주도록. */
  isDependencyMissing() {
    return this.status === 503 && this.code === 'DEPENDENCY_NOT_CONFIGURED'
  }
}

/**
 * JSON 응답 강제 + 4xx/5xx → ApiError throw.
 *
 * 왜 throw 인가:
 *   - 호출자가 try/catch 로 분기 vs `if (!res.ok)` 분기는 noisy. throw 가 한국 표준.
 *   - 단 useTradingQuotes 처럼 fire-and-forget 인 곳은 .catch(()=>{}) 로 처리 (errors 무시).
 *   - res.json() 자체가 빈 body 일 때 SyntaxError → catch 로 {} 반환.
 */
async function callJson<T>(input: RequestInfo, init?: RequestInit): Promise<T> {
  const res = await fetch(input, init)
  const data = (await res.json().catch(() => ({}))) as ApiErrorPayload
  if (!res.ok) {
    throw new ApiError(res.status, data, res.statusText || 'request failed')
  }
  return data as unknown as T
}

// ─── 엔드포인트 ───────────────────────────────────────────────────────────────

export const tradingApi = {
  status:     ():       Promise<TradingStatus> => callJson('/api/trading/status'),
  quotes:     ():       Promise<Quote[]>       => callJson('/api/trading/quotes'),
  balance:    (m: Market): Promise<Balance>    => callJson(`/api/trading/balance?market=${m}`),
  openOrders: ():       Promise<OpenOrder[]>   => callJson('/api/trading/orders/open'),
  refresh:    ():       Promise<void>          => callJson('/api/trading/refresh', { method: 'POST' }),

  order: (req: OrderRequest): Promise<OrderResponse> =>
    callJson('/api/trading/order', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    }),

  addWatch: (req: WatchAddRequest): Promise<void> =>
    callJson('/api/trading/watchlist', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    }),

  removeWatch: (market: Market, code: string): Promise<void> =>
    callJson(`/api/trading/watchlist?market=${market}&code=${code}`, { method: 'DELETE' }),
}
