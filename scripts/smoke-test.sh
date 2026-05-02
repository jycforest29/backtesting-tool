#!/usr/bin/env bash
# 배포 직후 핵심 경로가 살아있는지 검증한다. 실패 시 즉시 종료 (exit ≠ 0) →
# 향후 deploy.sh 가 이 결과로 배포 성공/실패를 판정할 수 있게 한다.
#
# 사용:
#   ./scripts/smoke-test.sh                       # 기본값 http://localhost (서버 내부에서)
#   ./scripts/smoke-test.sh http://13.125.163.209 # 외부에서 운영 검증
#
# 검증 항목:
#   1. frontend 셸 (GET /) — nginx 가 떠있고 index.html 서빙되는지
#   2. /api/quant/strategies — nginx X-API-Key 주입 → backend 인증 통과 → 200 + JSON
#   3. backend 직접 (호스트 내부 한정) — 8080 actuator/health 200

set -euo pipefail

HOST="${1:-http://localhost}"
TIMEOUT=10
TMP_BODY=$(mktemp)
trap 'rm -f "$TMP_BODY"' EXIT

echo "🔎 smoke test against: $HOST"
echo

# ── 1. frontend 셸 ────────────────────────────────────────
status=$(curl -s -o /dev/null -w '%{http_code}' --max-time "$TIMEOUT" "$HOST/")
if [ "$status" != "200" ]; then
  echo "❌ FAIL [1/3] frontend shell: GET / → $status (기대 200)"
  exit 1
fi
echo "✅ [1/3] frontend shell: GET /  →  200"

# ── 2. nginx → backend 인증 주입 ──────────────────────────
# 헤더 없이 호출해도 nginx 가 X-API-Key 를 주입해 200 이 떨어져야 정상.
# 401 이 나면 envsubst / proxy_set_header 둘 중 하나가 깨진 것.
status=$(curl -s -o "$TMP_BODY" -w '%{http_code}' --max-time "$TIMEOUT" "$HOST/api/quant/strategies")
if [ "$status" != "200" ]; then
  echo "❌ FAIL [2/3] /api/quant/strategies: $status (기대 200 — nginx X-API-Key 주입 확인 필요)"
  echo "    응답 본문 (앞 200자):"
  head -c 200 "$TMP_BODY"
  echo
  exit 1
fi
# JSON 인지 1바이트 확인 — SPA fallback (HTML 200) 받았는지 거르기
if ! head -c 1 "$TMP_BODY" | grep -qE '^[\[\{]'; then
  echo "❌ FAIL [2/3] /api/quant/strategies: 200 이지만 JSON 아님 (SPA fallback 의심)"
  echo "    응답 본문 (앞 100자):"
  head -c 100 "$TMP_BODY"
  echo
  exit 1
fi
echo "✅ [2/3] /api/quant/strategies (인증)  →  200 + JSON"

# ── 3. backend 직접 (호스트 내부 한정) ────────────────────
case "$HOST" in
  http://localhost*|http://127.0.0.1*)
    status=$(curl -s -o /dev/null -w '%{http_code}' --max-time "$TIMEOUT" \
              http://127.0.0.1:8080/actuator/health 2>/dev/null || echo "fail")
    if [ "$status" = "200" ]; then
      echo "✅ [3/3] backend direct: 127.0.0.1:8080/actuator/health  →  200"
    else
      echo "⚠️  [3/3] backend direct: $status (소프트 경고 — 8080 비노출이거나 헬스체크 startup 중)"
    fi
    ;;
  *)
    echo "⏭️  [3/3] backend direct: 외부 호스트라 스킵 (서버 내부에서 실행 시 자동 검증됨)"
    ;;
esac

echo
echo "🟢 모든 smoke test 통과"