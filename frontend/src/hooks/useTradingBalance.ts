import { useCallback, useEffect, useState } from 'react'
import { tradingApi, type Balance } from '../api/trading'
import type { Market } from '../domain/market'

export interface UseTradingBalanceResult {
  balance: Balance | null
  loading: boolean
  error: string | null
  market: Market
  setMarket: (m: Market) => void
  reload: () => void
}

/**
 * 시장별 잔고 조회. refreshKey 변화 시 자동 reload.
 *
 * refreshKey 패턴 (vs Redux/RxJS event bus):
 *   - 상위 컨테이너가 단조 증가 카운터를 prop 으로 내려보내면, 의존하는 패널들이
 *     useEffect 에서 자동 재조회. 외부 라이브러리 없이 cross-component 동기화.
 *   - 트레이딩 화면처럼 패널 수가 적고 (잔고/미체결) 트리거 횟수가 낮을 때 적절.
 *   - 패널이 더 많아지면 SWR/RTK Query 로 캐시 invalidate 패턴이 나음.
 */
export function useTradingBalance(refreshKey: number): UseTradingBalanceResult {
  const [market, setMarket] = useState<Market>('KR_STOCK')
  const [balance, setBalance] = useState<Balance | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setError(null)
    try {
      setBalance(await tradingApi.balance(market))
    } catch (e) {
      setError((e as Error).message); setBalance(null)
    } finally {
      setLoading(false)
    }
  }, [market])

  useEffect(() => { load() }, [load, refreshKey])

  return { balance, loading, error, market, setMarket, reload: load }
}
