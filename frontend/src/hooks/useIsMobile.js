import { useEffect, useState } from 'react'

const QUERY = '(max-width: 767.98px)'

export default function useIsMobile() {
  const [matches, setMatches] = useState(() =>
    typeof window !== 'undefined' && window.matchMedia(QUERY).matches
  )
  useEffect(() => {
    const mql = window.matchMedia(QUERY)
    const onChange = (e) => setMatches(e.matches)
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [])
  return matches
}