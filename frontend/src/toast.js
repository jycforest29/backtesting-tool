// 전역 토스트 시스템. window.showToast(message, { type, durationMs })로 호출.

let counter = 0
const listeners = new Set()
let toasts = []

const notify = () => listeners.forEach(fn => fn(toasts))

export function subscribeToasts(fn) {
  listeners.add(fn)
  fn(toasts)
  return () => listeners.delete(fn)
}

export function pushToast(message, { type = 'info', durationMs = 4000 } = {}) {
  const id = ++counter
  const toast = { id, message, type }
  toasts = [...toasts, toast]
  notify()
  if (durationMs > 0) {
    setTimeout(() => dismissToast(id), durationMs)
  }
  return id
}

export function dismissToast(id) {
  toasts = toasts.filter(t => t.id !== id)
  notify()
}

// 전역 fetch 래퍼: 429면 자동 토스트
const origFetch = window.fetch.bind(window)
window.fetch = async (...args) => {
  const res = await origFetch(...args)
  if (res.status === 429) {
    try {
      const clone = res.clone()
      const body = await clone.json()
      const sec = body?.retryAfterMs ? (body.retryAfterMs / 1000).toFixed(1) : '잠시'
      pushToast(`${body?.error || '호출 한도 초과'} — ${sec}초 후 다시 시도하세요`, {
        type: 'warn', durationMs: 5000,
      })
    } catch {
      pushToast('호출 한도 초과 — 잠시 후 다시 시도하세요', { type: 'warn' })
    }
  }
  return res
}

if (typeof window !== 'undefined') {
  window.showToast = pushToast
}
