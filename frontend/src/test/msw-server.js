import { setupServer } from 'msw/node'

// 기본 핸들러는 비워둠. 각 테스트가 server.use(...) 로 그때그때 등록.
// onUnhandledRequest:'error' 라 모르는 요청이 새면 즉시 실패.
export const server = setupServer()
