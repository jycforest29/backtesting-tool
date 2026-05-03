import AlertsToggle from './AlertsToggle'
import { NowClock, MarketStatusPill } from './ShellWidgets'

export default function DesktopShell({ nav, mode, onModeChange, active, children }) {
  return (
    <div className="app">
      <aside className="shell-side">
        <div className="brand">
          <div className="brand-mark">Q</div>
          <div className="brand-text">
            <div className="brand-title">QuantTerminal</div>
            <div className="brand-sub">KIS · DART · KRX</div>
          </div>
        </div>

        <nav className="nav">
          {nav.map(tab => (
            <button
              key={tab.key}
              className={`nav-item ${mode === tab.key ? 'active' : ''}`}
              onClick={() => onModeChange(tab.key)}
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

      <header className="shell-top">
        <div className="ctx-left">
          <div className="ctx-crumb">
            <span className="ctx-crumb-root">Workspace</span>
            <span className="ctx-crumb-sep">/</span>
            <span className="ctx-crumb-leaf">{active.label}</span>
          </div>
          <div className="ctx-title">
            <h1>{active.label}</h1>
            <span className="ctx-hint">{active.hint}</span>
          </div>
        </div>
        <div className="ctx-right">
          <MarketStatusPill />
          <NowClock />
          <div className="ctx-alerts">
            <AlertsToggle />
          </div>
        </div>
      </header>

      <main className="shell-main">{children}</main>
    </div>
  )
}
