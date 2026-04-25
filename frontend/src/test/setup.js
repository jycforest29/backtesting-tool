import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw-server'

// React 18 act(...) 환경 표시 (jsdom + RTL 조합에서 명시적으로 켜야 경고 사라짐)
globalThis.IS_REACT_ACT_ENVIRONMENT = true

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())
