import { useEffect, useRef, useState } from 'react'
import { fmtQty } from '../utils/formatters'

/**
 * 실시간 거래 실행 timeline.
 *
 * 흐름:
 *   1) mount 시 GET /api/quant/executions?limit=50 으로 백필 (최근 desc)
 *   2) EventSource('/api/quant/executions/stream') 으로 push 구독
 *   3) 새 'execution' 이벤트는 상단에 prepend, fade-in 애니메이션
 *   4) DRY_RUN 토글 — noisy 한 미리보기는 숨길 수 있게
 *
 * 재연결: EventSource 가 native 자동 재연결 (서버 끊김/네트워크 단절 후 자동 복구).
 * 메모리: 최대 200건만 메모리 유지 (오래된 건 잘림).
 */

const KIND_BADGE = {
  SCHEDULED:   { label: '자동', bg: '#DBEAFE', fg: '#1E40AF' },
  MANUAL:      { label: '수동', bg: '#FEE2E2', fg: '#991B1B' },
  DRY_RUN:     { label: '드라이런', bg: '#DCFCE7', fg: '#166534' },
  LIQUIDATION: { label: '청산', bg: '#FEF3C7', fg: '#92400E' },
}

const STRATEGY_LABEL = {
  VAA: 'VAA', DAA: 'DAA', LAA: 'LAA',
  DUAL_MOMENTUM: '듀얼 모멘텀',
  HALLOWEEN: '할로윈',
  ARIRANG_FACTOR_ROTATION: 'ARIRANG 팩터',
  SPAC_EVENT_DRIVEN: 'SPAC',
  MAGIC_FORMULA: '신마법공식',
  SUPER_VALUE: '슈퍼가치',
  SUPER_QUALITY: '슈퍼퀄리티',
  ULTRA: '울트라',
  F_SCORE: 'F-Score',
}

const MAX_ENTRIES = 200

const fmtTime = (iso) => {
  if (!iso) return '-'
  const d = new Date(iso)
  return d.toLocaleString('ko-KR', { hour12: false })
}

const fmtKrw = (n) => n == null ? '-' : '₩' + Number(n).toLocaleString('ko-KR', { maximumFractionDigits: 0 })

export default function ExecutionStream() {
  const [entries, setEntries] = useState([])
  const [showDryRun, setShowDryRun] = useState(true)
  const [connected, setConnected] = useState(false)
  const [error, setError] = useState(null)
  // 새로 들어온 entry 의 ID 집합 — fade-in 애니메이션 트리거용. 마운트 후 추가된 것만 highlight.
  const newIdsRef = useRef(new Set())

  useEffect(() => {
    let cancelled = false

    // 1) 백필 — 최근 50건 가져와 화면에 즉시 표시.
    fetch('/api/quant/executions?limit=50')
      .then(r => r.ok ? r.json() : [])
      .then(list => {
        if (cancelled) return
        if (Array.isArray(list)) setEntries(list)
      })
      .catch(() => {})

    // 2) SSE 구독 — 새 실행 이벤트 push.
    const es = new EventSource('/api/quant/executions/stream')
    es.onopen = () => { setConnected(true); setError(null) }
    es.onerror = () => {
      // EventSource 가 자동 재연결 시도 — connected=false 표시만, 토스트 안 띄움.
      setConnected(false)
    }
    es.addEventListener('hello', () => { setConnected(true) })
    es.addEventListener('execution', (ev) => {
      try {
        const log = JSON.parse(ev.data)
        const id = `${log.executedAt}-${log.strategyType}-${log.kind}`
        newIdsRef.current.add(id)
        // 1.5초 후 highlight 제거
        setTimeout(() => {
          newIdsRef.current.delete(id)
          setEntries(prev => [...prev])  // re-render to drop animation class
        }, 1500)
        setEntries(prev => {
          const next = [{ ...log, _id: id }, ...prev]
          return next.length > MAX_ENTRIES ? next.slice(0, MAX_ENTRIES) : next
        })
      } catch (e) {
        console.warn('SSE parse failed', e)
      }
    })

    return () => {
      cancelled = true
      es.close()
    }
  }, [])

  const filtered = showDryRun ? entries : entries.filter(e => e.kind !== 'DRY_RUN')

  return (
    <div style={{
      marginTop: 24, padding: 18, background: '#FFFFFF',
      border: '1px solid #E5E7EB', borderRadius: 12,
    }}>
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        marginBottom: 14,
      }}>
        <h3 style={{ margin: 0, fontSize: '1.05rem' }}>
          실시간 거래 내역
          <span style={{
            marginLeft: 10, fontSize: '0.7rem', fontWeight: 500,
            color: connected ? '#059669' : '#9CA3AF',
          }}>
            {connected ? '● LIVE' : '○ 연결 중...'}
          </span>
        </h3>
        <label style={{ fontSize: '0.8rem', display: 'flex', alignItems: 'center', gap: 6 }}>
          <input type="checkbox" checked={showDryRun}
                 onChange={e => setShowDryRun(e.target.checked)} />
          드라이런 포함
        </label>
      </div>

      {error && <div className="error-msg">{error}</div>}

      {filtered.length === 0 ? (
        <div style={{ color: '#9CA3AF', padding: '20px 0', textAlign: 'center', fontSize: '0.85rem' }}>
          아직 실행 이력이 없습니다. 일임 시작 또는 즉시 리밸런싱 시 여기에 표시됩니다.
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxHeight: 480, overflowY: 'auto' }}>
          {filtered.map((e, i) => {
            const id = e._id || `${e.executedAt}-${e.strategyType}-${e.kind}-${i}`
            const isNew = newIdsRef.current.has(id)
            const badge = KIND_BADGE[e.kind] || { label: e.kind, bg: '#F3F4F6', fg: '#374151' }
            const stratLabel = STRATEGY_LABEL[e.strategyType] || e.strategyType
            return (
              <div key={id} style={{
                padding: '10px 12px', borderRadius: 8,
                border: '1px solid ' + (e.errorMessage ? '#FCA5A5' : '#E5E7EB'),
                background: e.errorMessage ? '#FEF2F2' : (isNew ? '#FEF9C3' : '#F9FAFB'),
                transition: 'background 1.5s ease-out',
                fontSize: '0.85rem',
              }}>
                <div style={{
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  marginBottom: 4,
                }}>
                  <span style={{ color: '#6B7280', fontFamily: 'monospace', fontSize: '0.78rem' }}>
                    {fmtTime(e.executedAt)}
                  </span>
                  <span style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                    <span style={{ fontWeight: 600, color: '#111827' }}>{stratLabel}</span>
                    <span style={{
                      fontSize: '0.7rem', padding: '2px 8px', borderRadius: 999,
                      background: badge.bg, color: badge.fg, fontWeight: 600,
                    }}>{badge.label}</span>
                  </span>
                </div>
                {e.errorMessage && (
                  <div style={{ color: '#991B1B', fontWeight: 500 }}>⚠ {e.errorMessage}</div>
                )}
                {e.signal?.rationale && !e.errorMessage && (
                  <div style={{ color: '#374151', marginBottom: 4 }}>{e.signal.rationale}</div>
                )}
                {e.orders?.length > 0 && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginTop: 4 }}>
                    {e.orders.map((o, j) => (
                      <div key={j} style={{
                        fontSize: '0.8rem',
                        color: o.success === false ? '#991B1B' : (o.side === 'BUY' ? '#DC2626' : o.side === 'SELL' ? '#059669' : '#6B7280'),
                      }}>
                        {o.success === false ? '✗' : '●'}{' '}
                        {o.side === 'BUY' ? '매수' : o.side === 'SELL' ? '매도' : o.side}{' '}
                        {o.name} ({o.symbol}) × {fmtQty(o.quantity)}
                        {o.price > 0 && <span style={{ color: '#6B7280' }}> @ {fmtKrw(o.price)}</span>}
                        {o.message && <span style={{ color: '#6B7280' }}> — {o.message}</span>}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
