import { defineConfig, devices } from '@playwright/test'
export default defineConfig({
  testDir: '../e2e',
  timeout: 180_000,
  use: { baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'http://127.0.0.1:5173', trace: 'retain-on-failure' },
  projects: [{ name: 'webkit-mobile', use: { ...devices['iPhone 13'] } }],
})
