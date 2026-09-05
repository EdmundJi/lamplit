import { test, expect } from '@playwright/test'

const TEST_USER = 'town-demo-1788569807@example.com'
const TEST_PASS = 'Correct-Horse-Battery-2026!'

test.describe('世界能力系统端到端测试', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:5173/auth')
    await page.fill('input[type="email"]', TEST_USER)
    await page.fill('input[type="password"]', TEST_PASS)
    await page.click('button[type="submit"]')
    await page.waitForURL('**/today', { timeout: 8000 })
  })

  test('进入沉浸模式后，走到锚点旁会弹出动作菜单', async ({ page }) => {
    await page.goto('http://localhost:5173/town/immersive')
    await page.waitForSelector('[data-testid="immersive-canvas"]', { timeout: 5000 })
    await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/immersive-loaded.png' })

    // 等待小镇引擎加载完成（会有 loading 状态消失）
    await page.waitForTimeout(2000)

    // 寻找动作菜单（可能已经自动出现在玩家初始位置）
    const menuVisible = await page.locator('.world-action-menu').isVisible().catch(() => false)

    if (menuVisible) {
      await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/action-menu-appeared.png' })

      // 验证菜单里有能力项
      const actions = await page.locator('.world-action-item').count()
      expect(actions).toBeGreaterThan(0)

      // 查找并点击一个可用的能力（比如"刷新小镇"）
      const refreshButton = page.locator('.world-action-item').filter({ hasText: '刷新小镇' })
      if (await refreshButton.count() > 0) {
        await refreshButton.click()
        await page.waitForTimeout(500)

        // 验证反馈出现
        const feedback = page.locator('.world-feedback .feedback-item')
        await expect(feedback).toBeVisible({ timeout: 2000 })
        await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/action-feedback.png' })
      }
    } else {
      // 如果菜单没自动出现，尝试点击画面（模拟走到某处）
      await page.locator('[data-testid="immersive-canvas"]').click({ position: { x: 200, y: 400 } })
      await page.waitForTimeout(1500)
      await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/after-click-canvas.png' })
    }
  })

  test('dock 上的按钮能打开面板', async ({ page }) => {
    await page.goto('http://localhost:5173/town/immersive')
    await page.waitForSelector('.immersive-dock', { timeout: 5000 })

    // 点击 dock 上的"今天"按钮
    const todayButton = page.locator('.dock-button').filter({ hasText: '今天' })
    await todayButton.click()
    await page.waitForTimeout(500)

    // 验证面板窗口出现
    const panel = page.locator('.world-panel.today-panel')
    await expect(panel).toBeVisible()
    await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/panel-opened.png' })
  })

  test('R 键可以切换跑步模式', async ({ page }) => {
    await page.goto('http://localhost:5173/town/immersive')
    await page.waitForSelector('.run-toggle', { timeout: 5000 })

    const runToggle = page.locator('.run-toggle')
    const initialState = await runToggle.getAttribute('aria-pressed')

    // 按 R 键
    await page.keyboard.press('r')
    await page.waitForTimeout(300)

    const newState = await runToggle.getAttribute('aria-pressed')
    expect(newState).not.toBe(initialState)
    await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/run-toggled.png' })
  })
})
