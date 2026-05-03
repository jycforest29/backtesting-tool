import { useEffect, useState } from 'react'

export function NowClock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(id)
  }, [])
  const pad = (n) => String(n).padStart(2, '0')
  const date = `${now.getFullYear()}.${pad(now.getMonth() + 1)}.${pad(now.getDate())}`
  const time = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
  return (
    <div className="ctx-clock">
      <span className="ctx-clock-date">{date}</span>
      <span className="ctx-clock-time num">{time}</span>
    </div>
  )
}

export function MarketStatusPill() {
  const now = new Date()
  const day = now.getDay()
  const hm = now.getHours() * 60 + now.getMinutes()
  const isWeekend = day === 0 || day === 6
  const isOpen = !isWeekend && hm >= 9 * 60 && hm < 15 * 60 + 30
  return (
    <span className={`ctx-status ${isOpen ? 'on' : 'off'}`}>
      <span className="ctx-status-dot" />
      KRX {isOpen ? '정규장' : '장마감'}
    </span>
  )
}