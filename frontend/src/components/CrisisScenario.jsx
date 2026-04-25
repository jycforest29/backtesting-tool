import { useState, useEffect } from 'react'
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, ReferenceLine, Cell } from 'recharts'
import MoneyInput from '../utils/MoneyInput'

const FACTORS = [
  { value: 'KOSPI', label: 'KOSPI' },
  { value: 'NASDAQ', label: 'NASDAQ' },
  { value: 'SP500', label: 'S&P 500' },
  { value: 'NIKKEI', label: 'Nikkei 225' },
  { value: 'USD_KRW', label: 'USD/KRW' },
  { value: 'INTEREST_RATE', label: '금리 (bp)' },
]

const RISK_COLORS = {
  LOW: { bg: '#ECFDF5', color: '#059669', label: 'LOW RISK' },
  MEDIUM: { bg: '#FFFBEB', color: '#D97706', label: 'MEDIUM RISK' },
  HIGH: { bg: '#FEF2F2', color: '#DC2626', label: 'HIGH RISK' },
  CRITICAL: { bg: '#450A0A', color: '#FCA5A5', label: 'CRITICAL' },
}

const ASSET_TYPES = [
  { value: 'KR_STOCK', label: '한국 주식' },
  { value: 'US_STOCK', label: '미국 주식' },
  { value: 'JP_STOCK', label: '일본 주식' },
]

const fmt = (n) => parseFloat(n).toLocaleString('ko-KR', { minimumFractionDigits: 0, maximumFractionDigits: 0 }) + '원'

export default function CrisisScenario() {
  const [presets, setPresets] = useState({})
  const [mode, setMode] = useState('preset') // 'preset' | 'custom'
  const [selectedPreset, setSelectedPreset] = useState('')
  const [customShocks, setCustomShocks] = useState([{ factor: 'KOSPI', shockPercent: -20 }])
  const [assets, setAssets] = useState([
    { assetType: 'KR_STOCK', symbol: '005930', name: '삼성전자', weight: 40 },
    { assetType: 'US_STOCK', symbol: 'SPY', name: 'S&P 500 ETF', weight: 40 },
    { assetType: 'JP_STOCK', symbol: '7203', name: 'Toyota', weight: 20 },
  ])
  const [portfolioValue, setPortfolioValue] = useState('100000000')
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    fetch('/api/stress-test/presets')
      .then(r => r.json())
      .then(setPresets)
      .catch(() => {})
  }, [])

  const addShock = () => setCustomShocks([...customShocks, { factor: 'SP500', shockPercent: -10 }])
  const removeShock = (i) => setCustomShocks(customShocks.filter((_, idx) => idx !== i))
  const updateShock = (i, field, value) => {
    const next = [...customShocks]
    next[i] = { ...next[i], [field]: field === 'shockPercent' ? parseFloat(value) || 0 : value }
    setCustomShocks(next)
  }

  const handleSubmit = async (e) => {
    e.preventDefault()
    setLoading(true)
    setError(null)
    setResult(null)

    const body = {
      assets,
      portfolioValue: parseFloat(portfolioValue),
      investmentCurrency: 'KRW',
      ...(mode === 'preset'
        ? { presetScenario: selectedPreset }
        : { shocks: customShocks }),
    }

    try {
      const res = await fetch('/api/stress-test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || 'Error')
      setResult(data)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  const riskCfg = result ? (RISK_COLORS[result.riskLevel] || RISK_COLORS.LOW) : null

  return (
    <div>
      <form className="form-card" onSubmit={handleSubmit}>
        {/* Mode toggle */}
        <div className="mode-tabs" style={{ marginBottom: 16 }}>
          <button type="button" className={`mode-tab ${mode === 'preset' ? 'active' : ''}`}
            onClick={() => setMode('preset')}>사전 시나리오</button>
          <button type="button" className={`mode-tab ${mode === 'custom' ? 'active' : ''}`}
            onClick={() => setMode('custom')}>커스텀 시나리오</button>
        </div>

        {mode === 'preset' ? (
          <div className="form-group">
            <label>시나리오 선택</label>
            <select value={selectedPreset} onChange={e => setSelectedPreset(e.target.value)}>
              <option value="">-- 시나리오 선택 --</option>
              {Object.entries(presets).map(([key, name]) => (
                <option key={key} value={key}>{name}</option>
              ))}
            </select>
          </div>
        ) : (
          <div className="assets-section">
            <label className="section-label">충격 시나리오 설정</label>
            {customShocks.map((shock, i) => (
              <div key={i} className="portfolio-asset-row">
                <div className="asset-fields">
                  <select className="asset-type-select" value={shock.factor}
                    onChange={e => updateShock(i, 'factor', e.target.value)}>
                    {FACTORS.map(f => <option key={f.value} value={f.value}>{f.label}</option>)}
                  </select>
                  <div className="weight-input-wrapper">
                    <input type="number" className="weight-input" value={shock.shockPercent}
                      onChange={e => updateShock(i, 'shockPercent', e.target.value)}
                      step="1" style={{ width: 70 }} />
                    <span className="weight-suffix">%</span>
                  </div>
                </div>
                <button type="button" className="btn-remove-asset" onClick={() => removeShock(i)}>
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M18 6L6 18M6 6l12 12"/>
                  </svg>
                </button>
              </div>
            ))}
            <button type="button" className="btn-add-asset" onClick={addShock}>+ 충격 추가</button>
          </div>
        )}

        <div className="form-row" style={{ marginTop: 16 }}>
          <div className="form-group">
            <label>포트폴리오 가치 (원)</label>
            <MoneyInput value={portfolioValue} onChange={setPortfolioValue} suffix="원" />
          </div>
        </div>

        {/* Simple assets for stress test */}
        <details style={{ marginTop: 12 }}>
          <summary style={{ cursor: 'pointer', fontSize: '0.88rem', fontWeight: 600, color: '#6b7280' }}>
            포트폴리오 구성 수정
          </summary>
          <div style={{ marginTop: 8 }}>
            {assets.map((a, i) => (
              <div key={i} className="portfolio-asset-row">
                <div className="asset-fields">
                  <select className="asset-type-select" value={a.assetType}
                    onChange={e => {
                      const next = [...assets]; next[i] = { ...a, assetType: e.target.value }; setAssets(next)
                    }}>
                    {ASSET_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
                  </select>
                  <input style={{ flex: 1, background: '#f3f4f6', border: '1.5px solid transparent', borderRadius: 10, padding: '10px 12px', fontSize: '0.85rem', fontFamily: 'inherit' }}
                    value={a.name} onChange={e => { const next = [...assets]; next[i] = { ...a, name: e.target.value }; setAssets(next) }} />
                  <div className="weight-input-wrapper">
                    <input type="number" className="weight-input" value={a.weight}
                      onChange={e => { const next = [...assets]; next[i] = { ...a, weight: parseFloat(e.target.value) || 0 }; setAssets(next) }}
                      min="0" max="100" step="1" />
                    <span className="weight-suffix">%</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </details>

        <button type="submit" className="btn-submit" style={{ marginTop: 16 }}
          disabled={loading || (mode === 'preset' && !selectedPreset)}>
          {loading ? '계산 중...' : '위기 시나리오 실행'}
        </button>
      </form>

      {error && <div className="error-msg">{error}</div>}

      {loading && <div className="loading"><div className="spinner" /><p>위기 시나리오 실행 중...</p></div>}

      {result && (
        <div className="result-card">
          <div className="result-header">
            <div>
              <h2>{result.scenarioName}</h2>
              <div className="result-sub">{result.scenarioDescription}</div>
            </div>
            <div className="return-badge" style={{ background: riskCfg.bg, color: riskCfg.color }}>
              {riskCfg.label}
            </div>
          </div>

          {/* Shocks applied */}
          <div className="template-chips" style={{ marginBottom: 16 }}>
            {result.shocksApplied.map((s, i) => (
              <span key={i} className="template-chip" style={{
                borderColor: parseFloat(s.shockPercent) < 0 ? '#EF4444' : '#10B981',
                color: parseFloat(s.shockPercent) < 0 ? '#DC2626' : '#059669',
                cursor: 'default'
              }}>
                {s.factor} {parseFloat(s.shockPercent) >= 0 ? '+' : ''}{s.shockPercent}%
              </span>
            ))}
          </div>

          {/* Summary */}
          <div className="stats-grid">
            <div className="stat-box">
              <div className="stat-label">Before</div>
              <div className="stat-value">{fmt(result.portfolioValueBefore)}</div>
            </div>
            <div className="stat-box">
              <div className="stat-label">After</div>
              <div className="stat-value" style={{ color: parseFloat(result.portfolioChangePercent) >= 0 ? '#059669' : '#dc2626' }}>
                {fmt(result.portfolioValueAfter)}
              </div>
            </div>
            <div className="stat-box">
              <div className="stat-label">Impact</div>
              <div className="stat-value" style={{ color: parseFloat(result.portfolioChangePercent) >= 0 ? '#059669' : '#dc2626' }}>
                {parseFloat(result.portfolioChangePercent) >= 0 ? '+' : ''}{result.portfolioChangePercent}%
              </div>
            </div>
          </div>

          {/* Per-asset impact chart */}
          <div className="chart-title">자산별 충격 영향</div>
          <div className="chart-container" style={{ height: 300 }}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={result.assetImpacts} margin={{ top: 5, right: 20, bottom: 5, left: 10 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
                <XAxis dataKey="name" tick={{ fill: '#6b7280', fontSize: 11 }} tickLine={false} />
                <YAxis tick={{ fill: '#9ca3af', fontSize: 11 }} tickLine={false} axisLine={false}
                  tickFormatter={v => v + '%'} />
                <Tooltip formatter={(v) => [v + '%', 'Impact']}
                  contentStyle={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 12, fontSize: '0.85rem' }} />
                <ReferenceLine y={0} stroke="#94a3b8" />
                <Bar dataKey="changePercent" radius={[4, 4, 0, 0]}>
                  {result.assetImpacts.map((entry, i) => (
                    <Cell key={i} fill={parseFloat(entry.changePercent) >= 0 ? '#10B981' : '#EF4444'} opacity={0.8} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>

          {/* Detail table */}
          <div className="asset-perf-table" style={{ marginTop: 16 }}>
            <div className="asset-perf-header" style={{ gridTemplateColumns: '2fr 1fr 1fr 1fr 1fr' }}>
              <span>Asset</span><span>Weight</span><span>Before</span><span>After</span><span>Impact</span>
            </div>
            {result.assetImpacts.map((a, i) => (
              <div key={i} className="asset-perf-row" style={{ gridTemplateColumns: '2fr 1fr 1fr 1fr 1fr' }}>
                <span>{a.name}</span>
                <span>{a.weight}%</span>
                <span>{fmt(a.valueBefore)}</span>
                <span>{fmt(a.valueAfter)}</span>
                <span style={{ color: parseFloat(a.changePercent) >= 0 ? '#059669' : '#dc2626', fontWeight: 700 }}>
                  {parseFloat(a.changePercent) >= 0 ? '+' : ''}{a.changePercent}%
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}