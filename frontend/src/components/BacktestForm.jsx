import { useState, useEffect, useRef } from 'react'
import MoneyInput from '../utils/MoneyInput'

const ASSET_TYPES = [
  { value: 'KR_STOCK', label: '한국 주식', placeholder: '6자리 코드 (예: 005930)', currency: 'KRW' },
  { value: 'US_STOCK', label: '미국 주식', placeholder: '티커 (예: AAPL, TSLA)', currency: 'USD' },
  { value: 'JP_STOCK', label: '일본 주식', placeholder: '4자리 코드 (예: 7203)', currency: 'JPY' },
]

export default function BacktestForm({ onSubmit, loading }) {
  const [assetType, setAssetType] = useState('KR_STOCK')
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedSymbol, setSelectedSymbol] = useState(null)
  const [buyDate, setBuyDate] = useState('')
  const [investmentAmount, setInvestmentAmount] = useState('')
  const [searchResults, setSearchResults] = useState([])
  const [showDropdown, setShowDropdown] = useState(false)
  const searchTimer = useRef(null)
  const wrapperRef = useRef(null)

  const currentAsset = ASSET_TYPES.find(a => a.value === assetType)

  useEffect(() => {
    const handleClick = (e) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target)) setShowDropdown(false)
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [])

  const handleSearchChange = (value) => {
    setSearchQuery(value)
    if (searchTimer.current) clearTimeout(searchTimer.current)
    if (value.length < 1) { setSearchResults([]); setShowDropdown(false); return }
    searchTimer.current = setTimeout(async () => {
      try {
        const url = `/api/search?q=${encodeURIComponent(value)}&market=${assetType}`
        const res = await fetch(url)
        const data = await res.json()
        setSearchResults(Array.isArray(data) ? data : [])
        setShowDropdown(true)
      } catch { setSearchResults([]) }
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

  const canSubmit = () => {
    if (!selectedSymbol) return false
    if (!buyDate || !investmentAmount) return false
    return !loading
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    onSubmit({
      assetType,
      symbol: selectedSymbol.symbol,
      buyDate,
      investmentAmount: parseFloat(investmentAmount),
      investmentCurrency: currentAsset.currency,
    })
  }

  const today = new Date().toISOString().split('T')[0]

  return (
    <form className="form-card" onSubmit={handleSubmit}>
      <div className="form-row">
        <div className="form-group">
          <label>시장</label>
          <select value={assetType} onChange={e => handleAssetTypeChange(e.target.value)}>
            {ASSET_TYPES.map(a => <option key={a.value} value={a.value}>{a.label}</option>)}
          </select>
        </div>

        <div className="form-group" ref={wrapperRef}>
          <label>종목 코드</label>
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
                        <span className="item-exchange">{item.exchange || item.market}</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      <div className="form-row">
        <div className="form-group">
          <label>매수일</label>
          <input type="date" value={buyDate} onChange={e => setBuyDate(e.target.value)} max={today} required />
        </div>
        <div className="form-group">
          <label>투자금액 ({currentAsset.currency})</label>
          <MoneyInput value={investmentAmount}
            onChange={setInvestmentAmount}
            placeholder="1,000,000" required allowDecimal />
        </div>
      </div>

      <button type="submit" className="btn-submit" disabled={!canSubmit()}>
        {loading ? '조회 중...' : '수익률 계산하기'}
      </button>
    </form>
  )
}
