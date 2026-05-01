import { useTradingBalance } from '../../hooks/useTradingBalance'
import { fmtMoney } from '../../utils/formatters'
import { MARKETS, findMarket, type Market } from '../../domain/market'

interface Props { refreshKey: number }

/**
 * 잔고 패널. 시장 select + 새로고침 + 보유 종목 그리드.
 * 데이터 fetching 은 useTradingBalance 훅으로 분리 — 이 컴포넌트는 표시 책임만.
 */
export function BalancePanel({ refreshKey }: Props) {
  const { balance, loading, error, market, setMarket, reload } = useTradingBalance(refreshKey)
  const curr = findMarket(market).currency

  return (
    <div className="form-card">
      <div className="section-header">
        <h3 style={{ margin: 0 }}>잔고</h3>
        <div style={{ display: 'flex', gap: 8 }}>
          <select value={market} onChange={e => setMarket(e.target.value as Market)} style={{ width: 'auto' }}>
            {MARKETS.map(x => <option key={x.value} value={x.value}>{x.label}</option>)}
          </select>
          <button type="button" className="btn-refresh" onClick={reload}>새로고침</button>
        </div>
      </div>

      {loading && <p style={{ color: 'var(--tx-2)' }}>조회 중...</p>}
      {error && (
        error.dependencyMissing ? (
          // 의존성 미설정 — 운영자 안내성 메시지. 토스트가 아니라 inline 배너 1회.
          <div className="info-banner" style={{
            background: 'var(--warn-soft)', color: 'var(--warn)', padding: '10px 14px',
            borderRadius: 8, fontSize: '0.85rem', marginTop: 8,
          }}>
            ⚠ {error.message}
          </div>
        ) : (
          <div className="error-msg">{error.message}</div>
        )
      )}
      {balance && (
        <>
          <div className="risk-grid" style={{ marginTop: 12 }}>
            <div className="risk-card">
              <div className="risk-label">예수금</div>
              <div className="risk-value">{fmtMoney(balance.deposit, curr)}</div>
            </div>
            <div className="risk-card">
              <div className="risk-label">평가금액</div>
              <div className="risk-value">{fmtMoney(balance.totalEvalAmount, curr)}</div>
            </div>
            <div className="risk-card">
              <div className="risk-label">손익</div>
              <div className="risk-value" style={{ color: parseFloat(balance.totalPnl) >= 0 ? 'var(--up)' : 'var(--down)' }}>
                {fmtMoney(balance.totalPnl, curr)}
              </div>
            </div>
            <div className="risk-card">
              <div className="risk-label">수익률</div>
              <div className="risk-value" style={{ color: parseFloat(balance.totalPnlRate) >= 0 ? 'var(--up)' : 'var(--down)' }}>
                {parseFloat(balance.totalPnlRate || '0').toFixed(2)}%
              </div>
            </div>
          </div>

          {balance.holdings && balance.holdings.length > 0 ? (
            <div className="asset-perf-table" style={{ marginTop: 16 }}>
              <div className="asset-perf-header" style={{ gridTemplateColumns: '2fr 1fr 1fr 1fr 1fr 1fr' }}>
                <span>종목</span><span>수량</span><span>평균단가</span><span>현재가</span>
                <span>평가금액</span><span>손익</span>
              </div>
              {balance.holdings.map((h, i) => (
                <div key={i} className="asset-perf-row"
                  style={{ gridTemplateColumns: '2fr 1fr 1fr 1fr 1fr 1fr' }}>
                  <span>
                    <strong>{h.symbol}</strong>
                    {h.name && <span style={{ color: 'var(--tx-2)', marginLeft: 8 }}>{h.name}</span>}
                  </span>
                  <span>{h.quantity.toLocaleString()}</span>
                  <span>{fmtMoney(h.avgPrice, curr)}</span>
                  <span>{fmtMoney(h.currentPrice, curr)}</span>
                  <span>{fmtMoney(h.evalAmount, curr)}</span>
                  <span style={{ color: parseFloat(h.pnl) >= 0 ? 'var(--up)' : 'var(--down)', fontWeight: 700 }}>
                    {parseFloat(h.pnlRate || '0').toFixed(2)}%
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <p style={{ color: 'var(--tx-2)', marginTop: 12 }}>보유 종목이 없습니다.</p>
          )}
        </>
      )}
    </div>
  )
}
