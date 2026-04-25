import { useMemo } from 'react'
import { fmtMoney } from '../../utils/formatters'
import { findMarket, TAX_FEE, type Market, type Side, type OrderType } from '../../domain/market'

interface Props {
  market: Market
  side: Side
  orderType: OrderType
  quantity: string
  price: string
}

interface Preview {
  market: boolean   // true = 시장가라 미리보기 불가
  amount?: number
  commission?: number
  tax?: number
  netBuy?: number
  netSell?: number
}

/**
 * 주문 비용 미리보기.
 *
 * 학습 포인트 — 왜 useMemo 인가:
 *   - quantity/price 는 string state. 매 렌더마다 parseFloat·곱셈 자체는 가볍지만,
 *     자식 row 컴포넌트에 객체 props 로 내려가므로 referential equality 유지를 위해 메모.
 *   - rates 는 deps 에 들어 있음. 동일 market 이면 객체 동일성 유지 (TAX_FEE 가 frozen).
 *   - market 자체가 deps 에 없는 이유: rates 가 derived value 라 market 변경이 자동 전파.
 *
 * 비용 모델:
 *   netBuy  = 대금 + 수수료      (KR: 0.015%, US: 0.25%, JP: 0.10%)
 *   netSell = 대금 - 수수료 - 세금 (KR: 0.18% 거래세, US: 0.00278% SEC fee, JP: 없음)
 *   ※ 환전수수료·양도소득세는 별도 — UI 하단 footnote 로 명시.
 */
export function CostPreview({ market, side, orderType, quantity, price }: Props) {
  const currency = findMarket(market).currency
  const rates = TAX_FEE[market]

  const preview = useMemo<Preview | null>(() => {
    const qty = parseFloat(quantity)
    const px = parseFloat(price)
    if (!qty || qty <= 0) return null
    if (orderType === 'MARKET' || !px || px <= 0) return { market: true }
    const amount = qty * px
    const commission = amount * rates.commissionRate / 100
    const tax = (rates.taxOn === side) ? amount * rates.taxRate / 100 : 0
    const netBuy = amount + commission
    const netSell = amount - commission - tax
    return { amount, commission, tax, netBuy, netSell, market: false }
  }, [side, orderType, quantity, price, rates])

  if (!preview) return null

  const row = (label: string, value: number, sign = '', color = '#374151') => (
    <div style={{
      display: 'flex', justifyContent: 'space-between',
      fontSize: '0.85rem', color, padding: '4px 0',
    }}>
      <span>{label}</span>
      <span style={{ fontWeight: 500 }}>{sign}{fmtMoney(value, currency)}</span>
    </div>
  )

  if (preview.market) {
    return (
      <div style={{
        background: '#F9FAFB', border: '1px solid #E5E7EB', borderRadius: 10,
        padding: 14, marginTop: 12, fontSize: '0.85rem', color: '#6B7280',
      }}>
        시장가 주문은 체결가가 정해지지 않아 예상 비용을 계산할 수 없습니다.
      </div>
    )
  }

  return (
    <div style={{
      background: '#F9FAFB', border: '1px solid #E5E7EB', borderRadius: 10,
      padding: 14, marginTop: 12,
    }}>
      <div style={{ fontSize: '0.8rem', fontWeight: 600, color: '#6B7280', marginBottom: 6 }}>
        예상 비용 (한투 온라인 기준 · 실제와 차이 있을 수 있음)
      </div>
      {row('주문 대금', preview.amount!)}
      {row(`수수료 (${rates.commissionRate}%)`, preview.commission!, '-', '#DC2626')}
      {rates.taxOn === side && rates.taxRate > 0 &&
        row(`${rates.taxLabel} (${rates.taxRate}%)`, preview.tax!, '-', '#DC2626')}
      <div style={{
        borderTop: '1px dashed #D1D5DB', marginTop: 6, paddingTop: 6,
        display: 'flex', justifyContent: 'space-between',
        fontSize: '0.92rem', fontWeight: 700,
      }}>
        <span>{side === 'BUY' ? '총 결제금액' : '순 체결금액'}</span>
        <span style={{ color: side === 'BUY' ? '#DC2626' : '#059669' }}>
          {fmtMoney(side === 'BUY' ? preview.netBuy! : preview.netSell!, currency)}
        </span>
      </div>
      {market !== 'KR_STOCK' && (
        <div style={{ fontSize: '0.72rem', color: '#9CA3AF', marginTop: 6 }}>
          ※ 환전수수료 · 연말 양도소득세(22%, 250만원 공제) 미포함
        </div>
      )}
    </div>
  )
}
