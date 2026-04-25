import { test, expect } from '@playwright/test'

/**
 * 포트폴리오 백테스트 골든패스.
 *
 * 검증 포인트:
 *   1. 템플릿 칩 클릭으로 자산이 채워진다
 *   2. 시작일/투자금 입력 후 제출 버튼이 활성화된다
 *   3. 제출 버튼 연속 클릭 시에도 백엔드 호출은 1회만 (useSubmitting ref lock)
 *   4. 응답이 도착하면 결과 패널이 표시된다
 *
 * /api/portfolio-backtest 와 /api/search 는 stub 으로 차단 — 백엔드 부팅 불필요.
 */
test.describe('포트폴리오 백테스트 흐름', () => {

  test('템플릿 적용 → 제출 → 결과 표시 + 이중 제출 방지', async ({ page }) => {
    let backtestCalls = 0

    // 헤더 AlertsToggle 가 마운트시 호출하는 endpoint — vite proxy ECONNREFUSED 노이즈 차단용
    await page.route('**/api/trading/scanner/volume', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ enabled: false }),
      }),
    )

    await page.route('**/api/search**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    )

    await page.route('**/api/portfolio-backtest', async (route) => {
      backtestCalls += 1
      // 응답 지연을 줘야 두 번째 클릭이 들어올 시간이 생김 (race 시뮬레이션)
      await new Promise((r) => setTimeout(r, 500))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          finalValue: 1500000,
          totalReturn: 50.0,
          annualizedReturn: 12.3,
          maxDrawdown: -8.5,
          sharpeRatio: 1.2,
          dailyValues: [
            { date: '2020-01-01', value: 1000000 },
            { date: '2024-12-31', value: 1500000 },
          ],
          assetReturns: [],
        }),
      })
    })

    await page.goto('/')

    // 포트폴리오 모드 (기본) 가 활성인지 확인 — 탭 버튼만 정확 매칭 (제출 버튼 텍스트와 충돌 회피)
    await expect(page.getByRole('button', { name: '포트폴리오', exact: true })).toHaveClass(/active/)

    // 템플릿 칩 클릭 — '60/40 (전통적)'
    await page.getByRole('button', { name: '60/40 (전통적)' }).click()

    // 시작일/투자금 입력
    await page.locator('input[type="date"]').fill('2020-01-01')
    // MoneyInput 은 콤마 표시되는 텍스트 입력. placeholder="1,000,000" 으로 식별
    await page.getByPlaceholder('1,000,000').fill('1000000')

    const submitBtn = page.getByRole('button', { name: '포트폴리오 백테스트' })
    await expect(submitBtn).toBeEnabled()

    // 이중 제출: 빠르게 3회 클릭. useSubmitting 이 lock 해야 함.
    await Promise.all([submitBtn.click(), submitBtn.click(), submitBtn.click()])

    // 분석 중 표시 + 버튼 disabled
    await expect(page.getByRole('button', { name: '분석 중...' })).toBeDisabled()

    // 결과 패널 — finalValue 가 화면에 보여야 함 (KRW formatter 가 ₩1,500,000 으로 렌더)
    await expect(page.locator('text=/1,500,000/')).toBeVisible({ timeout: 5_000 })

    // 핵심 검증: race 가 차단되어 백엔드는 1회만 호출
    expect(backtestCalls).toBe(1)
  })
})
