import { useOpenOrders } from '../../hooks/useOpenOrders'
import { fmtMoney, fmtQty } from '../../utils/formatters'

interface Props { refreshKey: number }

/** 미체결 주문 리스트. 빈 상태에서는 아무것도 안 보임 (로딩/0건 모두 null). */
export function OpenOrdersPanel({ refreshKey }: Props) {
  const { orders, loading } = useOpenOrders(refreshKey)
  if (loading) return null
  if (orders.length === 0) return null

  return (
    <div className="form-card">
      <h3 style={{ marginTop: 0 }}>미체결 주문</h3>
      <div className="asset-perf-table">
        <div className="asset-perf-header" style={{ gridTemplateColumns: '1fr 1fr 1fr 1fr 1fr 1fr' }}>
          <span>주문번호</span><span>종목</span><span>구분</span>
          <span>수량</span><span>가격</span><span>잔여</span>
        </div>
        {orders.map((o, i) => (
          <div key={i} className="asset-perf-row"
            style={{ gridTemplateColumns: '1fr 1fr 1fr 1fr 1fr 1fr' }}>
            <span>{o.orderNo}</span>
            <span><strong>{o.symbol}</strong> {o.name}</span>
            <span>{o.side}</span>
            <span>{fmtQty(o.quantity)}</span>
            <span>{fmtMoney(o.price, 'KRW')}</span>
            <span>{fmtQty(o.remaining)}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
