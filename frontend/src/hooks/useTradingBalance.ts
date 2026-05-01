import { useCallback, useEffect, useState } from 'react'
import { tradingApi, ApiError, type Balance } from '../api/trading'
import type { Market } from '../domain/market'

/**
 * 잔고 호출 실패 정보. 단순 string 메시지가 아니라 status/code 를 보존해
 * 호출자가 503 의존성-미설정 vs 일반 오류를 다르게 표시할 수 있게 한다.
 */
export interface BalanceError {
  message: string
  status?: number
  code?: string
  /** UX 분기용 단축 플래그 — UI 가 inline 배너로 1회만 표시. */
  dependencyMissing: boolean
}

export interface UseTradingBalanceResult {
  balance: Balance | null
  loading: boolean
  error: BalanceError | null
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
  const [error, setError] = useState<BalanceError | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setError(null)
    try {
      setBalance(await tradingApi.balance(market))
    } catch (e) {
      const isApiErr = e instanceof ApiError
      setError({
        message: (e as Error).message,
        status: isApiErr ? e.status : undefined,
        code: isApiErr ? e.code : undefined,
        dependencyMissing: isApiErr && e.isDependencyMissing(),
      })
      setBalance(null)
    } finally {
      setLoading(false)
    }
  }, [market])

  useEffect(() => { load() }, [load, refreshKey])

  return { balance, loading, error, market, setMarket, reload: load }
}
