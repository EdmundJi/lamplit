import { defineConfig } from 'cypress'

/**
 * 小镇端到端测试。默认全部 API 走 cy.intercept 打桩（见 cypress/support/town.ts），
 * 所以只要 `pnpm dev` 跑着就能测，不需要后端和种子数据——小镇画面依赖等级/连续天数/
 * 今日任务这些数值，只有固定住它们，"三层楼""屋顶有两件装置"这类断言才有意义。
 *
 * 想对着真后端跑：CYPRESS_LIVE=1，再自己保证账号已登录（此时打桩全部跳过）。
 */
export default defineConfig({
  e2e: {
    baseUrl: process.env.CYPRESS_BASE_URL ?? 'http://localhost:5173',
    specPattern: 'cypress/e2e/**/*.cy.ts',
    supportFile: 'cypress/support/e2e.ts',
    fixturesFolder: 'cypress/fixtures',
    videosFolder: '../test-results/cypress/videos',
    screenshotsFolder: '../test-results/cypress/screenshots',
    video: false,
    viewportWidth: 1440,
    viewportHeight: 900,
    // Phaser 首帧要加载 1MB 引擎 + 图集，本机冷启动能到 15s 以上。
    defaultCommandTimeout: 12_000,
    retries: { runMode: 1, openMode: 0 },
    setupNodeEvents(on) {
      on('task', { log(message: string) { console.log('PROBE', message); return null } })
    },
  },
})
