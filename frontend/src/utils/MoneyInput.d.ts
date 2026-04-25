// allowJs 환경에서 .jsx 컴포넌트의 props 를 .ts 호출부에 노출하기 위한 ambient 선언.
// 런타임 코드는 MoneyInput.jsx 그대로.

import type { ComponentType, CSSProperties } from 'react'

export interface MoneyInputProps {
  value: string | number
  /** display string ("1,234.56") 핸들러. onChangeNumber 와 둘 중 하나 사용 */
  onChange?: (v: string) => void
  /** 숫자 핸들러. 빈 입력은 '' 반환 */
  onChangeNumber?: (v: number | '') => void
  /** 우측 단위 배지 ('원', '%', '주' 등) */
  suffix?: string
  required?: boolean
  allowNegative?: boolean
  /** 소수점 입력 허용 (USD 등). 기본 false */
  allowDecimal?: boolean
  style?: CSSProperties
  className?: string
  /** 그 외 input HTML attributes */
  [extra: string]: unknown
}

declare const MoneyInput: ComponentType<MoneyInputProps>
export default MoneyInput
