// 시장 / 사이드 / 주문유형 도메인 enum + 시장별 메타데이터.
//
// 백엔드 com.backtesting.model.Market enum 과 1:1 대응. 클라이언트가 typed 로 다루기 위한 mirror.
// 왜 클라이언트에 미러를 두나:
//   1. 컴파일 타임 좁힘 (Market 타입을 인자로 받는 함수가 'XX_STOCK' 오타를 컴파일 시 거부)
//   2. SSOT — UI 라벨/거래소/통화는 서버 응답이 아니라 도메인 자체의 상수 (서버 라운드트립 불필요)
//   3. 테스트 시 백엔드 부팅 없이 시장 분기 검증 가능

import type { Currency } from '../utils/formatters'

export type Market = 'KR_STOCK' | 'US_STOCK' | 'JP_STOCK'
export type Side = 'BUY' | 'SELL'
export type OrderType = 'LIMIT' | 'MARKET'

export interface MarketSpec {
  value: Market
  label: string
  /** default 거래소는 첫 원소. KRX 처럼 단일 시장도 있고 NAS/NYS/AMS 처럼 다중 시장도 있음 */
  exchanges: readonly string[]
  currency: Currency
}

export interface TaxFeeSpec {
  /** 수수료율 (%, 주문대금 기준) */
  commissionRate: number
  /** 거래세율 (%, 주문대금 기준). 0 이면 부과 없음 */
  taxRate: number
  /** 거래세 부과 측 ('BUY'/'SELL'). null 이면 부과 없음 */
  taxOn: Side | null
  /** 거래세 라벨 (UI 표시용) */
  taxLabel: string | null
}

export const MARKETS: readonly MarketSpec[] = [
  { value: 'KR_STOCK', label: '한국', exchanges: ['KRX'],               currency: 'KRW' },
  { value: 'US_STOCK', label: '미국', exchanges: ['NAS', 'NYS', 'AMS'], currency: 'USD' },
  { value: 'JP_STOCK', label: '일본', exchanges: ['TSE'],               currency: 'JPY' },
] as const

// 한투 온라인 기준 추정. 실제 체결가·수수료는 증권사 이벤트·고객 등급에 따라 다를 수 있음.
// 학습 목적이라 0 인 항목도 명시적으로 둔다 — 어떤 시장에 어떤 항목이 없는지 자체가 도메인 지식.
export const TAX_FEE: Readonly<Record<Market, TaxFeeSpec>> = {
  KR_STOCK: { commissionRate: 0.015, taxRate: 0.18,    taxOn: 'SELL', taxLabel: '증권거래세' },
  US_STOCK: { commissionRate: 0.25,  taxRate: 0.00278, taxOn: 'SELL', taxLabel: 'SEC fee'    },
  JP_STOCK: { commissionRate: 0.10,  taxRate: 0,       taxOn: null,   taxLabel: null         },
}

/** Market value 로 spec 조회. 일치 항목이 없으면 KR_STOCK 으로 폴백 (런타임 안전망). */
export function findMarket(value: string): MarketSpec {
  return MARKETS.find(m => m.value === value) ?? MARKETS[0]
}

/** Currency → Market 역매핑. 서버 응답에 market 필드가 없을 때 currency 로 추론. */
export function marketByCurrency(currency: Currency): Market {
  if (currency === 'KRW') return 'KR_STOCK'
  if (currency === 'JPY') return 'JP_STOCK'
  return 'US_STOCK'
}
