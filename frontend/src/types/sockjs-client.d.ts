// sockjs-client 의 dist subpath import 에 대한 ambient declaration.
// '@types/sockjs-client' 를 추가할 수도 있지만, 우리는 brower-only 의 dist/sockjs 만 쓰고
// API 표면이 좁아 별도 패키지 추가 비용이 더 큼. 최소 ambient 로 처리.

declare module 'sockjs-client/dist/sockjs' {
  // SockJS 는 WebSocket 호환 클래스. 우리는 STOMP Client 의 webSocketFactory 에만 사용.
  // STOMP 가 내부적으로 호출하는 메서드만 알면 충분하므로 광역 any 로 둔다.
  // (학습 포인트: 외부 의존이 좁고 표면이 작으면 ambient any 가 실용적)

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const SockJS: new (url: string, _reserved?: unknown, options?: Record<string, unknown>) => any
  export default SockJS
}
