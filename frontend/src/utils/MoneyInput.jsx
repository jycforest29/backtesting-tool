/**
 * 금액 입력용 input. 보이는 값은 콤마 삽입("1,234,567"), 콜백에는 raw 숫자 문자열("1234567") 전달.
 *
 * 사용:
 *   <MoneyInput value={amount} onChange={setAmount} />              // raw string 핸들러
 *   <MoneyInput value={amount} onChangeNumber={setAmount} />        // Number 핸들러
 *   <MoneyInput value={amount} suffix="원" />                       // 우측 단위 배지
 *
 * 브라우저 <input type="number">는 콤마 렌더링을 지원 안 하므로 type="text"로 구현.
 * inputMode="numeric"으로 모바일 키패드는 숫자 유지.
 */
import { useMemo } from 'react'

export default function MoneyInput({
  value,
  onChange,
  onChangeNumber,
  suffix,
  allowNegative = false,
  allowDecimal = false,
  style,
  className,
  ...rest
}) {
  const display = useMemo(() => formatDisplay(value, allowDecimal), [value, allowDecimal])

  const handleChange = (e) => {
    const raw = e.target.value
    // 허용: 숫자, 콤마(제거), 소수점(옵션), 음수부호(옵션)
    let cleaned = raw.replace(/,/g, '')
    if (!allowDecimal) cleaned = cleaned.replace(/\./g, '')
    if (!allowNegative) cleaned = cleaned.replace(/-/g, '')
    // 유효성: 빈 문자열, "-", "-12.", "12.34" 등 허용
    const pattern = allowDecimal
      ? (allowNegative ? /^-?\d*\.?\d*$/ : /^\d*\.?\d*$/)
      : (allowNegative ? /^-?\d*$/ : /^\d*$/)
    if (cleaned !== '' && !pattern.test(cleaned)) return
    onChange?.(cleaned)
    if (onChangeNumber) {
      const num = cleaned === '' || cleaned === '-' ? '' : Number(cleaned)
      onChangeNumber(Number.isFinite(num) ? num : '')
    }
  }

  const inputEl = (
    <input
      type="text"
      inputMode={allowDecimal ? 'decimal' : 'numeric'}
      value={display}
      onChange={handleChange}
      className={className}
      style={style}
      {...rest}
    />
  )

  if (!suffix) return inputEl

  return (
    <div style={{ position: 'relative', display: 'inline-flex', alignItems: 'center', width: '100%' }}>
      {inputEl}
      <span style={{
        position: 'absolute', right: 12,
        color: '#6b7280', fontSize: '0.85rem',
        pointerEvents: 'none',
      }}>{suffix}</span>
    </div>
  )
}

function formatDisplay(v, allowDecimal) {
  if (v == null || v === '') return ''
  const s = String(v)
  if (s === '-') return '-'
  // 소수점 입력 중일 때 "123." 처럼 점으로 끝나면 원본 유지
  if (allowDecimal && s.endsWith('.')) {
    const [intPart] = s.split('.')
    const signed = intPart.startsWith('-') ? '-' : ''
    const digits = intPart.replace('-', '')
    return signed + (digits ? Number(digits).toLocaleString('en-US') : '') + '.'
  }
  if (allowDecimal && s.includes('.')) {
    const [intPart, frac] = s.split('.')
    const signed = intPart.startsWith('-') ? '-' : ''
    const digits = intPart.replace('-', '')
    const intFormatted = digits ? Number(digits).toLocaleString('en-US') : '0'
    return signed + intFormatted + '.' + frac
  }
  const num = Number(s)
  if (!Number.isFinite(num)) return s
  return num.toLocaleString('en-US')
}
