// 금액/수량 표시 공통 유틸. 전부 콤마 삽입 보장.
// TS 점진 도입의 첫 파일 — 다른 컴포넌트는 동작 변화 없이 .ts 의 타입 추론만 받는다.

export type Currency = 'KRW' | 'JPY' | 'USD'
type NumLike = number | string | null | undefined

const toNum = (v: NumLike): number | null => {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** 정수 콤마 (소수점 버림). "1234" → "1,234". 실패 시 "-". */
export function fmtInt(v: NumLike): string {
  const n = toNum(v)
  if (n == null) return '-'
  return Math.trunc(n).toLocaleString('en-US')
}

/** 수량/주식 수. 정수 콤마 + 선택적 단위. fmtQty(1234) = "1,234", fmtQty(1234, '주') = "1,234주". */
export function fmtQty(v: NumLike, unit = ''): string {
  const n = toNum(v)
  if (n == null) return '-'
  const s = Math.trunc(n).toLocaleString('en-US')
  return unit ? s + unit : s
}

/** 통화 금액. currency에 따라 locale/소수/기호 자동.
 *  KRW → "₩1,234,567"
 *  JPY → "¥1,234,567"
 *  USD(또는 미지정) → "$1,234,567.89"
 */
export function fmtMoney(v: NumLike, currency: Currency = 'KRW'): string {
  const n = toNum(v)
  if (n == null) return '-'
  if (currency === 'KRW') {
    return '₩' + n.toLocaleString('ko-KR', { maximumFractionDigits: 0 })
  }
  if (currency === 'JPY') {
    return '¥' + n.toLocaleString('ja-JP', { maximumFractionDigits: 0 })
  }
  return '$' + n.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

/** 원화 단축 별칭. */
export const fmtKrw = (v: NumLike): string => fmtMoney(v, 'KRW')

/**
 * 가격 변동치(증감액). 부호 + 콤마. 소수 digits는 통화별 기본 사용.
 *  fmtDelta(1234.56, 'KRW') = "+1,235" (KRW는 정수)
 *  fmtDelta(-12.34, 'USD') = "-12.34"
 */
export function fmtDelta(v: NumLike, currency: Currency = 'KRW'): string {
  const n = toNum(v)
  if (n == null) return '-'
  const sign = n >= 0 ? '+' : ''
  if (currency === 'KRW' || currency === 'JPY') {
    return sign + Math.round(n).toLocaleString('en-US')
  }
  return sign + n.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

/** 퍼센트. fmtPct(1.234) = "+1.23%". null→"-". */
export function fmtPct(v: NumLike, digits = 2): string {
  const n = toNum(v)
  if (n == null) return '-'
  const sign = n >= 0 ? '+' : ''
  return sign + n.toFixed(digits) + '%'
}
