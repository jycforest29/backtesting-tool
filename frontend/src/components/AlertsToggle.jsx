import { useEffect, useRef, useState } from 'react'
import SockJS from 'sockjs-client/dist/sockjs'
import { Client } from '@stomp/stompjs'
import { pushToast } from '../toast'

/**
 * 장중 거래량 급증 스캐너 ON/OFF 스위치 + /topic/alerts 구독 → 토스트.
 * 헤더에 미니멀한 토글 하나만 노출. 별도 탭/페이지 만들지 않음.
 */
export default function AlertsToggle() {
  const [enabled, setEnabled] = useState(null) // null = 로딩 중
  const [connected, setConnected] = useState(false)
  const clientRef = useRef(null)
  const busyRef = useRef(false)

  // 1) 초기 상태 가져오기
  useEffect(() => {
    fetch('/api/trading/scanner/volume')
      .then(r => r.ok ? r.json() : null)
      .then(data => data && setEnabled(!!data.enabled))
      .catch(() => setEnabled(false))
  }, [])

  // 2) STOMP 구독
  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true)
        client.subscribe('/topic/alerts', (message) => {
          try {
            const payload = JSON.parse(message.body)
            if (payload?.text) {
              pushToast(payload.text, { type: 'info', durationMs: 10000 })
            }
          } catch { /* ignore */ }
        })
      },
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
    })
    client.activate()
    clientRef.current = client
    return () => { try { client.deactivate() } catch { /* */ } }
  }, [])

  const toggle = async () => {
    if (busyRef.current || enabled === null) return
    busyRef.current = true
    const next = !enabled
    try {
      const res = await fetch(`/api/trading/scanner/volume/enabled?value=${next}`,
        { method: 'POST' })
      const data = await res.json()
      setEnabled(!!data.enabled)
      pushToast(next ? '거래량 알림 ON' : '거래량 알림 OFF',
        { type: next ? 'success' : 'info', durationMs: 1800 })
    } catch {
      pushToast('토글 실패', { type: 'error' })
    } finally {
      busyRef.current = false
    }
  }

  if (enabled === null) return null

  return (
    <button onClick={toggle} title={connected ? '웹소켓 연결됨' : '연결 끊김'}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 8,
        padding: '6px 12px', borderRadius: 999,
        border: '1px solid ' + (enabled ? '#A7F3D0' : '#E5E7EB'),
        background: enabled ? '#ECFDF5' : '#F9FAFB',
        color: enabled ? '#065F46' : '#6B7280',
        fontSize: '0.82rem', fontWeight: 600,
        cursor: 'pointer',
      }}>
      <span style={{
        width: 8, height: 8, borderRadius: '50%',
        background: connected ? (enabled ? '#10B981' : '#9CA3AF') : '#F59E0B',
      }} />
      거래량 알림 {enabled ? 'ON' : 'OFF'}
    </button>
  )
}
