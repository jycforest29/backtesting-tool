import { useEffect, useState } from 'react'
import { subscribeToasts, dismissToast } from '../toast'

const TYPE_STYLES = {
  info: { bg: '#EFF6FF', color: '#1D4ED8', border: '#BFDBFE' },
  warn: { bg: '#FFFBEB', color: '#92400E', border: '#FDE68A' },
  error: { bg: '#FEF2F2', color: '#991B1B', border: '#FECACA' },
  success: { bg: '#ECFDF5', color: '#065F46', border: '#A7F3D0' },
}

export default function ToastContainer() {
  const [toasts, setToasts] = useState([])

  useEffect(() => subscribeToasts(setToasts), [])

  return (
    <div style={{
      position: 'fixed', top: 16, right: 16, zIndex: 9999,
      display: 'flex', flexDirection: 'column', gap: 8,
      maxWidth: 380, pointerEvents: 'none',
    }}>
      {toasts.map(t => {
        const s = TYPE_STYLES[t.type] || TYPE_STYLES.info
        return (
          <div key={t.id} style={{
            background: s.bg, color: s.color, border: `1px solid ${s.border}`,
            borderRadius: 10, padding: '12px 14px', fontSize: '0.88rem',
            fontWeight: 500, boxShadow: '0 4px 12px rgba(0,0,0,0.08)',
            display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start',
            gap: 12, pointerEvents: 'auto',
            animation: 'toastSlide 0.2s ease-out',
          }}>
            <span style={{ flex: 1 }}>{t.message}</span>
            <button onClick={() => dismissToast(t.id)}
              style={{
                background: 'transparent', border: 'none', color: s.color,
                cursor: 'pointer', fontSize: '1.1rem', lineHeight: 1, padding: 0,
                opacity: 0.6,
              }}>&times;</button>
          </div>
        )
      })}
      <style>{`
        @keyframes toastSlide {
          from { opacity: 0; transform: translateX(20px); }
          to { opacity: 1; transform: translateX(0); }
        }
      `}</style>
    </div>
  )
}
