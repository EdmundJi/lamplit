import { test, expect } from '@playwright/test'

test.describe('小镇场景细节改造验证', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:5173/login')
    await page.fill('input[type="email"]', 'town-demo-1788569807@example.com')
    await page.fill('input[type="password"]', 'Correct-Horse-Battery-2026!')
    await page.click('button[type="submit"]')
    await page.waitForURL('**/town', { timeout: 10000 })
    await page.waitForTimeout(3000) // 等待场景完全加载
  })

  test('改造后全景对比', async ({ page }) => {
    await page.setViewportSize({ width: 1920, height: 1080 })

    // 截取改造后全景
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-after-wide.png',
      fullPage: false
    })
  })

  test('验证家具碰撞 - 无法穿过长椅', async ({ page }) => {
    const canvas = page.locator('canvas').first()

    // 点击长椅附近
    await canvas.click({ position: { x: 800, y: 500 } })
    await page.waitForTimeout(1500)

    // 截图验证角色停在长椅前
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-collision-bench.png'
    })
  })

  test('交互演示 - 坐在长椅上', async ({ page }) => {
    const canvas = page.locator('canvas').first()

    // 交互前
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-interaction-before.png'
    })

    // 点击长椅（可交互家具）
    await canvas.click({ position: { x: 700, y: 520 } })
    await page.waitForTimeout(1200)

    // 交互中（走向长椅）
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-interaction-during.png'
    })

    await page.waitForTimeout(1000)

    // 交互后（坐下，出现气泡）
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-interaction-after.png'
    })
  })

  test('场地配套家具密度验证', async ({ page }) => {
    const canvas = page.locator('canvas').first()

    // 健身房区域
    await canvas.click({ position: { x: 1400, y: 400 } })
    await page.waitForTimeout(1000)
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-venue-gym.png'
    })

    // 咖啡馆区域
    await canvas.click({ position: { x: 1700, y: 400 } })
    await page.waitForTimeout(1000)
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-venue-cafe.png'
    })

    // 公园区域
    await canvas.click({ position: { x: 600, y: 400 } })
    await page.waitForTimeout(1000)
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-venue-park.png'
    })
  })

  test('街道家具密度验证', async ({ page }) => {
    const canvas = page.locator('canvas').first()

    // 街道左段
    await canvas.click({ position: { x: 400, y: 500 } })
    await page.waitForTimeout(800)
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-street-left.png'
    })

    // 街道中段
    await canvas.click({ position: { x: 1000, y: 500 } })
    await page.waitForTimeout(800)
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-street-mid.png'
    })

    // 街道右段
    await canvas.click({ position: { x: 1500, y: 500 } })
    await page.waitForTimeout(800)
    await page.screenshot({
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/town-street-right.png'
    })
  })
})
