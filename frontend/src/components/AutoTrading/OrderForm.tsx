import { useEffect, useState, type FormEvent } from 'react'
import SymbolSearchInput from '../SymbolSearchInput'
import MoneyInput from '../../utils/MoneyInput'
import { useSubmitting } from '../../hooks/useSubmitting'
import { MARKETS, findMarket, type Market, type Side, type OrderType } from '../../domain/market'
import { tradingApi } from '../../api/trading'
import { CostPreview } from './CostPreview'

interface Props {
  paperTrading: boolean
  onOrdered?: () => void
}

interface SelectedSymbol { symbol: string; exchange?: string }
type Msg = { type: 'ok' | 'err'; text: string }

const SYMBOL_PLACEHOLDER: Record<Market, string> = {
  KR_STOCK: '삼성전자 / 005930',
  US_STOCK: 'Apple / AAPL',
  JP_STOCK: 'Toyota / 7203',
}

/**
 * 주문 폼.
 *
 * 핵심 가드 (학습 포인트):
 *   1. useSubmitting — 폼 더블 클릭/엔터 race 차단 (ref 기반 동기 락 + state 기반 UI disabled).
 *      서버에 같은 주문이 두 번 가는 사고를 클라이언트에서도 1차 봉쇄.
 *      서버 측 idempotency-key 와 짝을 이룰 때만 안전하지만, 일반 사용자 시나리오에서는
 *      네트워크 round-trip 전에 막는 1차 방어가 사용자 체감 안정성을 크게 올림.
 *   2. 실전 계좌 confirm — paperTrading=false 시 명시 동의 한 단계.
 *   3. 주문 성공 시 onOrdered() 콜백 → 컨테이너가 refreshKey 증가 → 잔고/미체결 자동 재조회.
 */
export function OrderForm({ paperTrading, onOrdered }: Props) {
  const [market, setMarket] = useState<Market>('KR_STOCK')
  const [selected, setSelected] = useState<SelectedSymbol | null>(null)
  const [exchange, setExchange] = useState('KRX')
  const [side, setSide] = useState<Side>('BUY')
  const [orderType, setOrderType] = useState<OrderType>('LIMIT')
  const [quantity, setQuantity] = useState('')
  const [price, setPrice] = useState('')
  const [msg, setMsg] = useState<Msg | null>(null)
  const { submitting, run } = useSubmitting()

  const m = findMarket(market)

  useEffect(() => {
    setExchange(m.exchanges[0])
    setSelected(null)
  }, [market]) // eslint-disable-line react-hooks/exhaustive-deps

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!selected || submitting) return
    if (!paperTrading) {
      const ok = window.confirm('실전 계좌로 주문이 나갑니다. 계속하시겠습니까?')
      if (!ok) return
    }
    setMsg(null)
    run(async () => {
      try {
        const data = await tradingApi.order({
          market,
          symbol: selected.symbol,
          exchange: market === 'KR_STOCK' ? null : (selected.exchange || exchange),
          side,
          orderType,
          quantity: parseInt(quantity, 10),
          price: orderType === 'LIMIT' ? price : undefined,
        })
        setMsg({
          type: data.success ? 'ok' : 'err',
          text: data.success ? `주문 접수: ${data.orderNo}` : (data.message ?? '주문 실패'),
        })
        if (data.success) {
          setQuantity(''); setPrice('')
          onOrdered?.()
        }
      } catch (err) {
        setMsg({ type: 'err', text: (err as Error).message })
      }
    })
  }

  return (
    <div className="form-card">
      <h3 style={{ marginTop: 0 }}>
        주문 {paperTrading && <span style={{ fontSize: '0.7em', color: '#D97706' }}>(모의투자)</span>}
      </h3>
      <form onSubmit={submit}>
        <div className="form-row">
          <div className="form-group">
            <label>시장</label>
            <select value={market} onChange={e => setMarket(e.target.value as Market)}>
              {MARKETS.map(x => <option key={x.value} value={x.value}>{x.label}</option>)}
            </select>
          </div>
          {m.exchanges.length > 1 && !selected && (
            <div className="form-group">
              <label>거래소</label>
              <select value={exchange} onChange={e => setExchange(e.target.value)}>
                {m.exchanges.map(x => <option key={x} value={x}>{x}</option>)}
              </select>
            </div>
          )}
          <div className="form-group" style={{ flex: 2 }}>
            <label>종목</label>
            <SymbolSearchInput
              market={market}
              selected={selected}
              onSelect={setSelected}
              onClear={() => setSelected(null)}
              placeholder={SYMBOL_PLACEHOLDER[market]}
            />
          </div>
        </div>
        <div className="form-row">
          <div className="form-group">
            <label>매수/매도</label>
            <select value={side} onChange={e => setSide(e.target.value as Side)}>
              <option value="BUY">매수</option>
              <option value="SELL">매도</option>
            </select>
          </div>
          <div className="form-group">
            <label>주문 유형</label>
            <select value={orderType} onChange={e => setOrderType(e.target.value as OrderType)}>
              <option value="LIMIT">지정가</option>
              <option value="MARKET">시장가</option>
            </select>
          </div>
          <div className="form-group">
            <label>수량</label>
            <MoneyInput value={quantity} onChange={setQuantity} required />
          </div>
          {orderType === 'LIMIT' && (
            <div className="form-group">
              <label>가격 ({m.currency})</label>
              <MoneyInput value={price} onChange={setPrice} required allowDecimal={m.currency !== 'KRW'} />
            </div>
          )}
        </div>

        <CostPreview market={market} side={side} orderType={orderType}
          quantity={quantity} price={price} />

        <button type="submit" className="btn-submit"
          disabled={submitting || !selected}
          style={{ background: side === 'BUY' ? '#DC2626' : '#2563EB', marginTop: 14 }}>
          {submitting ? '전송 중...' : `${side === 'BUY' ? '매수' : '매도'} 주문 전송`}
        </button>
      </form>
      {msg && (
        <div className={msg.type === 'ok' ? 'success-msg' : 'error-msg'}
          style={{ marginTop: 12,
            background: msg.type === 'ok' ? '#ECFDF5' : '#FEF2F2',
            color: msg.type === 'ok' ? '#059669' : '#DC2626',
            padding: '10px 14px', borderRadius: 10 }}>
          {msg.text}
        </div>
      )}
    </div>
  )
}
