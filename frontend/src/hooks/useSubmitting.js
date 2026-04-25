import { useCallback, useRef, useState } from 'react'

/**
 * 폼 이중 제출 가드.
 *
 * 왜 ref + state 둘 다인가:
 *   - state 만 쓰면 React 18 자동 batching 때문에 같은 tick 안에서 두 번 클릭이
 *     모두 "submitting=false" 를 본 채로 통과할 수 있다 (race).
 *   - ref 는 동기적으로 즉시 true 가 되므로 두 번째 호출은 곧바로 차단된다.
 *   - state 는 disabled 같은 UI 표시용.
 *
 * 사용:
 *   const { submitting, run } = useSubmitting()
 *   const onSubmit = (e) => { e.preventDefault(); run(async () => { await api(...) }) }
 */
export function useSubmitting() {
  const [submitting, setSubmitting] = useState(false)
  const lockRef = useRef(false)

  const run = useCallback(async (fn) => {
    if (lockRef.current) return undefined
    lockRef.current = true
    setSubmitting(true)
    try {
      return await fn()
    } catch (err) {
      // 폼 submit 같은 fire-and-forget 호출자는 반환 promise 를 await 하지 않는다.
      // 여기서 swallow 하지 않으면 UnhandledRejection 으로 새어나가 운영 로그/감지를 흐린다.
      // 결과·예외를 다루고 싶은 호출자는 fn 내부에서 직접 try/catch 해야 한다.
      console.error('[useSubmitting] work failed:', err)
      return undefined
    } finally {
      lockRef.current = false
      setSubmitting(false)
    }
  }, [])

  return { submitting, run }
}
