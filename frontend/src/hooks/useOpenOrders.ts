import { useCallback, useEffect, useState } from 'react'
import { tradingApi, type OpenOrder } from '../api/trading'

export interface UseOpenOrdersResult {
  orders: OpenOrder[]
  loading: boolean
}

/** 미체결 주문 조회. refreshKey 변화 시 자동 reload (주문 성공 후 갱신). */
export function useOpenOrders(refreshKey: number): UseOpenOrdersResult {
  const [orders, setOrders] = useState<OpenOrder[]>([])
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try { setOrders(await tradingApi.openOrders()) }
    catch { setOrders([]) }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load, refreshKey])

  return { orders, loading }
}
