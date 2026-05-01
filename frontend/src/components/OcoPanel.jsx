import { useEffect, useState } from 'react'
import SymbolSearchInput from './SymbolSearchInput'
import { pushToast } from '../toast'
import { fmtQty, fmtInt } from '../utils/formatters'
import MoneyInput from '../utils/MoneyInput'
import { tradingApi, ApiError } from '../api/trading'

const DEFAULT_TP = [
  { percent: 2, quantityFraction: 0.33 },
  { percent: 4, quantityFraction: 0.33 },
  { percent: 6, quantityFraction: 0.34 },
]

const STATUS_LABEL = {
  PENDING_ENTRY: '진입 대기',
  PENDING_FILL: '체결 대기',
  ACTIVE: '감시 중',
  PARTIALLY_CLOSED: '일부 청산',
  CLOSED: '청산 완료',
  CANCELLED: '취소됨',
  FAILED: '실패',
}

const STATUS_COLOR = {
  PENDING_ENTRY: '#7C3AED',
  PENDING_FILL: '#D97706',
  ACTIVE: 'var(--up)',
  PARTIALLY_CLOSED: '#2563EB',
  CLOSED: 'var(--tx-2)',
  CANCELLED: 'var(--tx-3)',
  FAILED: 'var(--down)',
}

const ENTRY_TYPE_LABEL = {
  LIMIT: '즉시 지정가',
  MARKET: '즉시 시장가',
  BREAKOUT_ABOVE: '돌파 매수 (▲)',
  BREAKOUT_BELOW: '하락 매수 (▼)',
  EXISTING_HOLDING: '이미 보유 중',
}

// ===================== 익절 단계 섹션 =====================

function TakeProfitLegRow({ idx, leg, onChange, onRemove, totalQty }) {
  const plannedShares = totalQty > 0
    ? Math.round(totalQty * leg.quantityFraction)
    : null

  return (
    <div style={{
      display: 'grid',
      gridTemplateColumns: '36px 1fr 1fr auto',
      gap: 12,
      alignItems: 'center',
      padding: '10px 12px',
      background: 'var(--bg-2)',
      border: '1px solid #E5E7EB',
      borderRadius: 10,
      marginBottom: 8,
    }}>
      <div style={{
        width: 32, height: 32, borderRadius: '50%',
        background: '#ECFDF5', color: 'var(--up)',
        fontWeight: 700, fontSize: '0.85rem',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        {idx + 1}
      </div>

      <div>
        <div style={legLabel}>목표 상승률</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <span style={{ color: 'var(--up)', fontWeight: 600 }}>+</span>
          <input type="number" step="0.1" min="0.1"
            value={leg.percent}
            onChange={e => onChange(idx, 'percent', e.target.value)}
            style={legInput} />
          <span style={{ color: 'var(--tx-2)', fontSize: '0.9rem' }}>%</span>
        </div>
      </div>

      <div>
        <div style={legLabel}>
          매도 비율
          {plannedShares !== null && (
            <span style={{ color: 'var(--tx-3)', marginLeft: 6 }}>
              ≈ {plannedShares}주
            </span>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <input type="number" step="0.01" min="0" max="1"
            value={leg.quantityFraction}
            onChange={e => onChange(idx, 'quantityFraction', e.target.value)}
            style={legInput} />
          <span style={{ color: 'var(--tx-2)', fontSize: '0.9rem' }}>
            ({(leg.quantityFraction * 100).toFixed(0)}%)
          </span>
        </div>
      </div>

      <button type="button" onClick={() => onRemove(idx)}
        title="단계 삭제"
        style={{
          width: 32, height: 32, borderRadius: 8,
          background: 'transparent', border: '1px solid #E5E7EB',
          color: 'var(--tx-3)', cursor: 'pointer', fontSize: '1rem',
        }}>
        ×
      </button>
    </div>
  )
}

const legLabel = {
  fontSize: '0.72rem', color: 'var(--tx-2)', marginBottom: 4,
  letterSpacing: '0.02em', textTransform: 'uppercase',
}
const legInput = {
  width: 72, padding: '4px 8px',
  border: '1px solid #D1D5DB', borderRadius: 6,
  fontSize: '0.95rem', fontWeight: 600,
  color: '#111827',
}

function TakeProfitSection({ tp, setTp, totalQty }) {
  const updateLeg = (i, field, val) => {
    const copy = [...tp]
    copy[i] = { ...copy[i], [field]: parseFloat(val) || 0 }
    setTp(copy)
  }
  const removeLeg = (i) => setTp(tp.filter((_, idx) => idx !== i))
  const addLeg = () => setTp([...tp, { percent: 8, quantityFraction: 0 }])
  const resetDefault = () => setTp(DEFAULT_TP)

  const fractionSum = tp.reduce((s, l) => s + l.quantityFraction, 0)
  const sumValid = Math.abs(fractionSum - 1) <= 0.001

  return (
    <div style={{
      marginTop: 16,
      padding: 16,
      background: 'linear-gradient(180deg, #F0FDF4 0%, #F9FAFB 100%)',
      border: '1px solid #D1FAE5',
      borderRadius: 12,
    }}>
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        marginBottom: 12,
      }}>
        <div>
          <div style={{ fontWeight: 700, fontSize: '1rem', color: 'var(--ok)' }}>
            익절 단계
          </div>
          <div style={{ fontSize: '0.78rem', color: 'var(--tx-2)', marginTop: 2 }}>
            지정한 상승률 도달 시 해당 수량만큼 시장가 매도
          </div>
        </div>
        <div style={{
          padding: '6px 12px', borderRadius: 999,
          background: sumValid ? '#D1FAE5' : 'var(--up-soft)',
          color: sumValid ? 'var(--ok)' : '#B91C1C',
          fontSize: '0.8rem', fontWeight: 600,
        }}>
          합계 {(fractionSum * 100).toFixed(0)}% {sumValid && '✓'}
        </div>
      </div>

      {tp.map((leg, i) => (
        <TakeProfitLegRow key={i} idx={i} leg={leg} totalQty={totalQty}
          onChange={updateLeg} onRemove={removeLeg} />
      ))}

      <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
        <button type="button" onClick={addLeg} style={btnGhost}>+ 단계 추가</button>
        <button type="button" onClick={resetDefault} style={btnGhost}>기본값 복원</button>
      </div>
    </div>
  )
}

const btnGhost = {
  padding: '8px 14px', fontSize: '0.85rem',
  background: 'var(--bg-2)', color: 'var(--tx-0)',
  border: '1px solid #D1D5DB', borderRadius: 8,
  cursor: 'pointer', fontWeight: 500,
}

// ===================== 보유 종목 피커 (EXISTING_HOLDING 모드) =====================

function HoldingsPicker({ holdings, loading, error, onRefresh, onPick, selectedSymbol }) {
  return (
    <div style={{
      marginBottom: 14,
      padding: 14,
      background: '#F0F9FF',
      border: '1px solid #BAE6FD',
      borderRadius: 12,
    }}>
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        marginBottom: 8,
      }}>
        <div>
          <div style={{ fontWeight: 700, color: 'var(--accent)' }}>내 보유 종목 (국내)</div>
          <div style={{ fontSize: '0.78rem', color: 'var(--tx-2)', marginTop: 2 }}>
            선택 시 종목 · 수량이 자동 입력됩니다.
          </div>
        </div>
        <button type="button" onClick={onRefresh} disabled={loading} style={btnGhost}>
          {loading ? '조회 중...' : '🔄 새로고침'}
        </button>
      </div>

      {error && (
        // 의존성 미설정/일반 오류 모두 inline 으로. 자동 reload 로 토스트 폭발했던 기존 동작 대체.
        <div style={{
          background: error.dependencyMissing ? 'var(--warn-soft)' : 'var(--up-soft)',
          color: error.dependencyMissing ? '#92400E' : 'var(--up)',
          padding: '10px 14px', borderRadius: 8, fontSize: '0.85rem',
        }}>
          {error.dependencyMissing ? '⚠ ' : '✗ '}{error.message}
        </div>
      )}
      {!error && loading && holdings == null && (
        <div style={{ color: 'var(--tx-3)', padding: 12 }}>잔고 불러오는 중...</div>
      )}
      {!error && holdings != null && holdings.length === 0 && (
        <div style={{ color: 'var(--tx-3)', padding: 12 }}>
          보유 중인 국내 주식이 없습니다.
        </div>
      )}
      {holdings && holdings.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, maxHeight: 280, overflowY: 'auto' }}>
          {holdings.map(h => {
            const active = h.symbol === selectedSymbol
            const pnlColor = Number(h.pnl) >= 0 ? 'var(--up)' : 'var(--down)'
            return (
              <button
                type="button"
                key={h.symbol}
                onClick={() => onPick(h)}
                style={{
                  display: 'grid',
                  gridTemplateColumns: '1.8fr 0.8fr 1fr 1fr',
                  gap: 10,
                  alignItems: 'center',
                  padding: '10px 12px',
                  background: active ? '#E0F2FE' : 'var(--bg-2)',
                  border: active ? '2px solid #0284C7' : '1px solid #E5E7EB',
                  borderRadius: 8,
                  cursor: 'pointer',
                  fontFamily: 'inherit',
                  fontSize: '0.85rem',
                  textAlign: 'left',
                }}
              >
                <div>
                  <div style={{ fontWeight: 700 }}>{h.name || h.symbol}</div>
                  <div style={{ color: 'var(--tx-2)', fontSize: '0.75rem', fontFamily: 'monospace' }}>
                    {h.symbol}
                  </div>
                </div>
                <div style={{ color: 'var(--tx-0)' }}>{fmtQty(h.quantity, '주')}</div>
                <div style={{ color: 'var(--tx-2)' }}>
                  평단 {fmtInt(h.avgPrice)}
                </div>
                <div style={{ color: pnlColor, textAlign: 'right', fontWeight: 600 }}>
                  {Number(h.pnlRate) >= 0 ? '+' : ''}{Number(h.pnlRate).toFixed(2)}%
                </div>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}

// ===================== 등록 폼 =====================

function OrderForm({ onRegistered }) {
  const [selected, setSelected] = useState(null)
  const [quantity, setQuantity] = useState('')
  const [entryType, setEntryType] = useState('LIMIT')
  const [entryPrice, setEntryPrice] = useState('')
  const [triggerPrice, setTriggerPrice] = useState('')
  const [stopLoss, setStopLoss] = useState('-2.0')
  const [tp, setTp] = useState(DEFAULT_TP)
  const [busy, setBusy] = useState(false)

  // EXISTING_HOLDING 모드용
  const [holdings, setHoldings] = useState(null)      // null = not loaded yet, [] = loaded empty
  const [holdingsLoading, setHoldingsLoading] = useState(false)
  const [holdingsError, setHoldingsError] = useState(null)  // {message, dependencyMissing}
  const [pickedAvgPrice, setPickedAvgPrice] = useState(null)

  const isConditional = entryType === 'BREAKOUT_ABOVE' || entryType === 'BREAKOUT_BELOW'
  const isExistingHolding = entryType === 'EXISTING_HOLDING'
  const fractionSum = tp.reduce((s, l) => s + l.quantityFraction, 0)
  const totalQty = parseInt(quantity, 10) || 0

  // entryType 변경시에만 reset + 조건부 holdings 로드.
  // holdings/isExistingHolding 은 entryType 에서 파생되므로 deps 에 넣지 않는다 (그렇지 않으면
  // holdings 응답이 도착할 때마다 entryPrice 가 다시 비워져 사용자 입력이 사라짐).
  useEffect(() => {
    setEntryPrice(''); setTriggerPrice('')
    if (isExistingHolding && holdings == null) loadHoldings()
  }, [entryType]) // eslint-disable-line react-hooks/exhaustive-deps

  const loadHoldings = async () => {
    setHoldingsLoading(true); setHoldingsError(null)
    try {
      const data = await tradingApi.balance('KR_STOCK')
      setHoldings(data.holdings || [])
    } catch (e) {
      // 503 (DEPENDENCY_NOT_CONFIGURED) 은 토스트로 띄우지 않는다 — 환경변수 안내성이라
      // entryType 변경/주문 후 재로드 때마다 토스트가 누적되면 noisy. inline 배너로 1회만.
      const isApiErr = e instanceof ApiError
      const dependencyMissing = isApiErr && e.isDependencyMissing()
      setHoldingsError({ message: e.message, dependencyMissing })
      setHoldings([])
      if (!dependencyMissing) pushToast(e.message, { type: 'error' })
    } finally { setHoldingsLoading(false) }
  }

  const pickHolding = (h) => {
    setSelected({ symbol: h.symbol, name: h.name, market: 'KR_STOCK' })
    setQuantity(String(h.quantity))
    setPickedAvgPrice(h.avgPrice)
    setEntryPrice('') // 사용자가 별도 기준가 입력할 수 있음; 빈값이면 평단 사용
  }

  const entryDescription = () => {
    if (entryType === 'LIMIT') return `${fmtInt(entryPrice)}원 지정가 즉시 매수`
    if (entryType === 'MARKET') return '시장가 즉시 매수'
    if (entryType === 'BREAKOUT_ABOVE') return `${fmtInt(triggerPrice)}원 돌파 시 시장가 매수`
    if (entryType === 'BREAKOUT_BELOW') return `${fmtInt(triggerPrice)}원 이하 하락 시 ${fmtInt(triggerPrice)}원 지정가 매수`
    if (entryType === 'EXISTING_HOLDING') {
      const ref = entryPrice || pickedAvgPrice
      return `매수 없이 ${ref ? fmtInt(ref) + '원' : '평단가'} 기준으로 바로 감시 시작`
    }
    return ''
  }

  const submit = async (e) => {
    e.preventDefault()
    if (!selected) return
    if (Math.abs(fractionSum - 1) > 0.001) {
      pushToast(`익절 매도 비율 합이 100% 이어야 합니다 (현재 ${(fractionSum * 100).toFixed(0)}%)`,
        { type: 'error' })
      return
    }
    const header = isExistingHolding
      ? '보유 주식에 손절/익절 조건을 등록합니다 (매수 주문 없음).'
      : '실전 계좌 주문입니다.'
    const ok = window.confirm(
      `${header}\n${selected.symbol} ${fmtQty(quantity, '주')}\n${entryDescription()}\n손절 ${stopLoss}% / 익절 ${tp.length}단계\n계속할까요?`
    )
    if (!ok) return

    setBusy(true)
    try {
      // entryPrice 결정: LIMIT=필수, EXISTING_HOLDING=선택(빈값 OK → 평단 사용), 그 외=null
      const entryPriceSend =
        entryType === 'LIMIT' ? entryPrice
        : (entryType === 'EXISTING_HOLDING' && entryPrice) ? entryPrice
        : null
      const res = await fetch('/api/trading/oco', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          symbol: selected.symbol,
          quantity: parseInt(quantity, 10),
          entryType,
          entryPrice: entryPriceSend,
          triggerPrice: isConditional ? triggerPrice : null,
          stopLossPercent: parseFloat(stopLoss),
          takeProfit: tp,
        }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || '등록 실패')
      pushToast(`등록됨 — ${data.symbol} ${data.totalQuantity}주`, { type: 'success' })
      setQuantity(''); setEntryPrice(''); setTriggerPrice(''); setPickedAvgPrice(null)
      if (isExistingHolding) loadHoldings() // 잔고 갱신
      onRegistered?.()
    } catch (err) {
      pushToast(err.message, { type: 'error' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="form-card">
      <div style={{ marginBottom: 4 }}>
        <h3 style={{ margin: 0 }}>자동매매 주문 등록</h3>
        <p style={{ color: 'var(--tx-2)', fontSize: '0.85rem', margin: '4px 0 16px' }}>
          국내 단타 전용. 본주문 체결 확인 후 실시간 감시 → 손절가/익절가 도달 시 자동 시장가 매도.
          <br />
          <span style={{ color: 'var(--tx-3)' }}>
            ※ 신규 매수 모드는 <b>체결 평균가</b>, <b>이미 보유 중</b> 모드는 KIS 잔고 <b>평단가(또는 입력한 기준가)</b> 기준으로 손절/익절가 계산.
          </span>
        </p>
      </div>

      <form onSubmit={submit}>
        {/* 본주문 방식을 위로 올림 — 모드에 따라 아래 UI가 달라짐 */}
        <div className="form-row">
          <div className="form-group" style={{ flex: 2 }}>
            <label>모드</label>
            <select value={entryType} onChange={e => setEntryType(e.target.value)}>
              <option value="LIMIT">즉시 지정가 매수 + 감시</option>
              <option value="MARKET">즉시 시장가 매수 + 감시</option>
              <option value="BREAKOUT_ABOVE">돌파 매수 (가격 ▲ 도달 시)</option>
              <option value="BREAKOUT_BELOW">하락 매수 (가격 ▼ 도달 시)</option>
              <option value="EXISTING_HOLDING">이미 보유 중 — 매수 건너뛰고 감시만</option>
            </select>
          </div>
        </div>

        {/* EXISTING_HOLDING 모드: 보유 종목 피커 */}
        {isExistingHolding && (
          <HoldingsPicker
            holdings={holdings}
            loading={holdingsLoading}
            error={holdingsError}
            onRefresh={loadHoldings}
            onPick={pickHolding}
            selectedSymbol={selected?.symbol}
          />
        )}

        {/* 종목/수량 */}
        <div className="form-row">
          <div className="form-group" style={{ flex: 2 }}>
            <label>종목 (국내)</label>
            {isExistingHolding ? (
              <input
                type="text"
                value={selected ? `${selected.name || ''} (${selected.symbol})` : ''}
                placeholder="위 보유 목록에서 선택"
                readOnly
                style={{ background: 'var(--bg-3)', color: 'var(--tx-0)' }}
              />
            ) : (
              <SymbolSearchInput market="KR_STOCK" selected={selected}
                onSelect={setSelected} onClear={() => setSelected(null)}
                placeholder="삼성전자 / 005930" />
            )}
          </div>
          <div className="form-group">
            <label>수량 (주)</label>
            <MoneyInput value={quantity} onChange={setQuantity} required />
          </div>
        </div>

        {/* 가격 필드 — 모드별 */}
        <div className="form-row">
          {entryType === 'LIMIT' && (
            <div className="form-group">
              <label>본주문 가격 (원)</label>
              <MoneyInput value={entryPrice} onChange={setEntryPrice} suffix="원" required />
            </div>
          )}
          {isConditional && (
            <div className="form-group">
              <label>{entryType === 'BREAKOUT_ABOVE' ? '돌파 기준가 (원)' : '하락 기준가 (원)'}</label>
              <MoneyInput value={triggerPrice} onChange={setTriggerPrice} suffix="원" required />
            </div>
          )}
          {isExistingHolding && (
            <div className="form-group" style={{ flex: 1 }}>
              <label>
                손절/익절 기준가 (원) <span style={{ color: 'var(--tx-3)', fontWeight: 400, fontSize: '0.78rem' }}>
                  비워두면 평단가 {pickedAvgPrice ? `(${fmtInt(pickedAvgPrice)})` : ''} 사용
                </span>
              </label>
              <MoneyInput value={entryPrice} onChange={setEntryPrice} suffix="원"
                placeholder={pickedAvgPrice ? fmtInt(Math.round(pickedAvgPrice)) : ''} />
            </div>
          )}
        </div>

        {isConditional && (
          <div style={{
            marginTop: 8, padding: '10px 14px', borderRadius: 8,
            background: '#F3E8FF', color: '#6B21A8', fontSize: '0.85rem',
          }}>
            {entryType === 'BREAKOUT_ABOVE'
              ? `감시 중: 실시간 체결가 ≥ ${triggerPrice || '...'}원 되면 시장가 매수. 예수금 부족 시 자동 재시도.`
              : `감시 중: 실시간 체결가 ≤ ${triggerPrice || '...'}원 되면 ${triggerPrice || '...'}원 지정가 매수. 예수금 부족 시 자동 재시도.`}
          </div>
        )}

        {/* 손절 섹션 */}
        <div style={{
          marginTop: 16, padding: 16,
          background: 'linear-gradient(180deg, #FEF2F2 0%, #F9FAFB 100%)',
          border: '1px solid #FECACA', borderRadius: 12,
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div>
              <div style={{ fontWeight: 700, fontSize: '1rem', color: 'var(--up)' }}>
                손절 기준
              </div>
              <div style={{ fontSize: '0.78rem', color: 'var(--tx-2)', marginTop: 2 }}>
                평단가 대비 하락률. 전량 시장가 매도.
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <input type="number" value={stopLoss} step="0.1" max="-0.1" required
                onChange={e => setStopLoss(e.target.value)}
                style={{
                  width: 88, padding: '8px 12px',
                  border: '1px solid #FECACA', borderRadius: 8,
                  fontSize: '1rem', fontWeight: 700,
                  color: 'var(--up)', textAlign: 'right',
                  background: 'var(--bg-2)',
                }} />
              <span style={{ color: 'var(--up)', fontWeight: 600 }}>%</span>
            </div>
          </div>
        </div>

        {/* 익절 섹션 */}
        <TakeProfitSection tp={tp} setTp={setTp} totalQty={totalQty} />

        <button type="submit" className="btn-submit"
          disabled={busy || !selected || !quantity}
          style={{
            background: isExistingHolding ? '#0284C7' : '#7C3AED',
            marginTop: 18,
          }}>
          {busy ? '등록 중...'
            : isExistingHolding ? '감시 시작 (매수 없음)'
            : '주문 등록 · 감시 시작'}
        </button>
      </form>
    </div>
  )
}

// ===================== 포지션 리스트 =====================

function PositionsList({ positions, onCancel }) {
  if (!positions.length) {
    return <div className="form-card" style={{ color: 'var(--tx-3)', textAlign: 'center', padding: 32 }}>
      등록된 포지션이 없습니다.
    </div>
  }
  return (
    <div className="form-card">
      <h3 style={{ marginTop: 0 }}>활성 포지션 ({positions.length})</h3>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.88rem' }}>
          <thead>
            <tr style={{ background: 'var(--bg-3)', textAlign: 'left' }}>
              <th style={th}>종목</th>
              <th style={th}>상태</th>
              <th style={th}>진입 방식</th>
              <th style={th}>총/잔여</th>
              <th style={th}>평단</th>
              <th style={th}>손절가</th>
              <th style={th}>익절 단계</th>
              <th style={th}>액션</th>
            </tr>
          </thead>
          <tbody>
            {positions.map(p => (
              <tr key={p.id} style={{ borderBottom: '1px solid #E5E7EB' }}>
                <td style={td}><b>{p.symbol}</b></td>
                <td style={td}>
                  <span style={{ color: STATUS_COLOR[p.status], fontWeight: 600 }}>
                    {STATUS_LABEL[p.status] || p.status}
                  </span>
                  {p.closeReason && <div style={{ fontSize: '0.75rem', color: 'var(--tx-3)' }}>{p.closeReason}</div>}
                  {p.lastBuyFailReason && p.status === 'PENDING_ENTRY' && (
                    <div style={{ fontSize: '0.72rem', color: 'var(--down)', marginTop: 2 }}>
                      {p.lastBuyFailReason} {p.buyAttempts > 0 && `(${p.buyAttempts}회 재시도)`}
                    </div>
                  )}
                </td>
                <td style={td}>
                  <div style={{ fontSize: '0.82rem' }}>{ENTRY_TYPE_LABEL[p.entryType] || p.entryType}</div>
                  {p.triggerPrice && (
                    <div style={{ fontSize: '0.75rem', color: 'var(--tx-2)' }}>
                      트리거 {Number(p.triggerPrice).toLocaleString()}원
                    </div>
                  )}
                </td>
                <td style={td}>{fmtQty(p.totalQuantity)} / {fmtQty(p.remainingQuantity)}</td>
                <td style={td}>{p.entryPrice ? Number(p.entryPrice).toLocaleString() : '-'}</td>
                <td style={td}>{p.stopLossPrice ? Number(p.stopLossPrice).toLocaleString() : '-'}</td>
                <td style={td}>
                  {p.takeProfit?.map((l, i) => (
                    <div key={i} style={{
                      fontSize: '0.78rem',
                      color: l.triggered ? 'var(--up)' : 'var(--tx-2)',
                    }}>
                      +{l.percent}% {l.triggerPrice && `@${fmtInt(l.triggerPrice)}`}
                      {' '}({fmtQty(l.executedQuantity)}/{fmtQty(l.plannedQuantity)}) {l.triggered && '✓'}
                    </div>
                  ))}
                </td>
                <td style={td}>
                  {(p.status === 'PENDING_FILL' || p.status === 'ACTIVE' || p.status === 'PARTIALLY_CLOSED') && (
                    <button onClick={() => onCancel(p.id)}
                      style={{
                        padding: '4px 8px', fontSize: '0.8rem',
                        background: 'var(--up-soft)', color: '#B91C1C', border: 'none',
                        borderRadius: 4, cursor: 'pointer',
                      }}>
                      취소
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

const th = { padding: '8px', borderBottom: '2px solid #E5E7EB', fontWeight: 600 }
const td = { padding: '8px', verticalAlign: 'top' }

// ===================== 최상위 =====================

export default function OcoPanel() {
  const [positions, setPositions] = useState([])

  const load = async () => {
    try {
      const res = await fetch('/api/trading/oco')
      if (res.ok) setPositions(await res.json())
    } catch { /* swallow */ }
  }

  useEffect(() => {
    load()
    const id = setInterval(load, 5000)
    return () => clearInterval(id)
  }, [])

  const cancel = async (id) => {
    if (!window.confirm('이 포지션을 취소합니다. 계속할까요?')) return
    try {
      const res = await fetch(`/api/trading/oco/${id}`, { method: 'DELETE' })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || '취소 실패')
      pushToast('취소됨', { type: 'success' })
      load()
    } catch (e) {
      pushToast(e.message, { type: 'error' })
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <OrderForm onRegistered={load} />
      <PositionsList positions={positions} onCancel={cancel} />
    </div>
  )
}
