import { useEffect, useState } from 'react'
import { pushToast } from '../toast'
import { fmtQty } from '../utils/formatters'
import MoneyInput from '../utils/MoneyInput'
import ExecutionStream from './ExecutionStream'

const STRATEGY_COLORS = {
  VAA: '#7C3AED',
  DAA: '#2563EB',
  LAA: '#0D9488',
  DUAL_MOMENTUM: 'var(--down)',
  HALLOWEEN: '#D97706',
  ARIRANG_FACTOR_ROTATION: '#EA580C',
  SPAC_EVENT_DRIVEN: '#B45309',
  // 계층 B 팩터 전략
  MAGIC_FORMULA: '#BE185D',
  SUPER_VALUE: '#0369A1',
  SUPER_QUALITY: '#047857',
  ULTRA: '#7C2D12',
  F_SCORE: '#4338CA',
}

const GROUP_A_TYPES = new Set(['VAA', 'DAA', 'LAA', 'DUAL_MOMENTUM', 'HALLOWEEN', 'ARIRANG_FACTOR_ROTATION', 'SPAC_EVENT_DRIVEN'])
const GROUP_B_TYPES = new Set(['MAGIC_FORMULA', 'SUPER_VALUE', 'SUPER_QUALITY', 'ULTRA', 'F_SCORE'])

const fmtKrw = (n) => {
  if (n == null) return '-'
  return '₩' + Number(n).toLocaleString('ko-KR', { maximumFractionDigits: 0 })
}

// ===================== 상단 전략 선택 =====================

function StrategyCard({ strat, selected, onClick }) {
  const color = STRATEGY_COLORS[strat.type] || '#1a1d26'
  return (
    <button
      onClick={onClick}
      className={`kang-strat-card ${selected ? 'selected' : ''}`}
      style={{ borderColor: selected ? color : 'var(--line)' }}
    >
      <div className="kang-strat-title" style={{ color }}>{strat.name}</div>
      <div className="kang-strat-desc">{strat.description}</div>
      <div className="kang-strat-universe">
        자산 {strat.defaultUniverse.length}종
      </div>
    </button>
  )
}

// ===================== 일임 매매 패널 =====================

/**
 * KIS 트레이딩 키 미설정/실패 응답을 일관되게 inline 안내로 떨어뜨리기 위한 fetch 래퍼.
 * 백엔드 GlobalExceptionHandler 의 표준 ErrorResponse {code, message} 를 풀어 throw.
 */
async function quantFetch(url, init) {
  const res = await fetch(url, init)
  const data = await res.json().catch(() => null)
  if (!res.ok) {
    const err = new Error(data?.message || data?.error || `HTTP ${res.status}`)
    err.status = res.status
    err.code = data?.code
    throw err
  }
  return data
}

function LiveTradingPanel({ strategy, state, onChange }) {
  const [allocated, setAllocated] = useState(state?.allocatedAmount || 5_000_000)
  const [running, setRunning] = useState(false)
  const [kisStatus, setKisStatus] = useState(null)            // {configured, accountConfigured, paperTrading}
  const [previewLog, setPreviewLog] = useState(null)          // DRY_RUN 결과 — 일임 시작 직전 미리보기
  const [previewLoading, setPreviewLoading] = useState(false)

  // 일임 종료 흐름 — 청산 옵션 + 미리보기. R7.
  const [showDisablePanel, setShowDisablePanel] = useState(false)
  const [liquidate, setLiquidate] = useState(false)            // 체크박스
  const [liquidationPreview, setLiquidationPreview] = useState(null)  // dryRun outcome
  const [liquidationLoading, setLiquidationLoading] = useState(false)

  useEffect(() => {
    if (state?.allocatedAmount) setAllocated(state.allocatedAmount)
  }, [state?.allocatedAmount])

  // KIS 키 설정 상태 — 일임 시작 버튼 활성화 가드용. 페이지 진입 시 1회.
  useEffect(() => {
    quantFetch('/api/trading/status').then(setKisStatus).catch(() => setKisStatus(null))
  }, [])

  const kisReady = !!(kisStatus?.configured && kisStatus?.accountConfigured)

  /**
   * 일임 시작 흐름 1단계 — DRY_RUN 으로 "지금 활성화하면 무엇을 살까" 미리보기.
   * 이 단계에서는 enable 안 함 → 사용자 확정(2단계) 누를 때까지 KIS 주문 0.
   */
  const handleStartPreview = async () => {
    setPreviewLoading(true); setPreviewLog(null)
    try {
      const data = await quantFetch(
        `/api/quant/run/${strategy.type}?kind=DRY_RUN&force=true`,
        { method: 'POST' }
      )
      setPreviewLog(data)
    } catch (e) {
      pushToast('미리보기 실패: ' + e.message, { type: 'error' })
    } finally {
      setPreviewLoading(false)
    }
  }

  /**
   * 일임 시작 흐름 2단계 — 사용자가 미리보기 확인 후 [확정] 누르면 실제 enable.
   * enable + 첫 리밸런싱 (MANUAL) 까지 일괄 진행할지는 사용자 결정에 달림 — 여기서는 enable 만.
   * 실제 주문은 매월 말일 스케줄에 맡긴다 (백테스트 인프라의 기본 동작).
   */
  const handleConfirmEnable = async () => {
    setRunning(true)
    try {
      await quantFetch(`/api/quant/enable/${strategy.type}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ allocatedAmount: allocated }),
      })
      pushToast(`${strategy.name} 일임 시작 — 매월 말일 자동 리밸런싱`, { type: 'success' })
      setPreviewLog(null)
      onChange()
    } catch (e) {
      pushToast('일임 시작 실패: ' + e.message, { type: 'error' })
    } finally { setRunning(false) }
  }

  const handleCancelPreview = () => setPreviewLog(null)

  // ===== R7: 일임 종료 흐름 (체크박스 → 청산 미리보기 → 확정) =====

  const handleStartDisable = () => {
    setShowDisablePanel(true)
    setLiquidate(false)
    setLiquidationPreview(null)
  }

  const handleCancelDisable = () => {
    setShowDisablePanel(false)
    setLiquidate(false)
    setLiquidationPreview(null)
  }

  /**
   * 청산 체크박스 토글. ON 이면 dryRun=true 호출하여 "X종목 약 ₩Y 매도 예정" 미리보기 채움.
   * OFF 면 미리보기 클리어. 백엔드 옵션 4번 결정에 따라 미리보기는 enabled 변경 X.
   */
  const handleToggleLiquidate = async (checked) => {
    setLiquidate(checked)
    if (!checked) { setLiquidationPreview(null); return }
    setLiquidationLoading(true)
    try {
      const data = await quantFetch(
        `/api/quant/disable/${strategy.type}?liquidate=true&dryRun=true`,
        { method: 'POST' }
      )
      setLiquidationPreview(data?.liquidation || null)
    } catch (e) {
      pushToast('청산 미리보기 실패: ' + e.message, { type: 'error' })
      setLiquidate(false)
    } finally {
      setLiquidationLoading(false)
    }
  }

  const handleConfirmDisable = async () => {
    setRunning(true)
    try {
      const url = liquidate
        ? `/api/quant/disable/${strategy.type}?liquidate=true&dryRun=false`
        : `/api/quant/disable/${strategy.type}`
      const data = await quantFetch(url, { method: 'POST' })

      if (liquidate) {
        const orders = data?.liquidation?.orders || []
        const failed = orders.filter(o => !o.success).length
        if (failed > 0) {
          pushToast(
            `${strategy.name} 일임 종료 — ⚠ 매도 ${failed}건 실패, 잔여 포지션 확인 필요`,
            { type: 'error' }
          )
        } else if (orders.length > 0) {
          pushToast(`${strategy.name} 일임 종료 + ${orders.length}건 청산 완료`, { type: 'success' })
        } else {
          pushToast(`${strategy.name} 일임 종료 (청산 대상 없음)`, { type: 'info' })
        }
      } else {
        pushToast(`${strategy.name} 일임 종료`, { type: 'info' })
      }

      handleCancelDisable()
      onChange()
    } catch (e) {
      pushToast('실패: ' + e.message, { type: 'error' })
    } finally { setRunning(false) }
  }

  const handleRun = async (kind) => {
    const isManual = kind === 'MANUAL'
    if (isManual && !confirm(
      `⚠️ 실제 주문이 실행됩니다.\n\n${strategy.name} 리밸런싱을 지금 즉시 실행합니까?\n할당 금액: ${fmtKrw(allocated)}`
    )) return
    setRunning(true)
    try {
      await quantFetch(`/api/quant/run/${strategy.type}?kind=${kind}&force=true`, { method: 'POST' })
      pushToast(
        kind === 'DRY_RUN' ? '시그널 생성 완료 (주문 없음)' : '리밸런싱 주문 전송 완료',
        { type: 'success' }
      )
      onChange()
    } catch (e) {
      pushToast('실패: ' + e.message, { type: 'error' })
    } finally { setRunning(false) }
  }

  const color = STRATEGY_COLORS[strategy.type] || '#1a1d26'
  const enabled = state?.enabled
  const showPreview = !!previewLog && !enabled

  return (
    <div className="kang-live">
      <div className="kang-live-header">
        <h3>일임 매매 (내 KIS 계좌)</h3>
        <div className="kang-enable-badge" style={{
          background: enabled ? '#DCFCE7' : 'var(--bg-3)',
          color: enabled ? '#166534' : 'var(--tx-2)',
        }}>
          {enabled ? '● 일임 중' : '○ 비활성'}
        </div>
      </div>

      {/* KIS 키 미설정 안내 — 일임 시작 자체가 불가능. */}
      {kisStatus && !kisReady && (
        <div style={{
          background: 'var(--warn-soft)', color: '#92400E', padding: '10px 14px',
          borderRadius: 8, fontSize: '0.85rem', marginBottom: 12,
        }}>
          ⚠ KIS API 키 미설정 — 일임 시작 불가. HANTOO_API_KEY / HANTOO_API_SECRET / HANTOO_ACCOUNT 환경변수를 설정 후 백엔드 재시작.
        </div>
      )}

      {/* 모의투자 안내 — 실거래/페이퍼 구분 표시. */}
      {kisStatus?.paperTrading && (
        <div style={{
          background: 'var(--down-soft)', color: '#1E3A8A', padding: '8px 12px',
          borderRadius: 8, fontSize: '0.8rem', marginBottom: 12,
        }}>
          ℹ 페이퍼 트레이딩 모드 — 실계좌 주문 X, 모의서버에서만 처리.
        </div>
      )}

      <div className="kang-live-config">
        <label>
          일임 금액 (원)
          <MoneyInput value={allocated} onChangeNumber={v => setAllocated(v || 0)}
                 suffix="원" disabled={enabled || showPreview} />
        </label>
        {enabled ? (
          <button onClick={handleStartDisable} disabled={running || showDisablePanel}
                  className="kang-btn-danger">
            일임 종료
          </button>
        ) : (
          <button
            onClick={handleStartPreview}
            disabled={running || previewLoading || !allocated || !kisReady || showPreview}
            style={{ background: color, opacity: (!allocated || !kisReady) ? 0.5 : 1 }}
            className="kang-btn-primary">
            {previewLoading ? '미리보기 생성 중...' : '일임 시작'}
          </button>
        )}
      </div>

      {/* DRY_RUN 미리보기 — "이 종목들을 이 비중으로 매수하고 매월 말일 자동 리밸런싱" 확인 단계 */}
      {showPreview && (
        <div style={{
          marginTop: 14, padding: 16, background: 'var(--bg-3)',
          border: '2px solid ' + color, borderRadius: 12,
        }}>
          <h4 style={{ margin: '0 0 8px', color }}>📋 일임 시작 전 미리보기 (실주문 X)</h4>
          {previewLog.signal?.rationale && (
            <div style={{ fontSize: '0.85rem', color: 'var(--tx-0)', marginBottom: 10 }}>
              {previewLog.signal.rationale}
            </div>
          )}
          {previewLog.signal?.targetWeights && Object.keys(previewLog.signal.targetWeights).length > 0 && (
            <div style={{ marginBottom: 10 }}>
              <b style={{ fontSize: '0.85rem' }}>목표 비중:</b>{' '}
              {Object.entries(previewLog.signal.targetWeights).map(([sym, w]) => (
                <span key={sym} className="kang-weight-pill" style={{ marginRight: 4 }}>
                  {sym} {(parseFloat(w) * 100).toFixed(1)}%
                </span>
              ))}
            </div>
          )}
          {previewLog.orders?.length > 0 ? (
            <>
              <b style={{ fontSize: '0.85rem' }}>리밸런싱 시 발생할 주문 ({previewLog.orders.length}건):</b>
              <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 4 }}>
                {previewLog.orders.map((o, i) => (
                  <div key={i} style={{
                    fontSize: '0.85rem',
                    color: o.side === 'BUY' ? 'var(--down)' : 'var(--up)',
                  }}>
                    {o.side === 'BUY' ? '🔴 매수' : '🟢 매도'} {o.name} ({o.symbol}) × {fmtQty(o.quantity)}
                    {o.price && <span style={{ color: 'var(--tx-2)' }}> @ {fmtKrw(o.price)}</span>}
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div style={{ fontSize: '0.85rem', color: 'var(--tx-2)' }}>
              현재 시그널 기준 매매할 종목 없음 (목표 비중 0% 또는 이미 보유 중).
            </div>
          )}
          <div style={{ marginTop: 14, display: 'flex', gap: 8 }}>
            <button onClick={handleCancelPreview} disabled={running} className="kang-btn-secondary">
              취소
            </button>
            <button onClick={handleConfirmEnable} disabled={running}
                    style={{ background: color, flex: 1 }} className="kang-btn-primary">
              ✓ 위 비중으로 일임 확정 (₩{Number(allocated).toLocaleString('ko-KR')})
            </button>
          </div>
        </div>
      )}

      {/* R7: 일임 종료 패널 — 보유 포지션 청산 옵션 + 미리보기 → 확정 */}
      {enabled && showDisablePanel && (
        <div style={{
          marginTop: 14, padding: 16, background: '#FFFBEB',
          border: '2px solid #F59E0B', borderRadius: 12,
        }}>
          <h4 style={{ margin: '0 0 10px', color: '#92400E' }}>일임 종료</h4>
          <label style={{
            display: 'flex', alignItems: 'flex-start', gap: 8,
            padding: '8px 10px', background: '#FFFFFF', borderRadius: 8,
            border: '1px solid #FCD34D', cursor: 'pointer',
          }}>
            <input type="checkbox" checked={liquidate}
                   onChange={e => handleToggleLiquidate(e.target.checked)}
                   disabled={running || liquidationLoading}
                   style={{ marginTop: 3 }} />
            <span style={{ fontSize: '0.88rem', color: 'var(--tx-0)' }}>
              <b>보유 포지션 청산</b> (이 전략이 관리하는 종목만 시장가 매도)
              <div style={{ fontSize: '0.78rem', color: 'var(--tx-2)', marginTop: 2 }}>
                체크하지 않으면 비활성화만 — 보유 종목은 그대로 남고 사용자가 직접 매도해야 합니다.
              </div>
            </span>
          </label>

          {liquidationLoading && (
            <div style={{ marginTop: 10, fontSize: '0.85rem', color: 'var(--tx-2)' }}>
              청산 미리보기 생성 중...
            </div>
          )}

          {liquidate && liquidationPreview && !liquidationLoading && (
            <div style={{ marginTop: 12, padding: 12, background: '#FFFFFF', borderRadius: 8 }}>
              {liquidationPreview.errorMessage ? (
                <div style={{ color: 'var(--up)', fontSize: '0.85rem' }}>
                  ⚠ {liquidationPreview.errorMessage}
                </div>
              ) : liquidationPreview.orders?.length > 0 ? (
                <>
                  <b style={{ fontSize: '0.85rem' }}>
                    청산 예정 ({liquidationPreview.orders.length}종목):
                  </b>
                  <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 4 }}>
                    {liquidationPreview.orders.map((o, i) => (
                      <div key={i} style={{ fontSize: '0.85rem', color: 'var(--up)' }}>
                        🟢 매도 {o.name} ({o.symbol}) × {fmtQty(o.quantity)}
                        {o.price > 0 && <span style={{ color: 'var(--tx-2)' }}> @ {fmtKrw(o.price)}</span>}
                      </div>
                    ))}
                  </div>
                </>
              ) : (
                <div style={{ fontSize: '0.85rem', color: 'var(--tx-2)' }}>
                  청산 대상 없음 — 이 전략 유니버스에서 보유 중인 종목이 없습니다.
                </div>
              )}
            </div>
          )}

          <div style={{ marginTop: 14, display: 'flex', gap: 8 }}>
            <button onClick={handleCancelDisable} disabled={running}
                    className="kang-btn-secondary">
              취소
            </button>
            <button onClick={handleConfirmDisable}
                    disabled={running || liquidationLoading}
                    className="kang-btn-danger" style={{ flex: 1 }}>
              {liquidate ? '✓ 종료 + 청산 확정' : '✓ 종료 (포지션 유지)'}
            </button>
          </div>
        </div>
      )}

      {/* 활성 상태 — 다음 자동 리밸런싱 안내 + 수동 트리거 */}
      {enabled && (
        <>
          <div style={{
            marginTop: 12, padding: '8px 14px', background: '#F0FDF4',
            color: '#166534', borderRadius: 8, fontSize: '0.85rem',
          }}>
            📅 다음 자동 리밸런싱: <b>매월 말일</b> {state?.nextScheduledAt && `(${new Date(state.nextScheduledAt).toLocaleDateString('ko-KR')})`}
          </div>
          <div className="kang-live-actions">
            <button onClick={() => handleRun('DRY_RUN')}
                    disabled={running || showDisablePanel}
                    className="kang-btn-secondary">
              🧪 시그널만 확인 (드라이런)
            </button>
            <button onClick={() => handleRun('MANUAL')}
                    disabled={running || showDisablePanel}
                    className="kang-btn-warning">
              ⚡ 지금 즉시 리밸런싱 (실주문)
            </button>
          </div>
        </>
      )}

      {state?.lastSignal && (
        <div className="kang-last-signal">
          <h4>최근 시그널 ({state.lastSignal.asOfDate})</h4>
          <div className="kang-signal-rationale">{state.lastSignal.rationale}</div>
          {state.currentWeights && Object.keys(state.currentWeights).length > 0 && (
            <div className="kang-weights">
              <b>현재 목표 비중:</b>
              {Object.entries(state.currentWeights).map(([sym, w]) => (
                <span key={sym} className="kang-weight-pill">
                  {sym} {(parseFloat(w) * 100).toFixed(1)}%
                </span>
              ))}
            </div>
          )}
        </div>
      )}

      {state?.recentExecutions?.length > 0 && (
        <details className="kang-executions">
          <summary>실행 이력 ({state.recentExecutions.length}건)</summary>
          {state.recentExecutions.slice(0, 10).map((e, i) => (
            <div key={i} className="kang-exec-item">
              <div className="kang-exec-header">
                <span>{new Date(e.executedAt).toLocaleString('ko-KR')}</span>
                <span className={`kang-exec-kind ${e.kind.toLowerCase()}`}>{e.kind}</span>
              </div>
              {e.errorMessage && <div className="kang-exec-error">⚠ {e.errorMessage}</div>}
              {e.signal && <div className="kang-exec-rationale">{e.signal.rationale}</div>}
              {e.orders?.length > 0 && (
                <div className="kang-exec-orders">
                  {e.orders.map((o, j) => (
                    <div key={j} className={`kang-order ${o.success ? 'ok' : 'fail'}`}>
                      {o.success ? '✓' : '✗'} {o.side} {o.name} ({o.symbol}) × {fmtQty(o.quantity)}
                      <span className="kang-order-msg"> — {o.message}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
        </details>
      )}
    </div>
  )
}

// ===================== 유니버스 표시 =====================

function UniversePanel({ strategy, dartStatus }) {
  const ROLE_LABEL = {
    OFFENSIVE: '공격',
    DEFENSIVE: '방어',
    CANARY: '카나리아',
    STATIC: '정적',
    VARIABLE: '가변',
  }
  if (strategy.factor) {
    return (
      <div className="kang-universe">
        <h4>스크리닝 유니버스</h4>
        <div className="kang-factor-univ-note">
          KOSPI 대형주 {dartStatus?.universeSize ?? '...'}종목 중에서 팩터 순위 상위 N개를 선정합니다.
          <br />유니버스는 <code>resources/kospi-universe.json</code>에서 커스터마이즈 가능.
        </div>
      </div>
    )
  }
  return (
    <div className="kang-universe">
      <h4>자산 유니버스</h4>
      <div className="kang-universe-grid">
        {strategy.defaultUniverse.map((a, i) => (
          <div key={`${a.symbol}-${i}`} className="kang-asset-row">
            <span className={`kang-role-tag role-${a.role?.toLowerCase()}`}>
              {ROLE_LABEL[a.role] || a.role}
            </span>
            <span className="kang-asset-code">{a.symbol}</span>
            <span className="kang-asset-name">{a.name}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

// ===================== 팩터 전략: 종목 선정 프리뷰 =====================

function FactorPreview({ strategy }) {
  const [topN, setTopN] = useState(20)
  const [loading, setLoading] = useState(false)
  const [picks, setPicks] = useState(null)

  const run = async () => {
    setLoading(true); setPicks(null)
    try {
      const res = await fetch(`/api/quant/preview/${strategy.type}?topN=${topN}`)
      const data = await res.json()
      if (!res.ok) throw new Error(data.error)
      setPicks(data)
    } catch (e) {
      pushToast('프리뷰 실패: ' + e.message, 'error')
    } finally { setLoading(false) }
  }

  return (
    <div className="kang-factor-preview">
      <div className="kang-form-row">
        <label>
          상위 N 종목
          <input type="number" value={topN} min={5} max={50}
                 onChange={e => setTopN(parseInt(e.target.value) || 20)} />
        </label>
        <button onClick={run} disabled={loading} className="kang-btn-secondary"
                style={{ alignSelf: 'flex-end' }}>
          {loading ? '순위 계산 중...' : '🔍 현재 상위 N 종목 조회'}
        </button>
      </div>
      {picks?.picks?.length > 0 && (
        <div className="kang-picks">
          <div className="kang-picks-rationale">{picks.rationale}</div>
          <table className="kang-picks-table">
            <thead>
              <tr>
                <th>#</th>
                <th>코드</th>
                <th>종목 / 지표</th>
                <th style={{ textAlign: 'right' }}>비중</th>
              </tr>
            </thead>
            <tbody>
              {picks.picks.map((p, i) => (
                <tr key={p.symbol}>
                  <td>{i + 1}</td>
                  <td className="kang-asset-code">{p.symbol}</td>
                  <td>{p.label}</td>
                  <td style={{ textAlign: 'right' }}>
                    {picks.targetWeights[p.symbol]
                      ? (parseFloat(picks.targetWeights[p.symbol]) * 100).toFixed(1) + '%'
                      : '-'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

// ===================== 메인 컴포넌트 =====================

export default function RoboAdvisor() {
  const [strategies, setStrategies] = useState([])
  const [selectedType, setSelectedType] = useState(null)
  const [states, setStates] = useState([])
  const [dartStatus, setDartStatus] = useState(null)

  // 모든 fetch 응답은 다음 두 가지를 동시에 만족해야 state 에 들어간다:
  //  1) HTTP 2xx (에러 본문 = 객체 → 배열 슬롯 오염 방지)
  //  2) 기대 타입 일치 (배열 자리에 객체가 들어오면 .find/.filter 가 폭발)
  // GlobalExceptionHandler 가 만든 {code, message, traceId} 객체가 실제로 뚫려서 들어왔던 적이 있어
  // 이중 가드를 둔다.
  const fetchJsonOrThrow = async (url) => {
    const res = await fetch(url)
    const data = await res.json().catch(() => null)
    if (!res.ok) {
      const msg = data?.message || data?.error || `HTTP ${res.status}`
      throw new Error(msg)
    }
    return data
  }

  const loadStrategies = async () => {
    try {
      const data = await fetchJsonOrThrow('/api/quant/strategies')
      const list = Array.isArray(data) ? data : []
      setStrategies(list)
      if (list.length > 0 && !selectedType) setSelectedType(list[0].type)
    } catch (e) {
      setStrategies([])
      pushToast('전략 목록 로드 실패: ' + e.message, 'error')
    }
  }

  const loadStates = async () => {
    try {
      const data = await fetchJsonOrThrow('/api/quant/state')
      setStates(Array.isArray(data) ? data : [])
    } catch (e) {
      setStates([])
      console.warn('state load failed', e)
    }
  }

  const loadDartStatus = async () => {
    try {
      const data = await fetchJsonOrThrow('/api/quant/dart-status')
      setDartStatus(data && typeof data === 'object' ? data : null)
    } catch (e) {
      setDartStatus(null)
      console.warn('dart status failed', e)
    }
  }

  // 마운트시 1회만 초기 데이터 로드. 함수들이 컴포넌트 내부 정의라 useCallback 으로 안정화하지 않으면
  // exhaustive-deps 만족이 어렵고, 의도(mount-only)는 빈 deps 가 가장 명확.
  useEffect(() => {
    loadStrategies()
    loadStates()
    loadDartStatus()
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const selected = strategies.find(s => s.type === selectedType)
  const selectedState = states.find(s => s.strategyType === selectedType)

  if (!strategies.length) {
    return <div className="kang-loading">전략 로드 중...</div>
  }

  const groupA = strategies.filter(s => GROUP_A_TYPES.has(s.type))
  const groupB = strategies.filter(s => GROUP_B_TYPES.has(s.type))

  return (
    <div className="kang-quant">
      <div className="kang-intro">
        <h2>로보어드바이저</h2>
        <p>강환국 퀀트 전략 12종 기반 자동 매매. 전략 선택 → 일임 금액 입력 → 활성화하면 KIS 계좌에서 월말 자동 리밸런싱.</p>
      </div>

      {dartStatus && !dartStatus.configured && (
        <div className="kang-warn-banner">
          ⚠ DART_OPEN_API_KEY 환경변수 미설정 — 팩터 전략(계층 B)은 재무제표 조회 불가.
          <a href="https://opendart.fss.or.kr/" target="_blank" rel="noreferrer"> opendart.fss.or.kr</a>
          에서 키 발급 후 서버 재시작.
        </div>
      )}

      <div className="kang-group">
        <h3 className="kang-group-title">계층 A · 자산배분</h3>
        <div className="kang-strat-grid">
          {groupA.map(s => (
            <StrategyCard key={s.type} strat={s}
                          selected={s.type === selectedType}
                          onClick={() => setSelectedType(s.type)} />
          ))}
        </div>
      </div>

      <div className="kang-group">
        <h3 className="kang-group-title">계층 B · 팩터 투자 (재무제표 기반)</h3>
        <div className="kang-strat-grid">
          {groupB.map(s => (
            <StrategyCard key={s.type} strat={s}
                          selected={s.type === selectedType}
                          onClick={() => setSelectedType(s.type)} />
          ))}
        </div>
      </div>

      {selected && (
        <div className="kang-body">
          <div className="kang-section">
            <h3>{selected.name}</h3>
            <p className="kang-desc">{selected.description}</p>
            <UniversePanel strategy={selected} dartStatus={dartStatus} />
            {selected.factor && (
              <FactorPreview strategy={selected} />
            )}
          </div>

          <div className="kang-section">
            <LiveTradingPanel strategy={selected} state={selectedState} onChange={loadStates} />
          </div>
        </div>
      )}

      {/* 글로벌 실시간 거래 내역 — 전략 선택 무관하게 항상 노출.
          모든 전략의 실행이 시간 desc 로 흘러들어옴, SSE 로 push. */}
      <ExecutionStream />
    </div>
  )
}
