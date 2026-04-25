import { describe, expect, it, vi, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { act, render, screen, waitFor } from '@testing-library/react'
import { server } from '../test/msw-server'

// --- @stomp/stompjs / sockjs-client 모듈 mock --------------------------------
// jsdom 에선 진짜 WS 가 의미 없고, 우리가 검증해야 할 것은
//   1) reconnectDelay 가 Client 생성 시 전달됐는지 (자동 재연결 기반)
//   2) onConnect 시 /topic/alerts 를 구독하는지
//   3) 연결 끊김(onDisconnect) 후 재연결 사이클이 호출되는지 (수동 트리거)
const stompState = {
  lastConfig: null,
  lastClient: null,
  subscribedTo: [],
}

vi.mock('@stomp/stompjs', () => {
  class FakeClient {
    constructor(config) {
      this.config = config
      this.subscriptions = []
      stompState.lastConfig = config
      stompState.lastClient = this
    }
    activate() {
      // 비동기 연결 시뮬레이션
      queueMicrotask(() => this.config.onConnect?.())
    }
    deactivate() {
      this.config.onDisconnect?.()
    }
    subscribe(dest, cb) {
      stompState.subscribedTo.push(dest)
      this.subscriptions.push({ dest, cb })
      return { unsubscribe: () => {} }
    }
    // 테스트 헬퍼: 강제 disconnect + reconnect
    _simulateDisconnect() { this.config.onDisconnect?.() }
    _simulateReconnect() { this.config.onConnect?.() }
  }
  return { Client: FakeClient }
})

vi.mock('sockjs-client/dist/sockjs', () => ({
  default: vi.fn(() => ({})),
}))

// 후순위로 import (mock 적용 후)
import AlertsToggle from './AlertsToggle'

describe('AlertsToggle WebSocket', () => {
  beforeEach(() => {
    stompState.lastConfig = null
    stompState.lastClient = null
    stompState.subscribedTo = []
  })

  it('마운트 시 STOMP Client 가 reconnectDelay 와 함께 생성되고 /topic/alerts 를 구독', async () => {
    server.use(
      http.get('/api/trading/scanner/volume', () => HttpResponse.json({ enabled: false })),
    )

    render(<AlertsToggle />)

    // enabled 가 채워지면 버튼이 나타남
    await waitFor(() => expect(screen.getByRole('button')).toBeInTheDocument())

    expect(stompState.lastConfig).not.toBeNull()
    expect(stompState.lastConfig.reconnectDelay).toBe(5000)
    expect(typeof stompState.lastConfig.webSocketFactory).toBe('function')
    expect(stompState.subscribedTo).toContain('/topic/alerts')
  })

  it('onDisconnect 후 onConnect 가 다시 호출되면 동일 destination 을 재구독한다', async () => {
    server.use(
      http.get('/api/trading/scanner/volume', () => HttpResponse.json({ enabled: false })),
    )

    render(<AlertsToggle />)
    await waitFor(() => expect(screen.getByRole('button')).toBeInTheDocument())

    const subsBefore = stompState.subscribedTo.length

    await act(async () => {
      stompState.lastClient._simulateDisconnect()
      stompState.lastClient._simulateReconnect()
    })

    // 재연결 시 subscribe 콜백 안에서 /topic/alerts 를 다시 등록해야 함
    expect(stompState.subscribedTo.length).toBeGreaterThan(subsBefore)
    expect(stompState.subscribedTo.filter((d) => d === '/topic/alerts').length).toBeGreaterThanOrEqual(2)
  })
})
