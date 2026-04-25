import { useEffect, useState, type FormEvent } from 'react'
import SymbolSearchInput from '../SymbolSearchInput'
import { fmtDelta, fmtMoney } from '../../utils/formatters'
import { MARKETS, findMarket, type Market } from '../../domain/market'
import type { Quote } from '../../api/trading'

interface Props {
  quotes: Quote[]
  connected: boolean
  onAdd: (req: { market: Market; code: string; exchange: string }) => void
  onRemove: (q: Quote) => void
  onRefresh: () => void
}

interface SelectedSymbol { symbol: string; exchange?: string }

const PLACEHOLDER: Record<Market, string> = {
  KR_STOCK: '삼성전자 또는 005930',
  US_STOCK: 'Apple 또는 AAPL',
  JP_STOCK: 'Toyota 또는 7203',
}

/**
 * 감시 종목 추가 폼 + 실시간 가격 테이블.
 *
 * market 변경 시 exchange 와 selected 를 reset — useEffect deps 에 m 은 빠져 있는데
 * m = findMarket(market) 의 derived 값이라 market 만 바뀌면 충분. (의도적으로 disable)
 */
export function WatchlistPanel({ quotes, connected, onAdd, onRemove, onRefresh }: Props) {
  const [market, setMarket] = useState<Market>('KR_STOCK')
  const [selected, setSelected] = useState<SelectedSymbol | null>(null)
  const [exchange, setExchange] = useState('KRX')
  const m = findMarket(market)

  useEffect(() => {
    setExchange(m.exchanges[0])
    setSelected(null)
  }, [market]) // eslint-disable-line react-hooks/exhaustive-deps

  const add = (e: FormEvent) => {
    e.preventDefault()
    if (!selected) return
    const ex = selected.exchange || exchange
    onAdd({ market, code: selected.symbol, exchange: ex })
    setSelected(null)
  }

  return (
    <div className="form-card">
      <div className="live-status-bar">
        <div className="live-status">
          <span className={`live-dot ${connected ? 'connected' : 'disconnected'}`} />
          <span>{connected ? '실시간 연결' : '연결 중...'}</span>
        </div>
        <button type="button" className="btn-refresh" onClick={onRefresh}>새로고침</button>
      </div>

      <form className="live-add-form" onSubmit={add}
        style={{ display: 'grid', gridTemplateColumns: '100px 1fr auto', gap: 8, alignItems: 'center' }}>
        <select value={market} onChange={e => setMarket(e.target.value as Market)}
          style={{ padding: '10px 12px', borderRadius: 10, border: '1.5px solid #e5e7eb', fontSize: '0.85rem' }}>
          {MARKETS.map(x => <option key={x.value} value={x.value}>{x.label}</option>)}
        </select>
        <SymbolSearchInput
          market={market}
          selected={selected}
          onSelect={setSelected}
          onClear={() => setSelected(null)}
          placeholder={PLACEHOLDER[market]}
        />
        <button type="submit" className="live-add-btn" disabled={!selected}>추가</button>
      </form>

      {quotes.length > 0 ? (
        <div className="live-table">
          <div className="live-table-header">
            <span>종목</span><span>현재가</span><span>변동</span><span>변동률</span><span></span>
          </div>
          {quotes.map(p => {
            const pos = parseFloat(p.change) >= 0
            const color = pos ? '#059669' : '#dc2626'
            return (
              <div key={p.symbol + p.currency} className={`live-table-row ${p.flash ? 'flash' : ''}`}>
                <span className="live-stock-info">
                  <span className="live-symbol">{p.symbol}</span>
                  <span className="live-name">{p.name}</span>
                </span>
                <span className="live-price">{fmtMoney(p.price, p.currency)}</span>
                <span style={{ color, fontWeight: 600 }}>{fmtDelta(p.change, p.currency)}</span>
                <span className="live-change-badge"
                  style={{ background: pos ? '#ECFDF5' : '#FEF2F2', color }}>
                  {pos ? '+' : ''}{parseFloat(p.changePercent).toFixed(2)}%
                </span>
                <button className="live-remove-btn" onClick={() => onRemove(p)}>&times;</button>
              </div>
            )
          })}
        </div>
      ) : (
        <div className="loading" style={{ padding: '30px 20px' }}>
          <p>감시 종목을 추가하면 실시간 가격이 표시됩니다.</p>
        </div>
      )}
    </div>
  )
}
