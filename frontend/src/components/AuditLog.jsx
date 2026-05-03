import { useState, useEffect, useCallback } from 'react'
import useIsMobile from '../hooks/useIsMobile'

export default function AuditLog() {
  const isMobile = useIsMobile()
  const [entries, setEntries] = useState([])
  const [stats, setStats] = useState(null)
  const [levelFilter, setLevelFilter] = useState('')
  const [limit, setLimit] = useState(50)
  const [autoRefresh, setAutoRefresh] = useState(true)

  const loadData = useCallback(async () => {
    try {
      const params = new URLSearchParams({ limit: String(limit) })
      if (levelFilter) params.append('level', levelFilter)
      const [entriesRes, statsRes] = await Promise.all([
        fetch(`/api/audit-log?${params}`),
        fetch('/api/audit-log/stats'),
      ])
      const entriesJson = entriesRes.ok ? await entriesRes.json() : []
      setEntries(Array.isArray(entriesJson) ? entriesJson : [])
      setStats(statsRes.ok ? await statsRes.json() : null)
    } catch (e) { console.error(e) }
  }, [limit, levelFilter])

  useEffect(() => { loadData() }, [loadData])

  useEffect(() => {
    if (!autoRefresh) return
    const interval = setInterval(loadData, 10000)
    return () => clearInterval(interval)
  }, [autoRefresh, loadData])

  return (
    <div className="audit-page">
      {stats && (
        <div className="stats-grid audit-stats">
          <div className="stat-box">
            <div className="stat-label">Total</div>
            <div className="stat-value">{stats.totalEntries?.toLocaleString?.() ?? stats.totalEntries}</div>
          </div>
          <div className="stat-box">
            <div className="stat-label">Today</div>
            <div className="stat-value">{stats.todayCount?.toLocaleString?.() ?? stats.todayCount}</div>
          </div>
          <div className="stat-box">
            <div className="stat-label">Warnings</div>
            <div className={`stat-value ${stats.warnings > 0 ? 'warn' : 'muted'}`}>{stats.warnings}</div>
          </div>
          <div className="stat-box">
            <div className="stat-label">Errors</div>
            <div className={`stat-value ${stats.errors > 0 ? 'down' : 'muted'}`}>{stats.errors}</div>
          </div>
          <div className="stat-box">
            <div className="stat-label">Avg Duration</div>
            <div className="stat-value">{stats.avgDurationMs}<span className="stat-unit"> ms</span></div>
          </div>
          <div className="stat-box">
            <div className="stat-label">Auto Refresh</div>
            <div className="stat-value">
              <button
                className={`toggle-pill ${autoRefresh ? 'on' : 'off'}`}
                onClick={() => setAutoRefresh(!autoRefresh)}
              >
                <span className="toggle-pill-dot" />
                {autoRefresh ? 'ON' : 'OFF'}
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="audit-toolbar">
        <div className="audit-filter-group">
          <span className="audit-filter-label">LEVEL</span>
          <div className="period-buttons">
            {['', 'INFO', 'WARN', 'ERROR'].map(level => (
              <button
                key={level || 'ALL'}
                onClick={() => setLevelFilter(level)}
                className={levelFilter === level ? 'active' : ''}
              >
                {level || 'ALL'}
              </button>
            ))}
          </div>
        </div>
        <div className="audit-filter-group right">
          <select value={limit} onChange={e => setLimit(parseInt(e.target.value))} className="audit-limit-select">
            <option value={25}>25개</option>
            <option value={50}>50개</option>
            <option value={100}>100개</option>
            <option value={200}>200개</option>
          </select>
          <button onClick={loadData} className="btn-refresh">새로고침</button>
        </div>
      </div>

      {isMobile ? (
        <div className="audit-cards">
          {entries.map((e, i) => (
            <div key={e.id || i} className="audit-card">
              <div className="audit-card-top">
                <span className="audit-level-badge" data-level={e.level}>{e.level}</span>
                <span className="audit-card-time">
                  {e.timestamp ? new Date(e.timestamp).toLocaleTimeString('ko-KR', { hour12: false }) : '-'}
                </span>
                <span className={`audit-status ${e.responseStatus >= 500 ? 'down' : e.responseStatus >= 400 ? 'warn' : ''}`}>
                  {e.responseStatus}
                </span>
              </div>
              <div className="audit-card-action">{e.action}</div>
              <div className="audit-card-meta">
                <span className="audit-user">{e.user || '-'}</span>
                <span className="audit-duration">{e.durationMs}<span className="audit-unit">ms</span></span>
              </div>
            </div>
          ))}
          {entries.length === 0 && (
            <div className="audit-empty">기록된 로그가 없습니다.</div>
          )}
        </div>
      ) : (
        <div className="audit-table">
          <div className="audit-header">
            <span>Time</span>
            <span>Level</span>
            <span>Action</span>
            <span>User</span>
            <span>Status</span>
            <span>Duration</span>
          </div>
          {entries.map((e, i) => (
            <div key={e.id || i} className="audit-row">
              <span className="audit-time">
                {e.timestamp ? new Date(e.timestamp).toLocaleTimeString('ko-KR', { hour12: false }) : '-'}
              </span>
              <span>
                <span className="audit-level-badge" data-level={e.level}>{e.level}</span>
              </span>
              <span className="audit-action" title={e.action}>{e.action}</span>
              <span className="audit-user">{e.user || '-'}</span>
              <span className={`audit-status ${e.responseStatus >= 500 ? 'down' : e.responseStatus >= 400 ? 'warn' : ''}`}>
                {e.responseStatus}
              </span>
              <span className="audit-duration">{e.durationMs}<span className="audit-unit">ms</span></span>
            </div>
          ))}
          {entries.length === 0 && (
            <div className="audit-empty">기록된 로그가 없습니다.</div>
          )}
        </div>
      )}
    </div>
  )
}
