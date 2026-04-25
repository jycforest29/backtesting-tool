import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import ErrorBoundary from './ErrorBoundary'

function Bomb({ throwOn }) {
  if (throwOn) throw new Error('rendering failed')
  return <div>safe</div>
}

describe('ErrorBoundary', () => {
  let consoleErr
  beforeEach(() => {
    // React 가 catch 된 에러를 그래도 console.error 로 한 번 출력하므로 noise 억제
    consoleErr = vi.spyOn(console, 'error').mockImplementation(() => {})
  })
  afterEach(() => consoleErr.mockRestore())

  it('자식이 throw 하면 fallback UI 와 에러 메시지를 보여준다', () => {
    render(
      <ErrorBoundary>
        <Bomb throwOn />
      </ErrorBoundary>,
    )
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(/rendering failed/)).toBeInTheDocument()
  })

  it('정상 자식은 그대로 렌더링', () => {
    render(
      <ErrorBoundary>
        <Bomb throwOn={false} />
      </ErrorBoundary>,
    )
    expect(screen.getByText('safe')).toBeInTheDocument()
  })

  it('reset 버튼으로 오류 상태가 해제된다', async () => {
    const user = userEvent.setup()
    // 첫 렌더는 fallback 으로 들어가는 경로를 검증한 뒤,
    // 자식을 안전한 것으로 갈아끼우고 reset 클릭으로 정상 children 이 보이는지 확인.
    const { rerender } = render(
      <ErrorBoundary>
        <Bomb throwOn />
      </ErrorBoundary>,
    )
    expect(screen.getByRole('alert')).toBeInTheDocument()

    // 자식을 안전한 것으로 갈아끼운 뒤 reset 클릭
    rerender(
      <ErrorBoundary>
        <Bomb throwOn={false} />
      </ErrorBoundary>,
    )
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(screen.getByText('safe')).toBeInTheDocument()
  })

  it('함수형 fallback 에 error 와 reset 을 전달', () => {
    render(
      <ErrorBoundary fallback={({ error }) => <p data-testid="custom">caught: {error.message}</p>}>
        <Bomb throwOn />
      </ErrorBoundary>,
    )
    expect(screen.getByTestId('custom')).toHaveTextContent('caught: rendering failed')
  })
})
