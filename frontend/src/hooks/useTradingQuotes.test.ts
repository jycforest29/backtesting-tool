import { describe, it, expect } from 'vitest'
import { mergeQuotes } from './useTradingQuotes'
import type { Quote } from '../api/trading'

const q = (symbol: string, price: string): Quote => ({
  symbol, name: symbol, price, change: '0', changePercent: '0', currency: 'KRW',
})

describe('mergeQuotes', () => {
  it('빈 prev 에 incoming 그대로 → flash=true 표식', () => {
    const merged = mergeQuotes([], [q('A', '100'), q('B', '200')])
    expect(merged).toHaveLength(2)
    expect(merged.every(m => m.flash === true)).toBe(true)
  })

  it('기존 종목은 새 가격으로 갱신 + flash 부착', () => {
    const merged = mergeQuotes([{ ...q('A', '100'), flash: false }], [q('A', '101')])
    expect(merged).toHaveLength(1)
    expect(merged[0].price).toBe('101')
    expect(merged[0].flash).toBe(true)
  })

  it('incoming 에 없는 종목은 prev 그대로 보존 (flash 변화 없음)', () => {
    const prev = [{ ...q('A', '100'), flash: false }, { ...q('B', '200'), flash: false }]
    const merged = mergeQuotes(prev, [q('A', '101')])
    expect(merged).toHaveLength(2)
    const a = merged.find(m => m.symbol === 'A')!
    const b = merged.find(m => m.symbol === 'B')!
    expect(a.price).toBe('101')
    expect(a.flash).toBe(true)
    expect(b.price).toBe('200')
    expect(b.flash).toBe(false)
  })

  it('incoming 에 같은 symbol 이 두 번 오면 마지막 것이 이긴다 (Map.set 의미)', () => {
    const merged = mergeQuotes([], [q('A', '100'), q('A', '999')])
    expect(merged).toHaveLength(1)
    expect(merged[0].price).toBe('999')
  })
})
