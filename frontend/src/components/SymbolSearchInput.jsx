import { useEffect, useRef, useState } from 'react'

/**
 * 종목 검색 입력 — 이름 또는 코드로 자동완성.
 * props:
 *   market: 'KR_STOCK' | 'US_STOCK' | 'JP_STOCK'
 *   selected: { symbol, name, exchange } | null
 *   onSelect: (item) => void
 *   onClear: () => void
 *   placeholder?: string
 *   compact?: boolean  - 좁은 공간용 스타일
 */
export default function SymbolSearchInput({
  market, selected, onSelect, onClear, placeholder, compact
}) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState([])
  const [show, setShow] = useState(false)
  const timerRef = useRef(null)
  const wrapRef = useRef(null)

  useEffect(() => {
    const handler = (e) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target)) setShow(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  const handleChange = (v) => {
    setQuery(v)
    if (timerRef.current) clearTimeout(timerRef.current)
    if (v.trim().length < 1) { setResults([]); setShow(false); return }
    timerRef.current = setTimeout(async () => {
      try {
        const res = await fetch(`/api/search?q=${encodeURIComponent(v)}&market=${market}`)
        const data = await res.json()
        setResults(Array.isArray(data) ? data : [])
        setShow(true)
      } catch { setResults([]) }
    }, 250)
  }

  const pick = (item) => {
    onSelect(item)
    setQuery('')
    setResults([])
    setShow(false)
  }

  if (selected) {
    return (
      <div className={compact ? 'asset-chip' : 'selected-chip'}>
        <span className={compact ? 'asset-chip-name' : 'chip-name'}>{selected.name || selected.symbol}</span>
        <span className={compact ? 'asset-chip-symbol' : 'chip-symbol'}>{selected.symbol}</span>
        <button type="button" onClick={onClear}
          className={compact ? '' : 'chip-clear'}>&times;</button>
      </div>
    )
  }

  return (
    <div className={compact ? 'asset-search-wrapper' : 'search-wrapper'} ref={wrapRef}
      style={{ position: 'relative' }}>
      <input type="text"
        value={query}
        onChange={e => handleChange(e.target.value)}
        placeholder={placeholder || '이름 또는 코드 (예: 삼성전자, 005930, AAPL)'} />
      {show && results.length > 0 && (
        <div className={compact ? 'asset-search-dropdown' : 'search-dropdown'}>
          {results.map((item, i) => (
            <div key={i} className="search-item" onClick={() => pick(item)}>
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
  )
}
