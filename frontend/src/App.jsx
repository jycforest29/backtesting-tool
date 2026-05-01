import { useEffect, useState } from 'react'
import './toast'
import ToastContainer from './components/ToastContainer'
import PortfolioForm from './components/PortfolioForm'
import PortfolioResult from './components/PortfolioResult'
import InvestorFlowForm from './components/InvestorFlowForm'
import InvestorFlowResult from './components/InvestorFlowResult'
import AutoTrading from './components/AutoTrading'
import OcoPanel from './components/OcoPanel'
import AlertsToggle from './components/AlertsToggle'
import CrisisScenario from './components/CrisisScenario'
import AuditLog from './components/AuditLog'
import RoboAdvisor from './components/RoboAdvisor'

const NAV = [
  { key: 'portfolio', label: '백테스트',     hint: 'Portfolio' },
  { key: 'quant',     label: '로보어드바이저', hint: 'Robo Advisor' },
  { key: 'oco',       label: '자동매매',      hint: 'OCO Engine' },
  { key: 'investor',  label: '매매동향',      hint: 'Investor Flow' },
  { key: 'trading',   label: '계좌·주문',     hint: 'Account / Order' },
  { key: 'crisis',    label: '위기 시나리오',  hint: 'Stress Test' },
  { key: 'audit',     label: '이벤트 로그',    hint: 'Event Log' },
]

function NowClock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(id)
  }, [])
  const pad = (n) => String(n).padStart(2, '0')
  const date = `${now.getFullYear()}.${pad(now.getMonth() + 1)}.${pad(now.getDate())}`
  const time = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
  return (
    <div className="ctx-clock">
      <span className="ctx-clock-date">{date}</span>
      <span className="ctx-clock-time num">{time}</span>
    </div>
  )
}

function MarketStatusPill() {
  const now = new Date()
  const day = now.getDay()
  const hm = now.getHours() * 60 + now.getMinutes()
  const isWeekend = day === 0 || day === 6
  const isOpen = !isWeekend && hm >= 9 * 60 && hm < 15 * 60 + 30
  return (
    <span className={`ctx-status ${isOpen ? 'on' : 'off'}`}>
      <span className="ctx-status-dot" />
      KRX {isOpen ? '정규장' : '장마감'}
    </span>
  )
}

function App() {
  const [mode, setMode] = useState('portfolio')
  const [portfolioResult, setPortfolioResult] = useState(null)
  const [investorResult, setInvestorResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const handlePortfolioSubmit = async (request) => {
    setLoading(true); setError(null); setPortfolioResult(null)
    try {
      const res = await fetch('/api/portfolio-backtest', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || 'Unknown error')
      setPortfolioResult(data)
    } catch (e) { setError(e.message) } finally { setLoading(false) }
  }

  const handleInvestorSubmit = async ({ stockCode, stockName, startDate, endDate }) => {
    setLoading(true); setError(null); setInvestorResult(null)
    try {
      const params = new URLSearchParams({ stockCode, startDate, endDate })
      if (stockName) params.append('stockName', stockName)
      const res = await fetch(`/api/investor-flow?${params}`)
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || 'Unknown error')
      setInvestorResult(data)
    } catch (e) { setError(e.message) } finally { setLoading(false) }
  }

  const switchMode = (newMode) => {
    setMode(newMode); setError(null)
    setPortfolioResult(null); setInvestorResult(null)
  }

  const active = NAV.find(n => n.key === mode) ?? NAV[0]

  return (
    <div className="app">
      <ToastContainer />

      <aside className="shell-side">
        <div className="brand">
          <div className="brand-mark">Q</div>
          <div className="brand-text">
            <div className="brand-title">QuantTerminal</div>
            <div className="brand-sub">KIS · DART · KRX</div>
          </div>
        </div>

        <nav className="nav">
          {NAV.map(tab => (
            <button
              key={tab.key}
              className={`nav-item ${mode === tab.key ? 'active' : ''}`}
              onClick={() => switchMode(tab.key)}
            >
              <span className="nav-label">{tab.label}</span>
              <span className="nav-hint">{tab.hint}</span>
            </button>
          ))}
        </nav>

        <div className="shell-side-foot">
          <div className="legend">
            <span className="legend-row"><b className="up">▲</b> 상승 / 매수</span>
            <span className="legend-row"><b className="down">▼</b> 하락 / 매도</span>
          </div>
          <div className="build-tag">build · v1.0</div>
        </div>
      </aside>

      <header className="shell-top">
        <div className="ctx-left">
          <div className="ctx-crumb">
            <span className="ctx-crumb-root">Workspace</span>
            <span className="ctx-crumb-sep">/</span>
            <span className="ctx-crumb-leaf">{active.label}</span>
          </div>
          <div className="ctx-title">
            <h1>{active.label}</h1>
            <span className="ctx-hint">{active.hint}</span>
          </div>
        </div>
        <div className="ctx-right">
          <MarketStatusPill />
          <NowClock />
          <div className="ctx-alerts">
            <AlertsToggle />
          </div>
        </div>
      </header>

      <main className="shell-main">
        {mode === 'portfolio' && <PortfolioForm onSubmit={handlePortfolioSubmit} loading={loading} />}
        {mode === 'quant' && <RoboAdvisor />}
        {mode === 'oco' && <OcoPanel />}
        {mode === 'investor' && <InvestorFlowForm onSubmit={handleInvestorSubmit} loading={loading} />}
        {mode === 'trading' && <AutoTrading />}
        {mode === 'crisis' && <CrisisScenario />}
        {mode === 'audit' && <AuditLog />}

        {error && <div className="error-msg">{error}</div>}

        {loading && (
          <div className="loading">
            <div className="spinner" />
            <p>데이터 조회 중…</p>
          </div>
        )}

        {mode === 'portfolio' && portfolioResult && <PortfolioResult result={portfolioResult} />}
        {mode === 'investor' && investorResult && <InvestorFlowResult result={investorResult} />}
      </main>
    </div>
  )
}

export default App
