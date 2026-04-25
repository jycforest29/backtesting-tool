import { describe, expect, it } from 'vitest'
import { fmtInt, fmtQty, fmtMoney, fmtKrw, fmtDelta, fmtPct } from './formatters'

describe('formatters', () => {
  describe('fmtInt', () => {
    it('정수 콤마 + 소수점 버림', () => {
      expect(fmtInt(1234567)).toBe('1,234,567')
      expect(fmtInt('1234.9')).toBe('1,234')
    })
    it('null/빈 문자열/NaN 은 "-"', () => {
      expect(fmtInt(null)).toBe('-')
      expect(fmtInt(undefined)).toBe('-')
      expect(fmtInt('')).toBe('-')
      expect(fmtInt('abc')).toBe('-')
    })
  })

  describe('fmtQty', () => {
    it('단위 suffix 옵션', () => {
      expect(fmtQty(1234)).toBe('1,234')
      expect(fmtQty(1234, '주')).toBe('1,234주')
    })
  })

  describe('fmtMoney / fmtKrw', () => {
    it('KRW 는 원화 기호 + 소수 0', () => {
      expect(fmtMoney(1234567, 'KRW')).toBe('₩1,234,567')
      expect(fmtKrw(1000)).toBe('₩1,000')
    })
    it('JPY 는 엔화 기호 + 소수 0', () => {
      expect(fmtMoney(1234567, 'JPY')).toBe('¥1,234,567')
    })
    it('USD/기본 은 달러 + 소수 2 고정', () => {
      expect(fmtMoney(1234.5, 'USD')).toBe('$1,234.50')
      expect(fmtMoney(0.1)).toMatch(/^[₩]/) // 기본 KRW
    })
  })

  describe('fmtDelta', () => {
    it('양수는 + 부호', () => {
      expect(fmtDelta(1234.6, 'KRW')).toBe('+1,235')
      expect(fmtDelta(0, 'USD')).toBe('+0.00')
    })
    it('음수는 - 부호 (Number 자체)', () => {
      expect(fmtDelta(-12.34, 'USD')).toBe('-12.34')
    })
  })

  describe('fmtPct', () => {
    it('소수 자릿수 + 부호', () => {
      expect(fmtPct(1.234)).toBe('+1.23%')
      expect(fmtPct(-3, 1)).toBe('-3.0%')
      expect(fmtPct(null)).toBe('-')
    })
  })
})
