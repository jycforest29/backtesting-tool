import { useState } from 'react'
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
import KangQuantStrategy from './components/KangQuantStrategy'

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

  return (
    <div className="app">
      <ToastContainer />
      <header className="app-header" style={{ position: 'relative' }}>
        <h1>그때 <span>샀으면</span> 지금 얼마?</h1>
        <p>백테스트 · 투자자별 매매동향 · 자동매매 (한국투자증권 API)</p>
        <div style={{ position: 'absolute', top: 12, right: 16 }}>
          <AlertsToggle />
        </div>
      </header>

      <div className="mode-tabs">
        {[
          { key: 'portfolio', label: '포트폴리오' },
          { key: 'quant', label: '강환국 퀀트 전략' },
          { key: 'oco', label: '자동매매' },
          { key: 'investor', label: '투자자별 매매동향' },
          { key: 'trading', label: '계좌·주문' },
          { key: 'crisis', label: '위기 시나리오' },
          { key: 'audit', label: '감사 로그' },
        ].map(tab => (
          <button key={tab.key}
            className={`mode-tab ${mode === tab.key ? 'active' : ''}`}
            onClick={() => switchMode(tab.key)}>
            {tab.label}
          </button>
        ))}
      </div>

      {mode === 'portfolio' && <PortfolioForm onSubmit={handlePortfolioSubmit} loading={loading} />}
      {mode === 'quant' && <KangQuantStrategy />}
      {mode === 'oco' && <OcoPanel />}
      {mode === 'investor' && <InvestorFlowForm onSubmit={handleInvestorSubmit} loading={loading} />}
      {mode === 'trading' && <AutoTrading />}
      {mode === 'crisis' && <CrisisScenario />}
      {mode === 'audit' && <AuditLog />}

      {error && <div className="error-msg">{error}</div>}

      {loading && (
        <div className="loading">
          <div className="spinner" />
          <p>데이터 조회 중...</p>
        </div>
      )}

      {mode === 'portfolio' && portfolioResult && <PortfolioResult result={portfolioResult} />}
      {mode === 'investor' && investorResult && <InvestorFlowResult result={investorResult} />}
    </div>
  )
}

export default App
