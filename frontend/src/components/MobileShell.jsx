import { useEffect, useState } from 'react'
import AlertsToggle from './AlertsToggle'
import { NowClock, MarketStatusPill } from './ShellWidgets'

export default function MobileShell({ nav, mode, onModeChange, active, children }) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (!open) return
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false) }
    document.body.style.overflow = 'hidden'
    document.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = ''
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const select = (key) => {
    onModeChange(key)
    setOpen(false)
  }

  return (
    <div className="shell-mobile">
      <header className="shell-mobile-top">
        <button
          className="hamburger"
          onClick={() => setOpen(true)}
          aria-label="메뉴 열기"
        >
          <span /><span /><span />
        </button>
        <div className="shell-mobile-title">
          <span className="shell-mobile-title-text">{active.label}</span>
          <span className="shell-mobile-title-hint">{active.hint}</span>
        </div>
        <div className="shell-mobile-top-right">
          <AlertsToggle />
        </div>
      </header>

      {open && (
        <>
          <div className="shell-mobile-backdrop" onClick={() => setOpen(false)} />
          <aside className="shell-mobile-drawer">
            <div className="brand">
              <div className="brand-mark">Q</div>
              <div className="brand-text">
                <div className="brand-title">QuantTerminal</div>
                <div className="brand-sub">KIS · DART · KRX</div>
              </div>
              <button className="drawer-close" onClick={() => setOpen(false)} aria-label="메뉴 닫기">×</button>
            </div>

            <div className="shell-mobile-status">
              <MarketStatusPill />
              <NowClock />
            </div>

            <nav className="nav">
              {nav.map(tab => (
                <button
                  key={tab.key}
                  className={`nav-item ${mode === tab.key ? 'active' : ''}`}
                  onClick={() => select(tab.key)}
                >
                  <span className="nav-label">{tab.label}</span>
                  <span className="nav-hint">{tab.hint}</span>
                </button>
              ))}
            </nav>

            <div className="shell-side-foot">
              <div className="legend">
                <span className="legend-row"><b className="up">▲</b> 상승 / 매수</span>
                <span className="legend-row"><b className="down">▼</b> 하락 / 매도</span>
              </div>
              <div className="build-tag">build · v1.0</div>
            </div>
          </aside>
        </>
      )}

      <main className="shell-mobile-main">{children}</main>
    </div>
  )
}
