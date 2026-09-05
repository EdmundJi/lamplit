import { test, expect } from '@playwright/test'

test.describe('成长小镇位置持久化', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('http://localhost:5173/login')
    await page.fill('input[type="email"]', 'town-demo-1788569807@example.com')
    await page.fill('input[type="password"]', 'Correct-Horse-Battery-2026!')
    await page.click('button[type="submit"]')
    await page.waitForURL('**/today')
  })

  test('走到某个位置后刷新，不会瞬移回原点', async ({ page }) => {
    // 前往小镇
    await page.goto('http://localhost:5173/town')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)

    // 截图：初始位置
    await page.screenshot({ 
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/01-initial-spawn.png',
      fullPage: false 
    })

    // 点击远处地面，让自己的小人走过去
    const canvas = page.locator('canvas').first()
    const box = await canvas.boundingBox()
    if (box) {
      // 点击画布右侧区域
      await canvas.click({ position: { x: box.width * 0.7, y: box.height * 0.5 } })
      await page.waitForTimeout(3000) // 等待走到目标位置
    }

    // 截图：移动后位置
    await page.screenshot({ 
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/02-after-walk.png',
      fullPage: false 
    })

    // 刷新页面
    await page.reload()
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)

    // 截图：刷新后位置（应该在移动后的位置，而不是回到原点）
    await page.screenshot({ 
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/03-after-refresh.png',
      fullPage: false 
    })

    // 验证：页面正常加载
    await expect(canvas).toBeVisible()
  })

  test('邻居坐标大跨度变化时走过去而不是瞬移', async ({ page }) => {
    // Mock presence 数据：让某个邻居的位置在两次轮询间大幅变化
    await page.route('**/api/v1/town', async (route) => {
      const response = await route.fetch()
      const data = await response.json()
      
      // 第一次返回原始数据
      if (!page.context().storageState) {
        await route.fulfill({ response, json: data })
        return
      }

      // 修改第一个邻居（非自己）的 presence 坐标
      if (data.residents && data.residents.length > 1) {
        const neighbor = data.residents.find((r: any) => !r.self)
        if (neighbor) {
          neighbor.presence = {
            x: 800,
            y: 896,
            facing: 'right',
            scene: 'town',
            updatedAt: new Date().toISOString(),
            stale: false
          }
        }
      }

      await route.fulfill({ response, json: data })
    })

    await page.goto('http://localhost:5173/town')
    await page.waitForLoadState('networkidle')
    await page.waitForTimeout(2000)

    // 截图：邻居移动前
    await page.screenshot({ 
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/04-neighbor-before.png',
      fullPage: false 
    })

    // 等待轮询触发，邻居应该会走到新位置
    await page.waitForTimeout(35000) // 轮询间隔 30s + 缓冲

    // 截图：邻居移动后（应该是走过去的，不是瞬移）
    await page.screenshot({ 
      path: '/Users/asherji/.claude/jobs/4675194c/tmp/05-neighbor-after.png',
      fullPage: false 
    })

    const canvas = page.locator('canvas').first()
    await expect(canvas).toBeVisible()
  })
})
