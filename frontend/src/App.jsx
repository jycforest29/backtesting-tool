import { useState } from 'react'
import BacktestForm from './components/BacktestForm'
import ResultPanel from './components/ResultPanel'

function App() {
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const handleSubmit = async (request) => {
    setLoading(true)
    setError(null)
    setResult(null)

    try {
      const res = await fetch('/api/backtest', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request)
      })
      const data = await res.json()
      if (!res.ok) {
        throw new Error(data.error || 'Unknown error')
      }
      setResult(data)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="app">
      <header className="app-header">
        <h1>그때 <span>샀으면</span> 지금 얼마?</h1>
        <p>주식, 환율, 금, 은 — 과거 투자 수익률 시뮬레이터</p>
      </header>

      <BacktestForm onSubmit={handleSubmit} loading={loading} />

      {error && <div className="error-msg">{error}</div>}

      {loading && (
        <div className="loading">
          <div className="spinner" />
          <p>데이터 조회 중...</p>
        </div>
      )}

      {result && <ResultPanel result={result} />}
    </div>
  )
}

export default App
