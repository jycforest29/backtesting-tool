import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { OrderForm } from './OrderForm'
import { tradingApi } from '../../api/trading'

// SymbolSearchInput 은 내부 fetch 가 있어 stub.
// onSelect 를 클릭으로 트리거하는 단순 버튼으로 대체 — selected 상태 주입용.
vi.mock('../SymbolSearchInput', () => ({
  default: ({ selected, onSelect }: { selected: unknown; onSelect: (s: unknown) => void }) => (
    <button data-testid="symbol-stub"
      onClick={() => onSelect({ symbol: 'TEST', name: 'TEST Co' })}>
      {selected ? 'picked' : 'pick'}
    </button>
  ),
}))

// MoneyInput 은 className 으로 식별 가능한 plain input 으로 대체.
vi.mock('../../utils/MoneyInput', () => ({
  default: ({ value, onChange }: { value: string; onChange: (v: string) => void }) => (
    <input className="money-stub" value={value ?? ''} onChange={e => onChange(e.target.value)} />
  ),
}))

vi.mock('../../api/trading', () => ({
  tradingApi: { order: vi.fn() },
}))

const fillForm = () => {
  fireEvent.click(screen.getByTestId('symbol-stub'))
  const inputs = document.querySelectorAll<HTMLInputElement>('input.money-stub')
  fireEvent.change(inputs[0], { target: { value: '10' } })
  fireEvent.change(inputs[1], { target: { value: '50000' } })
}

describe('OrderForm — 이중 제출 가드 (useSubmitting)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('rapid 3회 submit 에도 tradingApi.order 는 1번만 호출', async () => {
    // resolve 를 일부러 늦춰 락이 풀리기 전에 추가 submit 을 시도하는 시나리오 재현
    let resolveOrder!: (v: { success: true; orderNo: string }) => void
    vi.mocked(tradingApi.order).mockImplementation(
      () => new Promise(r => { resolveOrder = r as typeof resolveOrder })
    )

    render(<OrderForm paperTrading={true} />)
    fillForm()

    const form = document.querySelector('form')!
    fireEvent.submit(form)
    fireEvent.submit(form)
    fireEvent.submit(form)

    expect(tradingApi.order).toHaveBeenCalledTimes(1)

    // 락 해제 후에는 다시 호출 가능해야 정상 (회복성 검증)
    await act(async () => {
      resolveOrder({ success: true, orderNo: 'OK1' })
      await Promise.resolve()
    })
  })

  it('실전 계좌(paperTrading=false) + confirm 거부 → fetch 호출 안 함', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)

    render(<OrderForm paperTrading={false} />)
    fillForm()
    fireEvent.submit(document.querySelector('form')!)

    expect(window.confirm).toHaveBeenCalled()
    expect(tradingApi.order).not.toHaveBeenCalled()
  })

  it('실전 계좌 + confirm 승인 → fetch 호출됨', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(tradingApi.order).mockResolvedValue({ success: true, orderNo: 'OK2' })

    render(<OrderForm paperTrading={false} />)
    fillForm()
    await act(async () => {
      fireEvent.submit(document.querySelector('form')!)
      await Promise.resolve()
    })

    expect(tradingApi.order).toHaveBeenCalledTimes(1)
  })
})
