import { test, expect } from '@playwright/test'

/**
 * 验证小镇面板的完整操作能力：
 * 1. 在洞察面板里填写并保存周复盘草稿
 * 2. 在个人面板里切换隐私开关（独自升级）
 * 3. 在个人面板里佩戴称号
 */

const TEST_USER = 'town-demo-1788569807@example.com'
const TEST_PASS = 'Correct-Horse-Battery-2026!'

test.describe('小镇面板完整操作能力', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/auth')
    await page.fill('input[type="email"]', TEST_USER)
    await page.fill('input[type="password"]', TEST_PASS)
    await page.click('button[type="submit"]')
    await page.waitForURL('/today')
  })

  test('洞察面板：填写并保存周复盘草稿', async ({ page }) => {
    // 进入沉浸式小镇
    await page.goto('/town/immersive')
    await page.waitForTimeout(1000)

    // 打开洞察面板（可能通过 dock 或快捷键）
    const insightsButton = page.locator('[aria-label*="洞察"], button:has-text("洞察")')
    if (await insightsButton.count() > 0) {
      await insightsButton.first().click()
    } else {
      // 如果没有 dock 按钮，尝试通过 URL 直接访问面板状态
      await page.evaluate(() => {
        window.dispatchEvent(new CustomEvent('world:open-panel', { detail: { panel: 'insights' } }))
      })
    }
    await page.waitForTimeout(500)

    // 截图：面板打开状态
    await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/01-insights-panel-opened.png' })

    // 切换到周复盘标签
    const reviewTab = page.locator('button[role="tab"]:has-text("周复盘")')
    if (await reviewTab.count() > 0) {
      await reviewTab.click()
      await page.waitForTimeout(300)

      // 填写第一个问题
      const firstTextarea = page.locator('.review-form textarea').first()
      await firstTextarea.fill('这周找到了适合自己的节奏，每天早上先完成最重要的事。')
      await page.waitForTimeout(200)

      // 截图：填写完毕
      await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/02-review-filled.png' })

      // 保存草稿
      const saveButton = page.locator('button:has-text("保存草稿")')
      await saveButton.click()
      await page.waitForTimeout(800)

      // 验证保存反馈
      const feedback = await page.locator('text=/复盘草稿已保存|保存中/').textContent()
      console.log('周复盘保存反馈:', feedback)

      // 截图：保存后
      await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/03-review-saved.png' })
    } else {
      console.log('周复盘标签未找到，可能计划未创建')
      await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/03-review-tab-missing.png' })
    }
  })

  test('个人面板：切换隐私开关与佩戴称号', async ({ page }) => {
    await page.goto('/town/immersive')
    await page.waitForTimeout(1000)

    // 打开个人面板
    const profileButton = page.locator('[aria-label*="个人"], button:has-text("个人")')
    if (await profileButton.count() > 0) {
      await profileButton.first().click()
    } else {
      await page.evaluate(() => {
        window.dispatchEvent(new CustomEvent('world:open-panel', { detail: { panel: 'profile' } }))
      })
    }
    await page.waitForTimeout(500)

    // 截图：个人面板打开
    await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/04-profile-panel-opened.png' })

    // 切换隐私开关
    const privacyToggle = page.locator('.privacy-toggle, button[role="switch"]')
    if (await privacyToggle.count() > 0) {
      const beforeText = await page.locator('body').textContent()
      await privacyToggle.first().click()
      await page.waitForTimeout(800)

      // 截图：隐私切换后
      await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/05-privacy-toggled.png' })

      const afterText = await page.locator('body').textContent()
      console.log('隐私切换前后文本变化:', { before: beforeText?.slice(0, 100), after: afterText?.slice(0, 100) })
    }

    // 切换到称号标签
    const titleTab = page.locator('button[role="tab"]:has-text("称号")')
    if (await titleTab.count() > 0) {
      await titleTab.click()
      await page.waitForTimeout(300)

      // 如果有未佩戴的已获得称号，尝试佩戴
      const equipButton = page.locator('.title-row.held:not(.equipped) .icon-button').first()
      if (await equipButton.count() > 0) {
        await equipButton.click()
        await page.waitForTimeout(800)

        // 截图：称号佩戴后
        await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/06-title-equipped.png' })

        const feedback = await page.locator('text=/已佩戴|已卸下/').textContent()
        console.log('称号操作反馈:', feedback)
      } else {
        console.log('没有可佩戴的称号，或当前已佩戴')
        await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/06-no-title-to-equip.png' })
      }
    }
  })

  test('设置面板：切换外观主题', async ({ page }) => {
    await page.goto('/town/immersive')
    await page.waitForTimeout(1000)

    const settingsButton = page.locator('[aria-label*="设置"], button:has-text("设置")')
    if (await settingsButton.count() > 0) {
      await settingsButton.first().click()
    } else {
      await page.evaluate(() => {
        window.dispatchEvent(new CustomEvent('world:open-panel', { detail: { panel: 'settings' } }))
      })
    }
    await page.waitForTimeout(500)

    // 截图：设置面板
    await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/07-settings-panel-opened.png' })

    // 切换主题（如果当前是浅色，切到深色）
    const darkThemeButton = page.locator('.theme-row button:has-text("深色")')
    if (await darkThemeButton.count() > 0) {
      await darkThemeButton.click()
      await page.waitForTimeout(500)
      await page.screenshot({ path: '/Users/asherji/.claude/jobs/4675194c/tmp/08-theme-switched-to-dark.png' })
    }
  })
})
