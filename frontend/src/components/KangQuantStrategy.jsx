import { useEffect, useMemo, useState } from 'react'
import { XAxis, YAxis, Tooltip, ResponsiveContainer, Legend, Line, LineChart, CartesianGrid } from 'recharts'
import { pushToast } from '../toast'
import { fmtQty } from '../utils/formatters'
import MoneyInput from '../utils/MoneyInput'

const STRATEGY_COLORS = {
  VAA: '#7C3AED',
  DAA: '#2563EB',
  LAA: '#0D9488',
  DUAL_MOMENTUM: '#DC2626',
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
const fmtPct = (n, digits = 2) => {
  if (n == null) return '-'
  const v = Number(n)
  return (v >= 0 ? '+' : '') + v.toFixed(digits) + '%'
}

// ===================== 상단 전략 선택 =====================

function StrategyCard({ strat, selected, onClick }) {
  const color = STRATEGY_COLORS[strat.type] || '#1a1d26'
  return (
    <button
      onClick={onClick}
      className={`kang-strat-card ${selected ? 'selected' : ''}`}
      style={{ borderColor: selected ? color : '#E5E7EB' }}
    >
      <div className="kang-strat-title" style={{ color }}>{strat.name}</div>
      <div className="kang-strat-desc">{strat.description}</div>
      <div className="kang-strat-universe">
        자산 {strat.defaultUniverse.length}종
      </div>
    </button>
  )
}

// ===================== 백테스트 결과 차트 =====================

function BacktestChart({ result }) {
  const data = useMemo(() => {
    if (!result?.valueHistory) return []
    const byDate = new Map()
    result.valueHistory.forEach(p => {
      byDate.set(p.date, { date: p.date, strategy: parseFloat(p.value) })
    })
    if (result.benchmarks) {
      result.benchmarks.forEach(b => {
        b.valueHistory?.forEach(p => {
          const row = byDate.get(p.date) || { date: p.date }
          row[b.symbol] = parseFloat(p.value)
          byDate.set(p.date, row)
        })
      })
    }
    return Array.from(byDate.values()).sort((a, b) => a.date.localeCompare(b.date))
  }, [result])

  if (!data.length) return null
  const color = STRATEGY_COLORS[result.strategyType] || '#1a1d26'

  return (
    <div className="chart-container" style={{ height: 360 }}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 10, right: 20, bottom: 5, left: 10 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#F3F4F6" />
          <XAxis dataKey="date" tick={{ fill: '#9ca3af', fontSize: 11 }} minTickGap={60} />
          <YAxis
            tick={{ fill: '#9ca3af', fontSize: 11 }}
            tickFormatter={v => '₩' + (v / 1e6).toFixed(0) + 'M'}
            width={70}
          />
          <Tooltip
            contentStyle={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 10, fontSize: '0.85rem' }}
            formatter={(v) => fmtKrw(v)}
          />
          <Legend wrapperStyle={{ fontSize: '0.85rem' }} />
          <Line type="monotone" dataKey="strategy" name={result.strategyName}
                stroke={color} strokeWidth={2.4} dot={false} />
          {result.benchmarks?.map(b => (
            <Line key={b.symbol} type="monotone" dataKey={b.symbol} name={b.name}
                  stroke="#9ca3af" strokeWidth={1.5} strokeDasharray="4 4" dot={false} />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

// ===================== 백테스트 결과 메트릭 =====================

function MetricCard({ label, value, sub, color }) {
  return (
    <div className="kang-metric">
      <div className="kang-metric-label">{label}</div>
      <div className="kang-metric-value" style={{ color: color || '#111827' }}>{value}</div>
      {sub && <div className="kang-metric-sub">{sub}</div>}
    </div>
  )
}

function BacktestResult({ result }) {
  if (!result) return null
  const m = result.riskMetrics || {}
  const profitColor = parseFloat(result.totalReturnPercent) >= 0 ? '#059669' : '#DC2626'

  return (
    <div className="kang-result">
      <h3>백테스트 결과 — {result.strategyName}</h3>
      <div className="kang-metrics-grid">
        <MetricCard label="최종 평가액" value={fmtKrw(result.finalValue)}
                    sub={`초기 ${fmtKrw(result.initialAmount)}`} />
        <MetricCard label="총 수익률" value={fmtPct(result.totalReturnPercent)}
                    color={profitColor}
                    sub={fmtKrw(result.profitLoss)} />
        <MetricCard label="CAGR" value={fmtPct(m.cagr)} />
        <MetricCard label="MDD" value={fmtPct(m.maxDrawdown)} color="#DC2626"
                    sub={`${m.maxDrawdownStart || '-'} → ${m.maxDrawdownEnd || '-'}`} />
        <MetricCard label="샤프" value={m.sharpeRatio || '-'} />
        <MetricCard label="연변동성" value={fmtPct(m.annualVolatility)} />
        <MetricCard label="리밸런싱 횟수" value={result.rebalances?.length ?? 0} />
      </div>

      <BacktestChart result={result} />

      {result.benchmarks?.length > 0 && (
        <div className="kang-benchmarks">
          <h4>벤치마크 대비</h4>
          {result.benchmarks.map(b => (
            <div key={b.symbol} className="kang-bench-row">
              <span>{b.name}</span>
              <span>{fmtPct(b.totalReturn)}</span>
              <span>CAGR {fmtPct(b.cagr)}</span>
            </div>
          ))}
        </div>
      )}

      {result.rebalances?.length > 0 && (
        <details className="kang-rebalance-log">
          <summary>리밸런싱 이력 ({result.rebalances.length}회)</summary>
          <div className="kang-rebalance-list">
            {result.rebalances.slice(-20).reverse().map((r, i) => (
              <div key={i} className="kang-rebalance-item">
                <div className="kang-rebalance-date">{r.date}</div>
                <div className="kang-rebalance-note">{r.note}</div>
                <div className="kang-rebalance-weights">
                  {Object.entries(r.weights).map(([sym, w]) => (
                    <span key={sym} className="kang-weight-pill">
                      {sym} {parseFloat(w).toFixed(1)}%
                    </span>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </details>
      )}
    </div>
  )
}

// ===================== 백테스트 폼 =====================

function BacktestForm({ strategy, onRun, loading }) {
  const [startDate, setStartDate] = useState(() => {
    const d = new Date()
    d.setFullYear(d.getFullYear() - 5)
    return d.toISOString().slice(0, 10)
  })
  const [amount, setAmount] = useState(10_000_000)
  const [topN, setTopN] = useState('')
  const [momentumMonths, setMomentumMonths] = useState('')
  const [smaMonths, setSmaMonths] = useState('')
  const [benchmarkEnabled, setBenchmarkEnabled] = useState(true)

  const handleRun = () => {
    const req = {
      strategyType: strategy.type,
      startDate,
      investmentAmount: amount,
      benchmarkEnabled,
    }
    if (topN) req.topN = parseInt(topN, 10)
    if (momentumMonths) req.momentumMonths = parseInt(momentumMonths, 10)
    if (smaMonths) req.smaMonths = parseInt(smaMonths, 10)
    onRun(req)
  }

  const showTopN = strategy.type === 'VAA' || strategy.type === 'DAA' || strategy.type === 'ARIRANG_FACTOR_ROTATION' || strategy.type === 'SPAC_EVENT_DRIVEN'
  const showMomentum = strategy.type === 'DUAL_MOMENTUM'
  const showSma = strategy.type === 'LAA'

  return (
    <div className="kang-form">
      <div className="kang-form-row">
        <label>
          시작일
          <input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} />
        </label>
        <label>
          초기 투자금 (원)
          <MoneyInput value={amount} onChangeNumber={v => setAmount(v || 0)} suffix="원" />
        </label>
      </div>
      {(showTopN || showMomentum || showSma) && (
        <div className="kang-form-row">
          {showTopN && (
            <label>
              탑-N {strategy.type === 'DAA' ? '(기본 6)' : strategy.type === 'ARIRANG_FACTOR_ROTATION' ? '(기본 2)' : strategy.type === 'SPAC_EVENT_DRIVEN' ? '(최대 포지션, 기본 10)' : '(기본 1)'}
              <input type="number" value={topN} onChange={e => setTopN(e.target.value)} />
            </label>
          )}
          {showMomentum && (
            <label>
              룩백 개월 (기본 12)
              <input type="number" value={momentumMonths} onChange={e => setMomentumMonths(e.target.value)} />
            </label>
          )}
          {showSma && (
            <label>
              SMA 개월 (기본 10)
              <input type="number" value={smaMonths} onChange={e => setSmaMonths(e.target.value)} />
            </label>
          )}
        </div>
      )}
      <div className="kang-form-row">
        <label style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <input type="checkbox" checked={benchmarkEnabled}
                 onChange={e => setBenchmarkEnabled(e.target.checked)} />
          KODEX 200 벤치마크 비교
        </label>
      </div>
      <button className="kang-run-btn" onClick={handleRun} disabled={loading}>
        {loading ? '백테스트 중...' : '백테스트 실행'}
      </button>
    </div>
  )
}

// ===================== 실매매 패널 =====================

function LiveTradingPanel({ strategy, state, onChange }) {
  const [allocated, setAllocated] = useState(state?.allocatedAmount || 5_000_000)
  const [running, setRunning] = useState(false)

  useEffect(() => {
    if (state?.allocatedAmount) setAllocated(state.allocatedAmount)
  }, [state?.allocatedAmount])

  const handleEnable = async () => {
    setRunning(true)
    try {
      const res = await fetch(`/api/quant/enable/${strategy.type}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ allocatedAmount: allocated }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error)
      pushToast(`${strategy.name} 활성화 — 월말 자동 리밸런싱`, 'success')
      onChange()
    } catch (e) {
      pushToast('활성화 실패: ' + e.message, 'error')
    } finally { setRunning(false) }
  }

  const handleDisable = async () => {
    setRunning(true)
    try {
      const res = await fetch(`/api/quant/disable/${strategy.type}`, { method: 'POST' })
      if (!res.ok) throw new Error((await res.json()).error)
      pushToast(`${strategy.name} 비활성화`, 'info')
      onChange()
    } catch (e) {
      pushToast('실패: ' + e.message, 'error')
    } finally { setRunning(false) }
  }

  const handleRun = async (kind) => {
    const isManual = kind === 'MANUAL'
    if (isManual && !confirm(
      `⚠️ 실제 주문이 실행됩니다.\n\n${strategy.name} 리밸런싱을 지금 실행합니까?\n할당 금액: ${fmtKrw(allocated)}`
    )) return
    setRunning(true)
    try {
      const res = await fetch(`/api/quant/run/${strategy.type}?kind=${kind}&force=true`, {
        method: 'POST',
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error)
      pushToast(
        kind === 'DRY_RUN' ? '시그널 생성 완료 (주문 없음)' : '리밸런싱 주문 전송 완료',
        'success'
      )
      onChange()
    } catch (e) {
      pushToast('실패: ' + e.message, 'error')
    } finally { setRunning(false) }
  }

  const color = STRATEGY_COLORS[strategy.type] || '#1a1d26'
  const enabled = state?.enabled

  return (
    <div className="kang-live">
      <div className="kang-live-header">
        <h3>실전 매매 (내 KIS 계좌)</h3>
        <div className="kang-enable-badge" style={{
          background: enabled ? '#DCFCE7' : '#F3F4F6',
          color: enabled ? '#166534' : '#6B7280',
        }}>
          {enabled ? '● 활성' : '○ 비활성'}
        </div>
      </div>

      <div className="kang-live-config">
        <label>
          할당 금액 (원)
          <MoneyInput value={allocated} onChangeNumber={v => setAllocated(v || 0)}
                 suffix="원" disabled={enabled} />
        </label>
        {enabled ? (
          <button onClick={handleDisable} disabled={running} className="kang-btn-danger">
            자동 매매 비활성화
          </button>
        ) : (
          <button onClick={handleEnable} disabled={running || !allocated}
                  style={{ background: color }} className="kang-btn-primary">
            자동 매매 활성화
          </button>
        )}
      </div>

      <div className="kang-live-actions">
        <button onClick={() => handleRun('DRY_RUN')} disabled={running} className="kang-btn-secondary">
          🧪 시그널만 확인 (드라이런)
        </button>
        <button onClick={() => handleRun('MANUAL')} disabled={running} className="kang-btn-warning">
          ⚡ 지금 리밸런싱 (실주문)
        </button>
      </div>

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

export default function KangQuantStrategy() {
  const [strategies, setStrategies] = useState([])
  const [selectedType, setSelectedType] = useState(null)
  const [backtestResult, setBacktestResult] = useState(null)
  const [backtestLoading, setBacktestLoading] = useState(false)
  const [states, setStates] = useState([])
  const [dartStatus, setDartStatus] = useState(null)

  const loadStrategies = async () => {
    try {
      const res = await fetch('/api/quant/strategies')
      const data = await res.json()
      setStrategies(data)
      if (data.length > 0 && !selectedType) setSelectedType(data[0].type)
    } catch (e) {
      pushToast('전략 목록 로드 실패: ' + e.message, 'error')
    }
  }

  const loadStates = async () => {
    try {
      const res = await fetch('/api/quant/state')
      const data = await res.json()
      setStates(data)
    } catch (e) {
      console.warn('state load failed', e)
    }
  }

  const loadDartStatus = async () => {
    try {
      const res = await fetch('/api/quant/dart-status')
      const data = await res.json()
      setDartStatus(data)
    } catch (e) {
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

  const handleBacktest = async (req) => {
    setBacktestLoading(true)
    setBacktestResult(null)
    try {
      const res = await fetch('/api/quant/backtest', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || '오류')
      setBacktestResult(data)
    } catch (e) {
      pushToast('백테스트 실패: ' + e.message, 'error')
    } finally { setBacktestLoading(false) }
  }

  if (!strategies.length) {
    return <div className="kang-loading">전략 로드 중...</div>
  }

  const groupA = strategies.filter(s => GROUP_A_TYPES.has(s.type))
  const groupB = strategies.filter(s => GROUP_B_TYPES.has(s.type))

  return (
    <div className="kang-quant">
      <div className="kang-intro">
        <h2>강환국 퀀트 전략</h2>
        <p>자산배분(계층 A) + 팩터 투자(계층 B) 통합. 백테스트 검증 후 내 KIS 계좌에서 자동 리밸런싱 실행.</p>
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
                          onClick={() => { setSelectedType(s.type); setBacktestResult(null) }} />
          ))}
        </div>
      </div>

      <div className="kang-group">
        <h3 className="kang-group-title">계층 B · 팩터 투자 (재무제표 기반)</h3>
        <div className="kang-strat-grid">
          {groupB.map(s => (
            <StrategyCard key={s.type} strat={s}
                          selected={s.type === selectedType}
                          onClick={() => { setSelectedType(s.type); setBacktestResult(null) }} />
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
            <h3>백테스트</h3>
            {selected.factor && (
              <div className="kang-caveat">
                ⚠️ <b>팩터 전략 백테스트 주의:</b> 현재 재무제표로 상위 N 종목을 선정한 후 그 종목만의
                과거 가격으로 시뮬레이션합니다. <b>생존편향(survivorship bias)</b> 및
                <b> 룩어헤드 편향</b>이 내재되어 실제 성과보다 과대평가되는 경향. 참고용으로만 사용하세요.
              </div>
            )}
            <BacktestForm strategy={selected} onRun={handleBacktest} loading={backtestLoading} />
            {backtestLoading && (
              <div className="loading" style={{ marginTop: 16 }}>
                <div className="spinner" />
                <p>{selected.factor
                  ? '펀더멘털 조회 → 가격 수집 → 시뮬레이션 중... (1~3분 소요)'
                  : '가격 데이터 수집 + 시뮬레이션 중... (10년 ≈ 30초)'}</p>
              </div>
            )}
            {backtestResult && <BacktestResult result={backtestResult} />}
          </div>

          <div className="kang-section">
            <LiveTradingPanel strategy={selected} state={selectedState} onChange={loadStates} />
          </div>
        </div>
      )}
    </div>
  )
}
