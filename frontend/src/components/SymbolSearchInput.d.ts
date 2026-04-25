// allowJs 환경에서 .jsx 컴포넌트의 props 를 .ts 호출부에 노출하기 위한 ambient 선언.
// 런타임 코드는 SymbolSearchInput.jsx 그대로. TS 입장에서만 이 .d.ts 가 진실.

import type { ComponentType } from 'react'

export interface SymbolSearchItem {
  symbol: string
  name?: string
  exchange?: string
}

export interface SymbolSearchInputProps {
  market: string
  selected: SymbolSearchItem | null
  onSelect: (item: SymbolSearchItem) => void
  onClear: () => void
  placeholder?: string
  /** 좁은 공간용 스타일 (자산 카드 등) */
  compact?: boolean
}

declare const SymbolSearchInput: ComponentType<SymbolSearchInputProps>
export default SymbolSearchInput
