import { XAxis, YAxis, Tooltip, ResponsiveContainer, ReferenceLine, Area, AreaChart } from 'recharts'

export default function PriceChart({ data, currency }) {
  const chartData = data.map(p => ({
    date: p.date,
    price: parseFloat(p.close),
  }))

  const firstPrice = chartData[0]?.price
  const lastPrice = chartData[chartData.length - 1]?.price
  const isPositive = lastPrice >= firstPrice
  const lineColor = isPositive ? '#059669' : '#dc2626'

  const formatPrice = (value) => {
    if (currency === 'KRW') return value.toLocaleString('ko-KR') + '원'
    if (currency === 'JPY') return '¥' + value.toLocaleString()
    return '$' + value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  }

  const currencyPrefix = currency === 'KRW' ? '' : currency === 'JPY' ? '¥' : '$'
  const currencySuffix = currency === 'KRW' ? '원' : ''

  return (
    <div className="chart-container">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 10 }}>
          <defs>
            <linearGradient id="priceGradient" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={lineColor} stopOpacity={0.15} />
              <stop offset="100%" stopColor={lineColor} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <XAxis
            dataKey="date"
            tick={{ fill: '#9ca3af', fontSize: 11 }}
            tickLine={false}
            axisLine={{ stroke: '#e5e7eb' }}
            minTickGap={60}
          />
          <YAxis
            tick={{ fill: '#9ca3af', fontSize: 11 }}
            tickLine={false}
            axisLine={false}
            tickFormatter={v => currencyPrefix + v.toLocaleString() + currencySuffix}
            domain={['auto', 'auto']}
            width={80}
          />
          <Tooltip
            contentStyle={{
              background: '#fff',
              border: '1px solid #e5e7eb',
              borderRadius: '12px',
              color: '#1a1d26',
              boxShadow: '0 4px 12px rgba(0,0,0,0.08)',
              fontSize: '0.85rem',
            }}
            formatter={(value) => [formatPrice(value), '가격']}
            labelStyle={{ color: '#9ca3af', marginBottom: 4 }}
          />
          {firstPrice && (
            <ReferenceLine
              y={firstPrice}
              stroke="#d1d5db"
              strokeDasharray="4 4"
              label={{ value: '매수가', fill: '#9ca3af', fontSize: 11 }}
            />
          )}
          <Area
            type="monotone"
            dataKey="price"
            stroke={lineColor}
            strokeWidth={2}
            fill="url(#priceGradient)"
            dot={false}
            activeDot={{ r: 4, fill: lineColor, stroke: '#fff', strokeWidth: 2 }}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
