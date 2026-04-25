import type { TradingStatus } from '../../api/trading'

interface Props { status: TradingStatus | null }

/**
 * KIS API 설정 / 환경 (모의/실전) / 계좌설정 상태 배너.
 * 상태 객체만 받아 UI 분기만 하는 순수 컴포넌트 — fetching 책임 없음.
 */
export function StatusBar({ status }: Props) {
  if (!status) return <div className="live-status-bar"><span>상태 확인 중...</span></div>

  if (!status.configured) {
    return (
      <div className="live-status-bar" style={{ background: '#FEF2F2', color: '#991B1B' }}>
        <strong>KIS API 미설정</strong>
        <span>.env에 KIS_APP_KEY, KIS_APP_SECRET 설정 후 백엔드 재시작</span>
      </div>
    )
  }

  return (
    <div className="live-status-bar">
      <div className="live-status">
        <span className={`live-dot ${status.paperTrading ? 'disconnected' : 'connected'}`} />
        <span>{status.paperTrading ? '모의투자 환경' : '실전투자 환경'}</span>
      </div>
      <div className="live-meta">
        {!status.accountConfigured && <span style={{ color: '#D97706' }}>계좌번호 미설정</span>}
      </div>
    </div>
  )
}
