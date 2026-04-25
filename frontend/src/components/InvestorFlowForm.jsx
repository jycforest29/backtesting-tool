import { useState, useEffect, useRef } from 'react'
import { useSubmitting } from '../hooks/useSubmitting'

const POPULAR_STOCKS = [
  { code: '005930', name: '삼성전자' },
  { code: '000660', name: 'SK하이닉스' },
  { code: '035420', name: 'NAVER' },
  { code: '035720', name: '카카오' },
  { code: '051910', name: 'LG화학' },
  { code: '006400', name: '삼성SDI' },
  { code: '005380', name: '현대자동차' },
  { code: '000270', name: '기아' },
  { code: '105560', name: 'KB금융' },
  { code: '055550', name: '신한지주' },
]

const PERIOD_OPTIONS = [
  { value: '1m', label: '1개월' },
  { value: '3m', label: '3개월' },
  { value: '6m', label: '6개월' },
  { value: '1y', label: '1년' },
]

export default function InvestorFlowForm({ onSubmit, loading }) {
  const [stockCode, setStockCode] = useState('')
  const [stockName, setStockName] = useState('')
  const [period, setPeriod] = useState('3m')
  const [searchQuery, setSearchQuery] = useState('')
  const [searchResults, setSearchResults] = useState([])
  const [showDropdown, setShowDropdown] = useState(false)
  const searchTimer = useRef(null)
  const wrapperRef = useRef(null)
  const { submitting, run } = useSubmitting()

  useEffect(() => {
    const handleClick = (e) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target)) setShowDropdown(false)
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [])

  const getDateRange = () => {
    const end = new Date()
    const start = new Date()
    switch (period) {
      case '1m': start.setMonth(start.getMonth() - 1); break
      case '3m': start.setMonth(start.getMonth() - 3); break
      case '6m': start.setMonth(start.getMonth() - 6); break
      case '1y': start.setFullYear(start.getFullYear() - 1); break
    }
    return {
      startDate: start.toISOString().split('T')[0],
      endDate: end.toISOString().split('T')[0],
    }
  }

  const handleSearch = (value) => {
    setSearchQuery(value)
    if (searchTimer.current) clearTimeout(searchTimer.current)
    if (value.length < 1) { setSearchResults([]); setShowDropdown(false); return }
    searchTimer.current = setTimeout(async () => {
      try {
        const res = await fetch(`/api/search?q=${encodeURIComponent(value)}&market=KR_STOCK`)
        const data = await res.json()
        setSearchResults(Array.isArray(data) ? data : [])
        setShowDropdown(true)
      } catch { setSearchResults([]) }
    }, 300)
  }

  const selectStock = (code, name) => {
    setStockCode(code.replace('.KS', '').replace('.KQ', ''))
    setStockName(name)
    setSearchQuery('')
    setShowDropdown(false)
    setSearchResults([])
  }

  const clearSelection = () => {
    setStockCode('')
    setStockName('')
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    const { startDate, endDate } = getDateRange()
    run(() => onSubmit({ stockCode, stockName, startDate, endDate }))
  }

  return (
    <form className="form-card" onSubmit={handleSubmit}>
      {/* Popular stocks */}
      <div className="template-section">
        <label className="section-label">인기 종목</label>
        <div className="template-chips">
          {POPULAR_STOCKS.map((s, i) => (
            <button key={i} type="button" className="template-chip"
              onClick={() => selectStock(s.code, s.name)}
              style={stockCode === s.code ? { borderColor: '#6366f1', background: '#f5f3ff', color: '#6366f1' } : {}}
            >
              {s.name}
            </button>
          ))}
        </div>
      </div>

      <div className="form-row">
        {/* Stock search */}
        <div className="form-group" ref={wrapperRef}>
          <label>종목 검색</label>
          {stockCode ? (
            <div className="selected-chip">
              <span className="chip-name">{stockName}</span>
              <span className="chip-symbol">{stockCode}</span>
              <button type="button" className="chip-clear" onClick={clearSelection}>&times;</button>
            </div>
          ) : (
            <div className="search-wrapper">
              <input
                type="text"
                value={searchQuery}
                onChange={e => handleSearch(e.target.value)}
                placeholder="종목명 또는 코드 입력..."
              />
              {showDropdown && searchResults.length > 0 && (
                <div className="search-dropdown">
                  {searchResults.map((item, i) => (
                    <div key={i} className="search-item" onClick={() => selectStock(item.symbol, item.name)}>
                      <span className="symbol-badge">{item.symbol}</span>
                      <div className="item-info">
                        <span className="item-name">{item.name}</span>
                        <span className="item-exchange">{item.exchange}</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>

        {/* Period */}
        <div className="form-group">
          <label>조회 기간</label>
          <div className="period-buttons">
            {PERIOD_OPTIONS.map(p => (
              <button key={p.value} type="button"
                className={`period-btn ${period === p.value ? 'active' : ''}`}
                onClick={() => setPeriod(p.value)}
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      <button type="submit" className="btn-submit" disabled={!stockCode || loading || submitting}>
        {loading || submitting ? '조회 중...' : '매매동향 조회'}
      </button>
    </form>
  )
}