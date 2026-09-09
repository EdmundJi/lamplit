import { defineConfig, devices } from '@playwright/test'

/** Deliberately separate from fixture tests: this talks to the configured local backend. */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'actual-town.spec.ts',
  outputDir: './test-results/actual-town',
  timeout: 120_000,
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5173',
    viewport: devices['Desktop Chrome'].viewport,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
})
