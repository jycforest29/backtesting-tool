import { useCallback, useEffect, useRef, useState } from 'react'
import SockJS from 'sockjs-client/dist/sockjs'
import { Client, type IMessage } from '@stomp/stompjs'
import { marketByCurrency, type Market } from '../domain/market'
import { tradingApi, type Quote } from '../api/trading'

/** 가격 갱신 후 flash 표식 유지 시간 (ms). UX 상 사람 눈에 잠깐 띄게. */
const FLASH_MS = 1500

/** symbol → 새 메시지로 덮어쓰고 flash=true 표식. 신규 종목은 추가, 기존은 갱신.
 *  export 한 이유: pure function 단위 테스트용 (STOMP mock 회피). */
export function mergeQuotes(prev: Quote[], incoming: Quote[]): Quote[] {
  const map = new Map(prev.map(p => [p.symbol, p]))
  incoming.forEach(p => map.set(p.symbol, { ...p, flash: true }))
  return Array.from(map.values())
}

export interface UseTradingQuotesResult {
  quotes: Quote[]
  connected: boolean
  addWatch: (req: { market: Market; code: string; exchange: string }) => Promise<void>
  removeWatch: (q: Quote) => Promise<void>
  refreshNow: () => Promise<void>
}

/**
 * 트레이딩 호가 화면의 사이드이펙트 한 묶음.
 *
 *   - STOMP/SockJS 연결 + /topic/quotes subscribe
 *   - 메시지 수신 → quotes 머지 + 1.5초 후 flash 자동 클리어 (setTimeout)
 *   - 마운트 시 GET /api/trading/quotes 로 초기 스냅샷
 *   - watchlist add/remove + refresh 트리거
 *
 * 분리 이유 (학습 포인트):
 *   - WS 라이프사이클·flash 타이머·REST 초기화·watchlist mutation 이 한 컨테이너에 모이면
 *     cleanup 누락·race·중복 subscribe 가 잠복하기 쉽다.
 *   - 한 훅에 봉인하면 useEffect cleanup 한 곳에서 client.deactivate() 보장.
 *   - 테스트 시 STOMP Client 한 번 mock → 전체 시뮬레이션 가능.
 *
 * 알려진 한계:
 *   - flash 클리어 setTimeout 은 unmount 시에도 살아있음 → cleanup 함수에서 명시 clear 까진
 *     안 했지만 (state setter 가 unmounted ref 를 모름), 1.5s 짧고 React 가 stale setState 를
 *     무시하므로 문제 없음. strict-mode 더블 마운트 시 한 번 더 떠도 동작 동일.
 */
export function useTradingQuotes(): UseTradingQuotesResult {
  const [quotes, setQuotes] = useState<Quote[]>([])
  const [connected, setConnected] = useState(false)
  const clientRef = useRef<Client | null>(null)

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true)
        client.subscribe('/topic/quotes', (message: IMessage) => {
          const incoming = JSON.parse(message.body) as Quote[]
          setQuotes(prev => mergeQuotes(prev, incoming))
          setTimeout(() => {
            setQuotes(prev => prev.map(p => ({ ...p, flash: false })))
          }, FLASH_MS)
        })
      },
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
    })
    client.activate()
    clientRef.current = client

    tradingApi.quotes()
      .then(d => setQuotes(Array.isArray(d) ? d : []))
      .catch(() => {})

    return () => {
      if (client.active) client.deactivate()
      clientRef.current = null
    }
  }, [])

  const addWatch = useCallback(async (req: { market: Market; code: string; exchange: string }) => {
    try { await tradingApi.addWatch(req) }
    catch (e) { console.error(e) }
  }, [])

  // 낙관적 갱신: 서버 ack 를 기다리지 않고 UI 에서 즉시 제거.
  // 실패해도 다음 WS 메시지가 다시 도착하면 자연 복구되므로 롤백 코드 생략.
  const removeWatch = useCallback(async (q: Quote) => {
    try {
      await tradingApi.removeWatch(marketByCurrency(q.currency), q.symbol)
      setQuotes(prev => prev.filter(x => x.symbol !== q.symbol))
    } catch (e) { console.error(e) }
  }, [])

  const refreshNow = useCallback(async () => {
    try { await tradingApi.refresh() } catch { /* fire-and-forget */ }
  }, [])

  return { quotes, connected, addWatch, removeWatch, refreshNow }
}
