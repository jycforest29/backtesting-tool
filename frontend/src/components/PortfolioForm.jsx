import { useState, useEffect, useRef } from 'react'
import MoneyInput from '../utils/MoneyInput'
import { useSubmitting } from '../hooks/useSubmitting'

const ASSET_TYPES = [
  { value: 'KR_STOCK', label: '한국 주식', placeholder: '6자리 코드 (예: 005930)' },
  { value: 'US_STOCK', label: '미국 주식', placeholder: '티커 (예: AAPL)' },
  { value: 'JP_STOCK', label: '일본 주식', placeholder: '4자리 코드 (예: 7203)' },
]

const REBALANCE_OPTIONS = [
  { value: 'NONE', label: '리밸런싱 안 함' },
  { value: 'MONTHLY', label: '매월' },
  { value: 'QUARTERLY', label: '분기별' },
  { value: 'YEARLY', label: '매년' },
]

const TEMPLATES = [
  {
    name: '60/40 (전통적)',
    assets: [
      { assetType: 'US_STOCK', symbol: 'SPY', name: 'S&P 500 ETF', weight: 60 },
      { assetType: 'US_STOCK', symbol: 'AGG', name: 'US Bond ETF', weight: 40 },
    ],
    rebalance: 'YEARLY',
  },
  {
    name: 'KR 대형주 집중',
    assets: [
      { assetType: 'KR_STOCK', symbol: '005930', name: '삼성전자', weight: 40 },
      { assetType: 'KR_STOCK', symbol: '000660', name: 'SK하이닉스', weight: 30 },
      { assetType: 'KR_STOCK', symbol: '069500', name: 'KODEX 200', weight: 30 },
    ],
    rebalance: 'QUARTERLY',
  },
  {
    name: '미국 테크 집중',
    assets: [
      { assetType: 'US_STOCK', symbol: 'QQQ', name: 'Nasdaq 100', weight: 50 },
      { assetType: 'US_STOCK', symbol: 'NVDA', name: 'NVIDIA', weight: 20 },
      { assetType: 'US_STOCK', symbol: 'AAPL', name: 'Apple', weight: 15 },
      { assetType: 'US_STOCK', symbol: 'MSFT', name: 'Microsoft', weight: 15 },
    ],
    rebalance: 'MONTHLY',
  },
  {
    name: '한미일 분산',
    assets: [
      { assetType: 'KR_STOCK', symbol: '069500', name: 'KODEX 200', weight: 35 },
      { assetType: 'US_STOCK', symbol: 'SPY', name: 'S&P 500 ETF', weight: 45 },
      { assetType: 'JP_STOCK', symbol: '7203', name: 'Toyota', weight: 20 },
    ],
    rebalance: 'QUARTERLY',
  },
]

function AssetInputRow({ asset, index, onUpdate, onRemove }) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState([])
  const [showDropdown, setShowDropdown] = useState(false)
  const timerRef = useRef(null)
  const wrapperRef = useRef(null)

  useEffect(() => {
    const handleClick = (e) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target)) setShowDropdown(false)
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [])

  const currentType = ASSET_TYPES.find(a => a.value === asset.assetType)

  const handleSearch = (value) => {
    setQuery(value)
    if (timerRef.current) clearTimeout(timerRef.current)
    if (value.length < 1) { setResults([]); setShowDropdown(false); return }
    timerRef.current = setTimeout(async () => {
      try {
        const res = await fetch(`/api/search?q=${encodeURIComponent(value)}&market=${asset.assetType}`)
        const data = await res.json()
        setResults(Array.isArray(data) ? data : [])
        setShowDropdown(true)
      } catch { setResults([]) }
    }, 300)
  }

  const selectItem = (item) => {
    onUpdate(index, { ...asset, symbol: item.symbol, name: item.name })
    setQuery('')
    setShowDropdown(false)
  }

  const handleTypeChange = (newType) => {
    onUpdate(index, { ...asset, assetType: newType, symbol: '', name: '' })
    setQuery('')
    setResults([])
  }

  return (
    <div className="portfolio-asset-row">
      <div className="asset-fields">
        <select className="asset-type-select" value={asset.assetType}
          onChange={e => handleTypeChange(e.target.value)}>
          {ASSET_TYPES.map(a => <option key={a.value} value={a.value}>{a.label}</option>)}
        </select>

        <div className="asset-search-wrapper" ref={wrapperRef}>
          {asset.symbol ? (
            <div className="asset-chip">
              <span className="asset-chip-name">{asset.name || asset.symbol}</span>
              <span className="asset-chip-symbol">{asset.symbol}</span>
              <button type="button" onClick={() => onUpdate(index, { ...asset, symbol: '', name: '' })}>&times;</button>
            </div>
          ) : (
            <>
              <input type="text" value={query}
                onChange={e => handleSearch(e.target.value)}
                placeholder={currentType?.placeholder} />
              {showDropdown && results.length > 0 && (
                <div className="asset-search-dropdown">
                  {results.map((item, i) => (
                    <div key={i} className="search-item" onClick={() => selectItem(item)}>
                      <span className="symbol-badge">{item.symbol}</span>
                      <div className="item-info">
                        <span className="item-name">{item.name}</span>
                        <span className="item-exchange">{item.exchange}</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>

        <div className="weight-input-wrapper">
          <input type="number" className="weight-input" value={asset.weight}
            onChange={e => onUpdate(index, { ...asset, weight: parseFloat(e.target.value) || 0 })}
            min="0" max="100" step="0.5" />
          <span className="weight-suffix">%</span>
        </div>
      </div>

      <button type="button" className="btn-remove-asset" onClick={() => onRemove(index)}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M18 6L6 18M6 6l12 12"/>
        </svg>
      </button>
    </div>
  )
}

export default function PortfolioForm({ onSubmit, loading }) {
  const [assets, setAssets] = useState([
    { assetType: 'KR_STOCK', symbol: '', name: '', weight: 50 },
    { assetType: 'US_STOCK', symbol: '', name: '', weight: 50 },
  ])
  const [startDate, setStartDate] = useState('')
  const [investmentAmount, setInvestmentAmount] = useState('')
  const [investmentCurrency, setInvestmentCurrency] = useState('KRW')
  const [rebalancePeriod, setRebalancePeriod] = useState('NONE')
  const [dcaEnabled, setDcaEnabled] = useState(false)
  const [dcaMonthlyAmount, setDcaMonthlyAmount] = useState('')
  const [benchmarkEnabled, setBenchmarkEnabled] = useState(false)
  const [benchmarkSymbols, setBenchmarkSymbols] = useState(['SPY', 'DEPOSIT'])
  const [taxEnabled, setTaxEnabled] = useState(false)
  const [taxPreset, setTaxPreset] = useState('KR')
  const { submitting, run } = useSubmitting()

  const totalWeight = assets.reduce((sum, a) => sum + (a.weight || 0), 0)
  const weightOk = Math.abs(totalWeight - 100) < 1

  const addAsset = () => setAssets([...assets, { assetType: 'KR_STOCK', symbol: '', name: '', weight: 0 }])
  const updateAsset = (i, v) => { const n = [...assets]; n[i] = v; setAssets(n) }
  const removeAsset = (i) => { if (assets.length > 1) setAssets(assets.filter((_, idx) => idx !== i)) }

  const applyTemplate = (t) => {
    setAssets(t.assets.map(a => ({ ...a })))
    setRebalancePeriod(t.rebalance)
  }

  const equalizeWeights = () => {
    const w = parseFloat((100 / assets.length).toFixed(1))
    const rem = parseFloat((100 - w * (assets.length - 1)).toFixed(1))
    setAssets(assets.map((a, i) => ({ ...a, weight: i === 0 ? rem : w })))
  }

  const canSubmit = () => {
    if (!startDate || !investmentAmount) return false
    if (!weightOk) return false
    if (assets.some(a => !a.symbol)) return false
    if (dcaEnabled && !dcaMonthlyAmount) return false
    return !loading && !submitting
  }

  const getTaxConfig = () => {
    if (!taxEnabled) return { enabled: false }
    if (taxPreset === 'US') return { enabled: true, capitalGainsTaxRate: 22, taxExemption: 2500000, tradingFeeRate: 0.1, fxFeeRate: 0.25 }
    if (taxPreset === 'KR') return { enabled: true, capitalGainsTaxRate: 0, taxExemption: 0, tradingFeeRate: 0.015, fxFeeRate: 0 }
    return { enabled: true, capitalGainsTaxRate: 22, taxExemption: 0, tradingFeeRate: 0.1, fxFeeRate: 0 }
  }

  const toggleBenchmark = (symbol) => {
    setBenchmarkSymbols(prev => prev.includes(symbol) ? prev.filter(s => s !== symbol) : [...prev, symbol])
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    run(() =>
      onSubmit({
        assets: assets.map(a => ({ assetType: a.assetType, symbol: a.symbol, name: a.name, weight: a.weight })),
        startDate,
        investmentAmount: parseFloat(investmentAmount),
        investmentCurrency,
        rebalancePeriod,
        dcaEnabled,
        dcaMonthlyAmount: dcaEnabled ? parseFloat(dcaMonthlyAmount) : null,
        benchmarkEnabled,
        benchmarkSymbols: benchmarkEnabled ? benchmarkSymbols : null,
        taxFeeConfig: getTaxConfig(),
      }),
    )
  }

  const today = new Date().toISOString().split('T')[0]

  return (
    <form className="form-card" onSubmit={handleSubmit}>
      <div className="template-section">
        <label className="section-label">전략 템플릿</label>
        <div className="template-chips">
          {TEMPLATES.map((t, i) => (
            <button key={i} type="button" className="template-chip" onClick={() => applyTemplate(t)}>
              {t.name}
            </button>
          ))}
        </div>
      </div>

      <div className="assets-section">
        <div className="section-header">
          <label className="section-label">포트폴리오 자산</label>
          <div className="weight-indicator" style={{ color: weightOk ? 'var(--up)' : 'var(--down)' }}>
            {totalWeight.toFixed(1)}% / 100%
          </div>
        </div>

        {assets.map((asset, i) => (
          <AssetInputRow key={i} asset={asset} index={i} onUpdate={updateAsset} onRemove={removeAsset} />
        ))}

        <div className="asset-actions">
          <button type="button" className="btn-add-asset" onClick={addAsset}>+ 자산 추가</button>
          <button type="button" className="btn-equalize" onClick={equalizeWeights}>균등 배분</button>
        </div>
      </div>

      <div className="form-row" style={{ marginTop: 20 }}>
        <div className="form-group">
          <label>시작일</label>
          <input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} max={today} required />
        </div>
        <div className="form-group">
          <label>초기 투자금</label>
          <MoneyInput value={investmentAmount}
            onChange={setInvestmentAmount}
            placeholder="1,000,000" required />
        </div>
        <div className="form-group">
          <label>기준 통화</label>
          <select value={investmentCurrency} onChange={e => setInvestmentCurrency(e.target.value)}>
            <option value="KRW">KRW</option>
            <option value="USD">USD</option>
            <option value="JPY">JPY</option>
          </select>
        </div>
        <div className="form-group">
          <label>리밸런싱 주기</label>
          <select value={rebalancePeriod} onChange={e => setRebalancePeriod(e.target.value)}>
            {REBALANCE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>
      </div>

      <div className="dca-section">
        <label className="dca-toggle">
          <input type="checkbox" checked={dcaEnabled} onChange={e => setDcaEnabled(e.target.checked)} />
          <span className="dca-toggle-label">적립식 투자 (DCA)</span>
        </label>
        {dcaEnabled && (
          <div className="form-group dca-amount-group">
            <label>월 적립금액</label>
            <MoneyInput value={dcaMonthlyAmount}
              onChange={setDcaMonthlyAmount}
              placeholder="100,000" />
          </div>
        )}
      </div>

      <div className="dca-section">
        <label className="dca-toggle">
          <input type="checkbox" checked={benchmarkEnabled} onChange={e => setBenchmarkEnabled(e.target.checked)} />
          <span className="dca-toggle-label">벤치마크 비교</span>
        </label>
        {benchmarkEnabled && (
          <div className="benchmark-options">
            {[
              { symbol: 'SPY', label: 'S&P 500 (SPY)' },
              { symbol: 'QQQ', label: 'Nasdaq 100 (QQQ)' },
              { symbol: 'AGG', label: 'US 채권 (AGG)' },
              { symbol: '069500', label: 'KODEX 200' },
              { symbol: 'DEPOSIT', label: '예금 (3.5%)' },
            ].map(b => (
              <label key={b.symbol} className="benchmark-chip">
                <input type="checkbox" checked={benchmarkSymbols.includes(b.symbol)}
                  onChange={() => toggleBenchmark(b.symbol)} />
                <span>{b.label}</span>
              </label>
            ))}
          </div>
        )}
      </div>

      <div className="dca-section">
        <label className="dca-toggle">
          <input type="checkbox" checked={taxEnabled} onChange={e => setTaxEnabled(e.target.checked)} />
          <span className="dca-toggle-label">세금 & 수수료 반영</span>
        </label>
        {taxEnabled && (
          <div className="tax-options">
            {[
              { value: 'KR', label: '국내주식 (소액주주 비과세, 수수료 0.015%)' },
              { value: 'US', label: '해외주식 (양도세 22%, 250만원 공제)' },
            ].map(t => (
              <label key={t.value} className="tax-radio">
                <input type="radio" name="taxPreset" value={t.value}
                  checked={taxPreset === t.value} onChange={e => setTaxPreset(e.target.value)} />
                <span>{t.label}</span>
              </label>
            ))}
          </div>
        )}
      </div>

      <button type="submit" className="btn-submit" disabled={!canSubmit()}>
        {loading || submitting ? '분석 중...' : '포트폴리오 백테스트'}
      </button>
    </form>
  )
}
