import { useState, useEffect, useCallback } from 'react'

const LEVEL_COLORS = {
  INFO: { bg: '#EFF6FF', color: '#2563EB' },
  WARN: { bg: '#FFFBEB', color: '#D97706' },
  ERROR: { bg: '#FEF2F2', color: '#DC2626' },
}

export default function AuditLog() {
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
      setEntries(await entriesRes.json())
      setStats(await statsRes.json())
    } catch (e) { console.error(e) }
  }, [limit, levelFilter])

  useEffect(() => { loadData() }, [loadData])

  useEffect(() => {
    if (!autoRefresh) return
    const interval = setInterval(loadData, 10000) // refresh every 10s
    return () => clearInterval(interval)
  }, [autoRefresh, loadData])

  return (
    <div>
      {/* Stats */}
      {stats && (
        <div className="form-card">
          <div className="stats-grid" style={{ marginBottom: 0 }}>
            <div className="stat-box">
              <div className="stat-label">Total Logs</div>
              <div className="stat-value">{stats.totalEntries}</div>
            </div>
            <div className="stat-box">
              <div className="stat-label">Today</div>
              <div className="stat-value">{stats.todayCount}</div>
            </div>
            <div className="stat-box">
              <div className="stat-label">Errors</div>
              <div className="stat-value" style={{ color: stats.errors > 0 ? '#dc2626' : '#059669' }}>{stats.errors}</div>
            </div>
            <div className="stat-box">
              <div className="stat-label">Warnings</div>
              <div className="stat-value" style={{ color: stats.warnings > 0 ? '#d97706' : '#059669' }}>{stats.warnings}</div>
            </div>
            <div className="stat-box">
              <div className="stat-label">Avg Duration</div>
              <div className="stat-value">{stats.avgDurationMs}ms</div>
            </div>
            <div className="stat-box">
              <div className="stat-label">Auto Refresh</div>
              <div className="stat-value">
                <button onClick={() => setAutoRefresh(!autoRefresh)}
                  style={{
                    padding: '4px 12px', borderRadius: 8, border: '1.5px solid',
                    borderColor: autoRefresh ? '#10B981' : '#d1d5db',
                    background: autoRefresh ? '#ECFDF5' : '#fff',
                    color: autoRefresh ? '#059669' : '#6b7280',
                    fontSize: '0.82rem', fontWeight: 600, cursor: 'pointer', fontFamily: 'inherit'
                  }}>
                  {autoRefresh ? 'ON' : 'OFF'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Filters */}
      <div className="form-card" style={{ padding: '16px 24px' }}>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
          <span style={{ fontSize: '0.82rem', fontWeight: 600, color: '#6b7280' }}>FILTER:</span>
          {['', 'INFO', 'WARN', 'ERROR'].map(level => (
            <button key={level} onClick={() => setLevelFilter(level)}
              className={`period-btn ${levelFilter === level ? 'active' : ''}`}
              style={{ padding: '6px 12px' }}>
              {level || 'All'}
            </button>
          ))}
          <select value={limit} onChange={e => setLimit(parseInt(e.target.value))}
            style={{ marginLeft: 'auto', padding: '6px 12px', borderRadius: 8, border: '1.5px solid #e5e7eb', fontSize: '0.85rem', fontFamily: 'inherit' }}>
            <option value={25}>25개</option>
            <option value={50}>50개</option>
            <option value={100}>100개</option>
            <option value={200}>200개</option>
          </select>
          <button onClick={loadData} className="btn-refresh">새로고침</button>
        </div>
      </div>

      {/* Log table */}
      <div className="result-card" style={{ padding: 0, overflow: 'hidden' }}>
        <div className="audit-table">
          <div className="audit-header">
            <span>Time</span>
            <span>Level</span>
            <span>Action</span>
            <span>User</span>
            <span>Status</span>
            <span>Duration</span>
          </div>
          {entries.map((e, i) => {
            const lc = LEVEL_COLORS[e.level] || LEVEL_COLORS.INFO
            return (
              <div key={e.id || i} className="audit-row">
                <span className="audit-time">
                  {e.timestamp ? new Date(e.timestamp).toLocaleTimeString() : '-'}
                </span>
                <span>
                  <span className="audit-level-badge" style={{ background: lc.bg, color: lc.color }}>
                    {e.level}
                  </span>
                </span>
                <span className="audit-action">{e.action}</span>
                <span className="audit-user">{e.user}</span>
                <span className={`audit-status ${e.responseStatus >= 400 ? 'error' : ''}`}>
                  {e.responseStatus}
                </span>
                <span className="audit-duration">{e.durationMs}ms</span>
              </div>
            )
          })}
          {entries.length === 0 && (
            <div style={{ padding: 30, textAlign: 'center', color: '#9ca3af' }}>
              아직 기록된 로그가 없습니다.
            </div>
          )}
        </div>
      </div>
    </div>
  )
}