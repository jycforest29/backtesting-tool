import { useEffect, useState } from 'react'
import { tradingApi, type TradingStatus } from '../api/trading'

/**
 * KIS API 설정 상태 단발 조회.
 *
 * 마운트 시 1회 fetch. 실패 시 null 유지 (UI 는 "상태 확인 중..." 표시).
 * cancelled 플래그는 unmount 후 setState 경고를 차단 — strict-mode 더블 마운트 대비.
 */
export function useTradingStatus(): TradingStatus | null {
  const [status, setStatus] = useState<TradingStatus | null>(null)
  useEffect(() => {
    let cancelled = false
    tradingApi.status()
      .then(s => { if (!cancelled) setStatus(s) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [])
  return status
}
