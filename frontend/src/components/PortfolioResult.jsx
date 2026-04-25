import { XAxis, YAxis, Tooltip, ResponsiveContainer, Area, AreaChart, Legend, Line, LineChart, CartesianGrid } from 'recharts'

const fmt = (num, prefix = '$') => {
  if (num == null) return '-'
  const n = parseFloat(num)
  return prefix + n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const pct = (num) => {
  if (num == null) return '-'
  const n = parseFloat(num)
  const sign = n >= 0 ? '+' : ''
  return sign + n.toFixed(2) + '%'
}

function RiskCard({ label, value, detail, color }) {
  return (
    <div className="risk-card">
      <div className="risk-label">{label}</div>
      <div className="risk-value" style={{ color: color || '#1a1d26' }}>{value}</div>
      {detail && <div className="risk-detail">{detail}</div>}
    </div>
  )
}

function PortfolioChart({ data, dcaEnabled }) {
  const chartData = data.map(p => ({
    date: p.date,
    value: parseFloat(p.value),
    invested: parseFloat(p.invested),
  }))

  const isPositive = chartData.length > 1 &&
    chartData[chartData.length - 1].value >= chartData[chartData.length - 1].invested

  const valueColor = isPositive ? '#059669' : '#dc2626'

  return (
    <div className="chart-container" style={{ height: 400 }}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 10 }}>
          <defs>
            <linearGradient id="valueGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={valueColor} stopOpacity={0.15} />
              <stop offset="100%" stopColor={valueColor} stopOpacity={0.02} />
            </linearGradient>
            <linearGradient id="investedGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#6366f1" stopOpacity={0.1} />
              <stop offset="100%" stopColor="#6366f1" stopOpacity={0.01} />
            </linearGradient>
          </defs>
          <XAxis
            dataKey="date" tick={{ fill: '#9ca3af', fontSize: 11 }}
            tickLine={false} axisLine={{ stroke: '#e5e7eb' }} minTickGap={60}
          />
          <YAxis
            tick={{ fill: '#9ca3af', fontSize: 11 }} tickLine={false} axisLine={false}
            tickFormatter={v => '$' + (v / 1000).toFixed(0) + 'k'}
            width={65}
          />
          <Tooltip
            contentStyle={{
              background: '#fff', border: '1px solid #e5e7eb', borderRadius: 12,
              boxShadow: '0 4px 12px rgba(0,0,0,0.08)', fontSize: '0.85rem',
            }}
            formatter={(value, name) => [
              fmt(value),
              name === 'value' ? '포트폴리오 가치' : '투자 원금'
            ]}
          />
          <Legend
            formatter={(value) => value === 'value' ? '포트폴리오 가치' : '투자 원금'}
            wrapperStyle={{ fontSize: '0.85rem' }}
          />
          {dcaEnabled && (
            <Area
              type="monotone" dataKey="invested" stroke="#6366f1" strokeWidth={1.5}
              fill="url(#investedGrad)" dot={false} strokeDasharray="4 4"
            />
          )}
          <Area
            type="monotone" dataKey="value" stroke={valueColor} strokeWidth={2}
            fill="url(#valueGrad)" dot={false}
            activeDot={{ r: 4, fill: valueColor, stroke: '#fff', strokeWidth: 2 }}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}


export default function PortfolioResult({ result }) {
  const r = result
  const m = r.riskMetrics || {}
  const isPositive = parseFloat(r.totalReturnPercent) >= 0

  return (
    <div className="result-card">
      {/* Summary Header */}
      <div className="result-header">
        <div>
          <h2>Portfolio Backtest</h2>
          <div className="result-sub">
            {r.startDate} ~ 현재 · {r.assetPerformances?.length}개 자산
            {r.rebalancePeriod !== 'NONE' && ` · ${r.rebalancePeriod} 리밸런싱 (${r.rebalanceCount}회)`}
            {r.dcaEnabled && ` · DCA ${fmt(r.dcaMonthlyAmount)}/월 (${r.dcaContributions}회)`}
          </div>
        </div>
        <div className={`return-badge ${isPositive ? 'return-positive' : 'return-negative'}`}>
          {pct(r.totalReturnPercent)}
        </div>
      </div>

      {/* Summary Stats */}
      <div className="stats-grid">
        <div className="stat-box">
          <div className="stat-label">총 투자금</div>
          <div className="stat-value">{fmt(r.totalInvested)}</div>
        </div>
        <div className="stat-box">
          <div className="stat-label">현재 가치</div>
          <div className="stat-value" style={{ color: isPositive ? '#059669' : '#dc2626' }}>
            {fmt(r.finalValue)}
          </div>
        </div>
        <div className="stat-box">
          <div className="stat-label">수익 / 손실</div>
          <div className="stat-value" style={{ color: isPositive ? '#059669' : '#dc2626' }}>
            {(isPositive ? '+' : '') + fmt(r.profitLoss)}
          </div>
        </div>
      </div>

      {/* Risk Metrics Dashboard */}
      <div className="section-title">리스크 지표 대시보드</div>
      <div className="risk-grid">
        <RiskCard
          label="Sharpe Ratio"
          value={m.sharpeRatio}
          detail={parseFloat(m.sharpeRatio) >= 1 ? 'Good' : parseFloat(m.sharpeRatio) >= 0.5 ? 'Moderate' : 'Low'}
          color={parseFloat(m.sharpeRatio) >= 1 ? '#059669' : parseFloat(m.sharpeRatio) >= 0.5 ? '#d97706' : '#dc2626'}
        />
        <RiskCard
          label="Sortino Ratio"
          value={m.sortinoRatio}
          detail="하방 리스크 대비 수익"
          color={parseFloat(m.sortinoRatio) >= 1 ? '#059669' : '#d97706'}
        />
        <RiskCard
          label="MDD (최대 낙폭)"
          value={pct(m.maxDrawdown)}
          detail={m.maxDrawdownStart && m.maxDrawdownEnd ? `${m.maxDrawdownStart} ~ ${m.maxDrawdownEnd}` : ''}
          color="#dc2626"
        />
        <RiskCard
          label="연간 변동성"
          value={pct(m.annualVolatility)}
          detail={parseFloat(m.annualVolatility) < 15 ? 'Low Risk' : parseFloat(m.annualVolatility) < 25 ? 'Medium' : 'High Risk'}
          color={parseFloat(m.annualVolatility) < 15 ? '#059669' : parseFloat(m.annualVolatility) < 25 ? '#d97706' : '#dc2626'}
        />
        <RiskCard
          label="연간 수익률"
          value={pct(m.annualReturn)}
          color={parseFloat(m.annualReturn) >= 0 ? '#059669' : '#dc2626'}
        />
        <RiskCard
          label="CAGR"
          value={pct(m.cagr)}
          detail="연복리 수익률"
          color={parseFloat(m.cagr) >= 0 ? '#059669' : '#dc2626'}
        />
      </div>

      {/* Asset Performance */}
      <div className="section-title" style={{ marginTop: 28 }}>자산별 성과</div>
      <div className="asset-perf-table">
        <div className="asset-perf-header">
          <span>자산</span>
          <span>비중</span>
          <span>배분 금액</span>
          <span>수익률</span>
        </div>
        {r.assetPerformances?.map((ap, i) => {
          const apPositive = parseFloat(ap.returnPercent) >= 0
          return (
            <div key={i} className="asset-perf-row">
              <span className="asset-perf-name">
                <span className="asset-perf-symbol">{ap.symbol}</span>
                {ap.name}
              </span>
              <span>{ap.weight}%</span>
              <span>{fmt(ap.allocated)}</span>
              <span style={{ color: apPositive ? '#059669' : '#dc2626', fontWeight: 700 }}>
                {pct(ap.returnPercent)}
              </span>
            </div>
          )
        })}
      </div>

      {/* Chart */}
      <div className="chart-title" style={{ marginTop: 28 }}>포트폴리오 가치 추이</div>
      {r.valueHistory && r.valueHistory.length > 0 && (
        <PortfolioChart data={r.valueHistory} dcaEnabled={r.dcaEnabled} />
      )}

      {/* Benchmark Comparison */}
      {r.benchmarks && r.benchmarks.length > 0 && (
        <>
          <div className="section-title" style={{ marginTop: 28 }}>벤치마크 비교</div>
          <BenchmarkChart portfolio={r.valueHistory} benchmarks={r.benchmarks} portfolioName="My Portfolio" />
          <div className="benchmark-table">
            <div className="asset-perf-header" style={{ gridTemplateColumns: '2fr 1fr 1fr 1fr' }}>
              <span>벤치마크</span>
              <span>총 수익률</span>
              <span>CAGR</span>
              <span>최종 가치</span>
            </div>
            <div className="asset-perf-row" style={{ gridTemplateColumns: '2fr 1fr 1fr 1fr', background: '#f5f3ff' }}>
              <span style={{ fontWeight: 700 }}>My Portfolio</span>
              <span style={{ color: isPositive ? '#059669' : '#dc2626', fontWeight: 700 }}>{pct(r.totalReturnPercent)}</span>
              <span style={{ fontWeight: 600 }}>{pct(m.cagr)}</span>
              <span style={{ fontWeight: 600 }}>{fmt(r.finalValue)}</span>
            </div>
            {r.benchmarks.map((b, i) => {
              const bPositive = parseFloat(b.totalReturn) >= 0
              return (
                <div key={i} className="asset-perf-row" style={{ gridTemplateColumns: '2fr 1fr 1fr 1fr' }}>
                  <span>{b.name}</span>
                  <span style={{ color: bPositive ? '#059669' : '#dc2626', fontWeight: 700 }}>{pct(b.totalReturn)}</span>
                  <span>{pct(b.cagr)}</span>
                  <span>{fmt(b.finalValue)}</span>
                </div>
              )
            })}
          </div>
        </>
      )}

      {/* Tax & Fee Result */}
      {r.taxFeeResult && r.taxFeeResult.enabled && (
        <>
          <div className="section-title" style={{ marginTop: 28 }}>세금 & 수수료</div>
          <div className="tax-result-grid">
            <div className="tax-result-item">
              <span className="tax-result-label">세전 수익</span>
              <span className="tax-result-value">{fmt(r.taxFeeResult.grossProfit)}</span>
            </div>
            <div className="tax-result-item deduction">
              <span className="tax-result-label">양도소득세</span>
              <span className="tax-result-value">-{fmt(r.taxFeeResult.capitalGainsTax)}</span>
            </div>
            <div className="tax-result-item deduction">
              <span className="tax-result-label">거래 수수료</span>
              <span className="tax-result-value">-{fmt(r.taxFeeResult.tradingFees)}</span>
            </div>
            <div className="tax-result-item deduction">
              <span className="tax-result-label">환전 수수료</span>
              <span className="tax-result-value">-{fmt(r.taxFeeResult.fxFees)}</span>
            </div>
            <div className="tax-result-item total-deduction">
              <span className="tax-result-label">총 공제</span>
              <span className="tax-result-value">-{fmt(r.taxFeeResult.totalDeductions)}</span>
            </div>
            <div className="tax-result-item net">
              <span className="tax-result-label">세후 순이익</span>
              <span className="tax-result-value" style={{
                color: parseFloat(r.taxFeeResult.netProfit) >= 0 ? '#059669' : '#dc2626'
              }}>{fmt(r.taxFeeResult.netProfit)}</span>
            </div>
            <div className="tax-result-item net">
              <span className="tax-result-label">세후 수익률</span>
              <span className="tax-result-value" style={{
                color: parseFloat(r.taxFeeResult.netReturnPercent) >= 0 ? '#059669' : '#dc2626'
              }}>{pct(r.taxFeeResult.netReturnPercent)}</span>
            </div>
            <div className="tax-result-item">
              <span className="tax-result-label">실효세율</span>
              <span className="tax-result-value">{r.taxFeeResult.effectiveTaxRate}%</span>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

const BENCHMARK_COLORS = ['#8b5cf6', '#f59e0b', '#ef4444', '#06b6d4', '#84cc16']

function BenchmarkChart({ portfolio, benchmarks, portfolioName }) {
  // Merge portfolio and benchmark data by date
  const dateMap = new Map()

  portfolio?.forEach(p => {
    dateMap.set(p.date, { date: p.date, portfolio: parseFloat(p.value) })
  })

  benchmarks.forEach((b, idx) => {
    b.valueHistory?.forEach(p => {
      const existing = dateMap.get(p.date)
      if (existing) {
        existing[`bench_${idx}`] = parseFloat(p.value)
      }
    })
  })

  const chartData = Array.from(dateMap.values()).sort((a, b) => a.date.localeCompare(b.date))

  return (
    <div className="chart-container" style={{ height: 380, marginBottom: 16 }}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 10 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
          <XAxis dataKey="date" tick={{ fill: '#9ca3af', fontSize: 11 }} tickLine={false} minTickGap={60} />
          <YAxis tick={{ fill: '#9ca3af', fontSize: 11 }} tickLine={false} axisLine={false}
            tickFormatter={v => '$' + (v / 1000).toFixed(0) + 'k'} width={65} />
          <Tooltip
            contentStyle={{
              background: '#fff', border: '1px solid #e5e7eb', borderRadius: 12,
              boxShadow: '0 4px 12px rgba(0,0,0,0.08)', fontSize: '0.82rem',
            }}
            formatter={(value, name) => {
              const label = name === 'portfolio' ? portfolioName : benchmarks.find((_, i) => `bench_${i}` === name)?.name || name
              return [fmt(value), label]
            }}
          />
          <Legend formatter={(value) => {
            if (value === 'portfolio') return portfolioName
            const idx = parseInt(value.replace('bench_', ''))
            return benchmarks[idx]?.name || value
          }} wrapperStyle={{ fontSize: '0.82rem' }} />
          <Line type="monotone" dataKey="portfolio" stroke="#059669" strokeWidth={2.5} dot={false} />
          {benchmarks.map((_, idx) => (
            <Line key={idx} type="monotone" dataKey={`bench_${idx}`}
              stroke={BENCHMARK_COLORS[idx % BENCHMARK_COLORS.length]}
              strokeWidth={1.5} dot={false} strokeDasharray={idx > 0 ? '4 4' : undefined} />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
