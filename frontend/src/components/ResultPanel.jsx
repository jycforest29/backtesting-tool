import PriceChart from './PriceChart'

const formatNumber = (num, currency, isRate = false) => {
  if (num == null) return '-'
  const n = parseFloat(num)

  // For exchange rates or very small numbers, show enough decimal places
  if (isRate || (Math.abs(n) > 0 && Math.abs(n) < 1)) {
    const decimals = Math.abs(n) < 0.01 ? 6 : Math.abs(n) < 1 ? 4 : 2
    return n.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })
  }

  if (currency === 'KRW') {
    return n.toLocaleString('ko-KR', { maximumFractionDigits: 0 }) + '원'
  }
  if (currency === 'JPY') {
    return '¥' + n.toLocaleString('ja-JP', { maximumFractionDigits: 0 })
  }
  return '$' + n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const ASSET_LABELS = {
  US_STOCK: '미국 주식',
  KR_STOCK: '한국 주식',
  JP_STOCK: '일본 주식',
  FOREX: '환율',
  GOLD: '금',
  SILVER: '은',
  BITCOIN: '비트코인',
}

export default function ResultPanel({ result }) {
  const isPositive = parseFloat(result.returnPercent) >= 0
  const isForex = result.assetType === 'FOREX'

  const moneyCurrency = result.investmentCurrency || result.currency

  return (
    <div className="result-card">
      <div className="result-header">
        <div>
          <h2>{result.name || result.symbol}</h2>
          <div className="result-sub">
            {result.symbol} · {ASSET_LABELS[result.assetType] || result.assetType} · {result.buyDate} 매수
          </div>
        </div>
        <div className={`return-badge ${isPositive ? 'return-positive' : 'return-negative'}`}>
          {isPositive ? '+' : ''}{result.returnPercent}%
        </div>
      </div>

      <div className="stats-grid">
        <div className="stat-box">
          <div className="stat-label">투자금액</div>
          <div className="stat-value">{formatNumber(result.investmentAmount, moneyCurrency)}</div>
        </div>
        <div className="stat-box">
          <div className="stat-label">현재가치</div>
          <div className="stat-value" style={{ color: isPositive ? '#059669' : '#dc2626' }}>
            {formatNumber(result.currentValue, moneyCurrency)}
          </div>
        </div>
        <div className="stat-box">
          <div className="stat-label">수익 / 손실</div>
          <div className="stat-value" style={{ color: isPositive ? '#059669' : '#dc2626' }}>
            {isPositive ? '+' : ''}{formatNumber(result.profitLoss, moneyCurrency)}
          </div>
        </div>
        {!isForex && (
          <>
            <div className="stat-box">
              <div className="stat-label">매수가</div>
              <div className="stat-value">{formatNumber(result.buyPrice, moneyCurrency)}</div>
            </div>
            <div className="stat-box">
              <div className="stat-label">현재가</div>
              <div className="stat-value" style={{ color: isPositive ? '#059669' : '#dc2626' }}>
                {formatNumber(result.currentPrice, moneyCurrency)}
              </div>
            </div>
            <div className="stat-box">
              <div className="stat-label">보유 수량</div>
              <div className="stat-value">{parseFloat(result.units).toLocaleString('en-US', { maximumFractionDigits: 4 })}</div>
            </div>
          </>
        )}
      </div>

      {result.priceHistory && result.priceHistory.length > 0 && (
        <>
          <div className="chart-title">가격 추이</div>
          <PriceChart data={result.priceHistory} currency={result.currency} />
        </>
      )}
    </div>
  )
}
