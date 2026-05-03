import { useState } from 'react'
import './toast'
import ToastContainer from './components/ToastContainer'
import PortfolioForm from './components/PortfolioForm'
import PortfolioResult from './components/PortfolioResult'
import InvestorFlowForm from './components/InvestorFlowForm'
import InvestorFlowResult from './components/InvestorFlowResult'
import AutoTrading from './components/AutoTrading'
import OcoPanel from './components/OcoPanel'
import CrisisScenario from './components/CrisisScenario'
import AuditLog from './components/AuditLog'
import RoboAdvisor from './components/RoboAdvisor'
import DesktopShell from './components/DesktopShell'
import MobileShell from './components/MobileShell'
import useIsMobile from './hooks/useIsMobile'

const NAV = [
  { key: 'portfolio', label: '백테스트',     hint: 'Portfolio' },
  { key: 'quant',     label: '로보어드바이저', hint: 'Robo Advisor' },
  { key: 'oco',       label: '자동매매',      hint: 'OCO Engine' },
  { key: 'investor',  label: '매매동향',      hint: 'Investor Flow' },
  { key: 'trading',   label: '계좌·주문',     hint: 'Account / Order' },
  { key: 'crisis',    label: '위기 시나리오',  hint: 'Stress Test' },
  { key: 'audit',     label: '이벤트 로그',    hint: 'Event Log' },
]

function App() {
  const [mode, setMode] = useState('portfolio')
  const [portfolioResult, setPortfolioResult] = useState(null)
  const [investorResult, setInvestorResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const isMobile = useIsMobile()

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
  const Shell = isMobile ? MobileShell : DesktopShell

  const page = (
    <>
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
    </>
  )

  return (
    <>
      <ToastContainer />
      <Shell nav={NAV} mode={mode} onModeChange={switchMode} active={active}>
        {page}
      </Shell>
    </>
  )
}

export default App
