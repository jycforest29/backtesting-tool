import { useState } from 'react'
import { useTradingStatus } from '../../hooks/useTradingStatus'
import { useTradingQuotes } from '../../hooks/useTradingQuotes'
import { StatusBar } from './StatusBar'
import { WatchlistPanel } from './WatchlistPanel'
import { OrderForm } from './OrderForm'
import { OpenOrdersPanel } from './OpenOrdersPanel'
import { BalancePanel } from './BalancePanel'

/**
 * AutoTrading 컨테이너.
 *
 * 책임:
 *   - 두 비동기 소스 (status REST + quotes WS) 를 훅으로 분리해 조합
 *   - 주문 성공 → refreshKey++ 로 잔고/미체결 패널을 cross-component 동기화
 *   - 자식 컴포넌트들에 props 만 전달 (presentational/container 분리)
 *
 * 분해 이유 (학습 포인트):
 *   - 기존 단일 .jsx (546 줄) 는 WS 라이프사이클·여러 패널 상태·도메인 상수가 모두 한 파일.
 *     단위 테스트가 어렵고 기능 추가 시 파일 길이가 선형 증가.
 *   - 컨테이너는 props 전달만 담당 → 패널 별 재사용·독립 테스트가 자유로움.
 *   - 빅테크 트레이딩 화면도 같은 패턴: container ↔ presentational ↔ hooks ↔ api.
 */
export default function AutoTrading() {
  const status = useTradingStatus()
  const { quotes, connected, addWatch, removeWatch, refreshNow } = useTradingQuotes()
  const [refreshKey, setRefreshKey] = useState(0)

  const onOrdered = () => setRefreshKey(k => k + 1)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <StatusBar status={status} />
      <WatchlistPanel
        quotes={quotes}
        connected={connected}
        onAdd={addWatch}
        onRemove={removeWatch}
        onRefresh={refreshNow}
      />
      {status?.configured && (
        <>
          <OrderForm paperTrading={status.paperTrading} onOrdered={onOrdered} />
          <OpenOrdersPanel refreshKey={refreshKey} />
          <BalancePanel refreshKey={refreshKey} />
        </>
      )}
    </div>
  )
}
