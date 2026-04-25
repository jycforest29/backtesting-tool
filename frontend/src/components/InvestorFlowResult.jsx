import {
  XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, Legend,
  Bar, BarChart, Line, ComposedChart, Area, ReferenceLine
} from 'recharts'

const fmtAmt = (num) => {
  if (num == null) return '-'
  const n = parseFloat(num)
  const abs = Math.abs(n)
  if (abs >= 1e12) return (n / 1e12).toFixed(1) + '조'
  if (abs >= 1e8) return (n / 1e8).toFixed(0) + '억'
  if (abs >= 1e4) return (n / 1e4).toFixed(0) + '만'
  return n.toLocaleString()
}

const fmtShares = (num) => {
  if (num == null) return '-'
  const abs = Math.abs(num)
  if (abs >= 1e6) return (num / 1e6).toFixed(1) + 'M'
  if (abs >= 1e3) return (num / 1e3).toFixed(0) + 'K'
  return num.toLocaleString()
}

const COLORS = {
  individual: '#EF4444',
  foreign: '#3B82F6',
  institution: '#10B981',
  price: '#1E293B',
}

const SIGNAL_CONFIG = {
  BULLISH: { label: '매집 신호 (BULLISH)', color: '#059669', bg: '#ECFDF5', desc: '외국인/기관이 사고 있는데 주가는 아직 반영 안 됨' },
  BEARISH: { label: '분산 신호 (BEARISH)', color: '#DC2626', bg: '#FEF2F2', desc: '외국인/기관이 팔고 있는데 주가는 아직 버티는 중' },
  NEUTRAL: { label: '중립', color: '#6B7280', bg: '#F8F9FB', desc: '매매동향과 주가 방향이 일치' },
}

const BUYER_LABELS = {
  FOREIGN: '외국인',
  INSTITUTION: '기관',
  INDIVIDUAL: '개인',
}

function SummaryCard({ label, value, color, sub }) {
  return (
    <div className="risk-card">
      <div className="risk-label">{label}</div>
      <div className="risk-value" style={{ color, fontSize: '1.1rem' }}>{value}</div>
      {sub && <div className="risk-detail">{sub}</div>}
    </div>
  )
}


export default function InvestorFlowResult({ result }) {
  const r = result
  const s = r.summary || {}
  const signalCfg = SIGNAL_CONFIG[s.divergenceSignal] || SIGNAL_CONFIG.NEUTRAL

  // Prepare chart data
  const chartData = (r.dailyData || []).map(d => ({
    date: d.date,
    individual: d.individualNet,
    foreign: d.foreignNet,
    institution: d.institutionNet,
    price: d.closePrice ? parseFloat(d.closePrice) : null,
    indCum: d.individualCumNet,
    forCum: d.foreignCumNet,
    instCum: d.institutionCumNet,
  }))

  return (
    <div className="result-card">
      {/* Header */}
      <div className="result-header">
        <div>
          <h2>{r.stockName || r.stockCode}</h2>
          <div className="result-sub">{r.stockCode} · 투자자별 매매동향</div>
        </div>
        <div className="return-badge" style={{ background: signalCfg.bg, color: signalCfg.color }}>
          {signalCfg.label}
        </div>
      </div>

      {/* Signal description */}
      <div className="divergence-box" style={{ borderLeftColor: signalCfg.color, background: signalCfg.bg }}>
        <div style={{ fontWeight: 700, marginBottom: 4 }}>매매동향 괴리 분석</div>
        <p>{signalCfg.desc}</p>
        <p style={{ marginTop: 4 }}>
          주도 매수자: <strong>{BUYER_LABELS[s.dominantBuyer] || '-'}</strong> ·
          주가 방향: <strong>{s.priceDirection === 'UP' ? '상승' : s.priceDirection === 'DOWN' ? '하락' : '보합'}</strong>
        </p>
      </div>

      {/* Summary Cards */}
      <div className="section-title" style={{ marginTop: 20 }}>기간 합계 순매수</div>
      <div className="risk-grid">
        <SummaryCard
          label="개인"
          value={fmtShares(s.individualTotalNet)}
          color={COLORS.individual}
          sub={fmtAmt(s.individualTotalAmt) + '원'}
        />
        <SummaryCard
          label="외국인"
          value={fmtShares(s.foreignTotalNet)}
          color={COLORS.foreign}
          sub={fmtAmt(s.foreignTotalAmt) + '원'}
        />
        <SummaryCard
          label="기관"
          value={fmtShares(s.institutionTotalNet)}
          color={COLORS.institution}
          sub={fmtAmt(s.institutionTotalAmt) + '원'}
        />
      </div>

      {/* Daily Net Buying Bar Chart */}
      {chartData.length > 0 && (
        <>
          <div className="chart-title" style={{ marginTop: 28 }}>일별 순매수 (주식 수)</div>
          <div className="chart-container" style={{ height: 350 }}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 10 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
                <XAxis dataKey="date" tick={{ fill: '#9ca3af', fontSize: 10 }} tickLine={false} minTickGap={40} />
                <YAxis tick={{ fill: '#9ca3af', fontSize: 11 }} tickLine={false} axisLine={false}
                  tickFormatter={v => fmtShares(v)} width={55} />
                <Tooltip
                  contentStyle={{
                    background: '#fff', border: '1px solid #e5e7eb', borderRadius: 12,
                    boxShadow: '0 4px 12px rgba(0,0,0,0.08)', fontSize: '0.82rem',
                  }}
                  formatter={(value, name) => [
                    fmtShares(value) + '주',
                    name === 'individual' ? '개인' : name === 'foreign' ? '외국인' : '기관'
                  ]}
                />
                <Legend formatter={v => v === 'individual' ? '개인' : v === 'foreign' ? '외국인' : '기관'}
                  wrapperStyle={{ fontSize: '0.82rem' }} />
                <ReferenceLine y={0} stroke="#94a3b8" strokeWidth={1} />
                <Bar dataKey="individual" fill={COLORS.individual} opacity={0.8} radius={[2, 2, 0, 0]} />
                <Bar dataKey="foreign" fill={COLORS.foreign} opacity={0.8} radius={[2, 2, 0, 0]} />
                <Bar dataKey="institution" fill={COLORS.institution} opacity={0.8} radius={[2, 2, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>

          {/* Cumulative + Price Chart */}
          <div className="chart-title" style={{ marginTop: 28 }}>누적 순매수 vs 주가</div>
          <div className="chart-container" style={{ height: 400 }}>
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={chartData} margin={{ top: 5, right: 60, bottom: 5, left: 10 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" />
                <XAxis dataKey="date" tick={{ fill: '#9ca3af', fontSize: 10 }} tickLine={false} minTickGap={40} />
                <YAxis yAxisId="cum" tick={{ fill: '#9ca3af', fontSize: 11 }} tickLine={false} axisLine={false}
                  tickFormatter={v => fmtShares(v)} width={55} />
                <YAxis yAxisId="price" orientation="right" tick={{ fill: '#9ca3af', fontSize: 11 }}
                  tickLine={false} axisLine={false}
                  tickFormatter={v => v.toLocaleString() + '원'} width={70} />
                <Tooltip
                  contentStyle={{
                    background: '#fff', border: '1px solid #e5e7eb', borderRadius: 12,
                    boxShadow: '0 4px 12px rgba(0,0,0,0.08)', fontSize: '0.82rem',
                  }}
                  formatter={(value, name) => {
                    if (name === 'price') return [value?.toLocaleString() + '원', '주가']
                    const label = name === 'indCum' ? '개인(누적)' : name === 'forCum' ? '외국인(누적)' : '기관(누적)'
                    return [fmtShares(value) + '주', label]
                  }}
                />
                <Legend formatter={v => {
                  if (v === 'price') return '주가'
                  return v === 'indCum' ? '개인(누적)' : v === 'forCum' ? '외국인(누적)' : '기관(누적)'
                }} wrapperStyle={{ fontSize: '0.82rem' }} />
                <ReferenceLine yAxisId="cum" y={0} stroke="#94a3b8" strokeWidth={1} />
                <Area yAxisId="cum" type="monotone" dataKey="forCum" stroke={COLORS.foreign}
                  fill={COLORS.foreign} fillOpacity={0.08} strokeWidth={2} dot={false} />
                <Area yAxisId="cum" type="monotone" dataKey="instCum" stroke={COLORS.institution}
                  fill={COLORS.institution} fillOpacity={0.08} strokeWidth={2} dot={false} />
                <Area yAxisId="cum" type="monotone" dataKey="indCum" stroke={COLORS.individual}
                  fill={COLORS.individual} fillOpacity={0.05} strokeWidth={1.5} dot={false}
                  strokeDasharray="4 4" />
                <Line yAxisId="price" type="monotone" dataKey="price" stroke={COLORS.price}
                  strokeWidth={2.5} dot={false} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        </>
      )}

      {chartData.length === 0 && (
        <div className="loading" style={{ padding: '20px' }}>
          <p>데이터가 없습니다. 다른 기간을 선택하거나 KIS 접근권한을 확인해 보세요.</p>
        </div>
      )}
    </div>
  )
}