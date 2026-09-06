import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e/town/visual',
  outputDir: '../test-results/town-visual/runs',
  timeout: 90_000,
  workers: 1,
  reporter: [['list'], ['html', { outputFolder: '../test-results/town-visual/report', open: 'never' }]],
  use: {
    baseURL: process.env.TOWN_BASE_URL ?? 'http://127.0.0.1:5176',
    viewport: { width: 1440, height: 900 },
    actionTimeout: 10_000,
    screenshot: 'on',
    trace: 'on',
    video: 'on',
  },
  webServer: process.env.TOWN_BASE_URL ? undefined : {
    command: './node_modules/.bin/vite --host 127.0.0.1 --port 5176 --strictPort',
    url: 'http://127.0.0.1:5176',
    reuseExistingServer: !process.env.CI,
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
})
