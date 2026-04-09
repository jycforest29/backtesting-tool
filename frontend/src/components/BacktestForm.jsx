import { useState, useEffect, useRef } from 'react'

const ASSET_TYPES = [
  { value: 'US_STOCK', label: '미국 주식', needsSearch: true, market: 'US', placeholder: 'Apple, Tesla, NVIDIA...' },
  { value: 'KR_STOCK', label: '한국 주식', needsSearch: true, market: 'KR', placeholder: '삼성전자, 카카오, 네이버...' },
  { value: 'JP_STOCK', label: '일본 주식', needsSearch: true, market: 'JP', placeholder: 'Toyota, Sony, Nintendo...' },
  { value: 'FOREX', label: '환율', needsSearch: false },
  { value: 'GOLD', label: '금 (Gold)', needsSearch: false },
  { value: 'SILVER', label: '은 (Silver)', needsSearch: false },
  { value: 'BITCOIN', label: '비트코인 (BTC)', needsSearch: false },
]

const FOREX_PAIRS = [
  { symbol: 'KRW=X', label: 'USD/KRW (달러/원)', currency: 'USD' },
  { symbol: 'JPY=X', label: 'USD/JPY (달러/엔)', currency: 'USD' },
  { symbol: 'KRWJPY=X', label: 'KRW/JPY (원/엔)', currency: 'KRW' },
  { symbol: 'EURUSD=X', label: 'EUR/USD (유로/달러)', currency: 'EUR' },
]

export default function BacktestForm({ onSubmit, loading }) {
  const [assetType, setAssetType] = useState('US_STOCK')
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedSymbol, setSelectedSymbol] = useState(null) // { symbol, name, exchange }
  const [forexPair, setForexPair] = useState('KRW=X')
  const [buyDate, setBuyDate] = useState('')
  const [investmentAmount, setInvestmentAmount] = useState('')
  const [searchResults, setSearchResults] = useState([])
  const [showDropdown, setShowDropdown] = useState(false)
  const searchTimer = useRef(null)
  const wrapperRef = useRef(null)

  const currentAsset = ASSET_TYPES.find(a => a.value === assetType)

  useEffect(() => {
    const handleClick = (e) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target)) {
        setShowDropdown(false)
      }
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [])

  const handleSearchChange = (value) => {
    setSearchQuery(value)
    if (searchTimer.current) clearTimeout(searchTimer.current)
    if (value.length < 1) {
      setSearchResults([])
      setShowDropdown(false)
      return
    }
    searchTimer.current = setTimeout(async () => {
      try {
        const market = currentAsset.market || ''
        const url = `/api/search?q=${encodeURIComponent(value)}${market ? `&market=${market}` : ''}`
        const res = await fetch(url)
        const data = await res.json()
        setSearchResults(Array.isArray(data) ? data : [])
        setShowDropdown(true)
      } catch {
        setSearchResults([])
      }
    }, 300)
  }

  const selectItem = (item) => {
    setSelectedSymbol(item)
    setSearchQuery('')
    setShowDropdown(false)
    setSearchResults([])
  }

  const clearSelection = () => {
    setSelectedSymbol(null)
    setSearchQuery('')
  }

  const handleAssetTypeChange = (value) => {
    setAssetType(value)
    setSelectedSymbol(null)
    setSearchQuery('')
    setSearchResults([])
    setShowDropdown(false)
  }

  const resolveSymbol = () => {
    if (assetType === 'GOLD') return 'GC=F'
    if (assetType === 'SILVER') return 'SI=F'
    if (assetType === 'FOREX') return forexPair
    return selectedSymbol?.symbol || ''
  }

  const getCurrencyHint = () => {
    switch (assetType) {
      case 'US_STOCK': case 'GOLD': case 'SILVER': case 'BITCOIN': return 'USD'
      case 'KR_STOCK': return 'KRW'
      case 'JP_STOCK': return 'JPY'
      case 'FOREX': return FOREX_PAIRS.find(p => p.symbol === forexPair)?.currency || 'USD'
      default: return ''
    }
  }

  const canSubmit = () => {
    if (currentAsset.needsSearch && !selectedSymbol) return false
    if (!buyDate || !investmentAmount) return false
    return !loading
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    onSubmit({
      assetType,
      symbol: resolveSymbol(),
      buyDate,
      investmentAmount: parseFloat(investmentAmount),
      investmentCurrency: getCurrencyHint(),
    })
  }

  const today = new Date().toISOString().split('T')[0]

  return (
    <form className="form-card" onSubmit={handleSubmit}>
      <div className="form-row">
        <div className="form-group">
          <label>자산 유형</label>
          <select value={assetType} onChange={e => handleAssetTypeChange(e.target.value)}>
            {ASSET_TYPES.map(a => (
              <option key={a.value} value={a.value}>{a.label}</option>
            ))}
          </select>
        </div>

        {currentAsset.needsSearch && (
          <div className="form-group" ref={wrapperRef}>
            <label>종목 검색</label>
            {selectedSymbol ? (
              <div className="selected-chip">
                <span className="chip-name">{selectedSymbol.name || selectedSymbol.symbol}</span>
                <span className="chip-symbol">{selectedSymbol.symbol}</span>
                <button type="button" className="chip-clear" onClick={clearSelection}>&times;</button>
              </div>
            ) : (
              <div className="search-wrapper">
                <input
                  type="text"
                  value={searchQuery}
                  onChange={e => handleSearchChange(e.target.value)}
                  placeholder={currentAsset.placeholder}
                />
                {showDropdown && searchResults.length > 0 && (
                  <div className="search-dropdown">
                    {searchResults.map((item, i) => (
                      <div key={i} className="search-item" onClick={() => selectItem(item)}>
                        <span className="symbol-badge">{item.symbol}</span>
                        <div className="item-info">
                          <span className="item-name">{item.name}</span>
                          <span className="item-exchange">{item.exchange} · {item.type}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {assetType === 'FOREX' && (
          <div className="form-group">
            <label>환율 쌍</label>
            <select value={forexPair} onChange={e => setForexPair(e.target.value)}>
              {FOREX_PAIRS.map(p => (
                <option key={p.symbol} value={p.symbol}>{p.label}</option>
              ))}
            </select>
          </div>
        )}
      </div>

      <div className="form-row">
        <div className="form-group">
          <label>매수일</label>
          <input
            type="date"
            value={buyDate}
            onChange={e => setBuyDate(e.target.value)}
            max={today}
            required
          />
        </div>
        <div className="form-group">
          <label>투자금액 ({getCurrencyHint()})</label>
          <input
            type="number"
            value={investmentAmount}
            onChange={e => setInvestmentAmount(e.target.value)}
            placeholder="10,000"
            min="1"
            step="any"
            required
          />
        </div>
      </div>

      <button type="submit" className="btn-submit" disabled={!canSubmit()}>
        {loading ? '조회 중...' : '수익률 계산하기'}
      </button>
    </form>
  )
}
