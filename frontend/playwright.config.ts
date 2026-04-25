import { defineConfig, devices } from '@playwright/test'

/**
 * 스모크 E2E. 단위/통합 테스트(Vitest) 가 못 잡는
 * "실제 브라우저에서 사용자가 폼을 채우고 결과를 본다" 흐름만 1~2개로 좁혀 둔다.
 * /api/* 는 page.route() 로 stub — 백엔드 부팅 없이 프론트만 검증.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['list']] : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
