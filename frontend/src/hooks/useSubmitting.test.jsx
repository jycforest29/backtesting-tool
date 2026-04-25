import { describe, expect, it, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useSubmitting } from './useSubmitting'

function Probe({ work }) {
  const { submitting, run } = useSubmitting()
  return (
    <>
      <button type="button" onClick={() => run(work)} disabled={submitting}>
        {submitting ? 'busy' : 'go'}
      </button>
    </>
  )
}

describe('useSubmitting', () => {
  it('연속 클릭이 들어와도 work 는 한 번만 실행된다 (race 차단)', async () => {
    const user = userEvent.setup()
    let resolveWork
    const work = vi.fn(() => new Promise((r) => { resolveWork = r }))

    render(<Probe work={work} />)
    const btn = screen.getByRole('button')

    // 두 번 빠르게 클릭. ref 가드가 없으면 둘 다 통과할 수 있는 시나리오.
    await user.click(btn)
    await user.click(btn)
    await user.click(btn)

    expect(work).toHaveBeenCalledTimes(1)
    expect(btn).toBeDisabled()
    expect(btn).toHaveTextContent('busy')

    await act(async () => { resolveWork() })

    expect(btn).not.toBeDisabled()
    expect(btn).toHaveTextContent('go')
  })

  it('work 가 throw 해도 lock 이 풀리고 예외는 swallow 된다', async () => {
    const consoleErr = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      const user = userEvent.setup()
      const work = vi.fn().mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce('ok')

      render(<Probe work={work} />)
      const btn = screen.getByRole('button')

      await act(async () => { await user.click(btn) })
      expect(btn).not.toBeDisabled() // finally 에서 lock 풀림
      expect(consoleErr).toHaveBeenCalledWith(
        '[useSubmitting] work failed:',
        expect.objectContaining({ message: 'boom' }),
      )

      await act(async () => { await user.click(btn) })
      expect(work).toHaveBeenCalledTimes(2)
    } finally {
      consoleErr.mockRestore()
    }
  })
})
