import { Component } from 'react'

/**
 * 트레이딩 화면은 컴포넌트 하나가 throw 해도 전체 화면이 백지가 되면 안 된다.
 * fallback 으로 격리하고, error 는 console 에 남겨 운영 환경에서 sourcemap 으로 추적 가능하게 한다.
 *
 * 외부 sink(Sentry 등) 미연동 — 연동 시 componentDidCatch 안에서 호출하면 된다.
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    console.error('[ErrorBoundary]', error, info?.componentStack)
  }

  reset = () => this.setState({ error: null })

  render() {
    const { error } = this.state
    if (!error) return this.props.children

    if (this.props.fallback) {
      return typeof this.props.fallback === 'function'
        ? this.props.fallback({ error, reset: this.reset })
        : this.props.fallback
    }

    return (
      <div role="alert" className="error-boundary">
        <h2>화면 렌더링 중 오류가 발생했습니다</h2>
        <pre style={{ whiteSpace: 'pre-wrap', color: 'var(--down)' }}>{String(error?.message ?? error)}</pre>
        <button type="button" onClick={this.reset} className="btn-submit" style={{ marginTop: 12 }}>
          다시 시도
        </button>
      </div>
    )
  }
}
