import { expect, test } from '@playwright/test'

test('goal stack carousel switches with arrows without overflow', async ({ page }, testInfo) => {
  await page.goto('/auth')
  await page.getByRole('button', { name: '注册' }).click()
  await page.getByLabel('邮箱').fill(`stack-${testInfo.project.name}-${Date.now()}@example.test`)
  await page.getByLabel('密码').fill('Correct-Horse-Battery-2026!')
  await page.getByLabel('称呼').fill('堆叠验证')
  await page.getByLabel('出生日期').fill('1990-01-01')
  await page.getByLabel('我同意服务条款').check()
  await page.getByLabel('我同意隐私政策').check()
  await page.getByLabel('我了解 AI 使用说明').check()
  await page.getByRole('button', { name: '创建账户' }).click()
  await expect(page).toHaveURL(/\/onboarding$/)

  const csrf = await page.evaluate(() => {
    const match = document.cookie.split(';').map(v => v.trim()).find(v => v.startsWith('csrf_token='))
    return match ? decodeURIComponent(match.slice('csrf_token='.length)) : ''
  })
  const dimensions = await page.request.get('http://127.0.0.1:5173/api/v1/dimensions')
  const dim = (await dimensions.json()).data[0].publicId
  for (const index of [1, 2, 3]) {
    const response = await page.request.post('http://127.0.0.1:5173/api/v1/goals', {
      headers: { 'X-CSRF-Token': csrf },
      data: {
        dimensionPublicId: dim,
        title: `堆叠目标 ${index}`,
        description: `这是第 ${index} 个目标的描述`,
        startDate: '2026-08-03',
        endDate: '2026-08-30',
      },
    })
    expect(response.status()).toBe(201)
  }

  await page.goto('/goals')
  await page.getByRole('button', { name: '关闭欢迎介绍' }).click().catch(() => {})
  const cards = page.locator('.goal-stack article')
  await expect(cards).toHaveCount(3)
  await expect(page.locator('.goal-stack article.current')).toHaveCount(1)
  await expect(page.locator('.goal-stack article.current')).toContainText('堆叠目标 3')
  await expect(page.locator('.stack-count').first()).toHaveText('1 / 3')

  if (testInfo.project.name === 'desktop') {
  const geometry = await page.evaluate(() => {
    const current = document.querySelector('.goal-stack article.current')!.getBoundingClientRect()
    const next = document.querySelector('.goal-stack article.next')
    return {
      currentX: Math.round(current.x),
      nextX: next ? Math.round(next.getBoundingClientRect().x) : null,
      nextVisible: Boolean(next && next.getBoundingClientRect().x > current.x && next.getBoundingClientRect().right > current.right),
    }
  })
  expect(geometry.nextX! - geometry.currentX).toBeGreaterThan(20)
  expect(geometry.nextVisible).toBe(true)
  }

  await page.getByRole('button', { name: '下一个目标' }).click()
  await expect(page.locator('.goal-stack article.current')).toContainText('堆叠目标 2')
  await expect(page.locator('.stack-count').first()).toHaveText('2 / 3')
  await page.getByRole('button', { name: '下一个目标' }).click()
  await expect(page.locator('.goal-stack article.current')).toContainText('堆叠目标 1')
  await expect(page.getByRole('button', { name: '下一个目标' })).toBeDisabled()
  if (testInfo.project.name === 'desktop') {
  const prevGeometry = await page.evaluate(() => {
    const current = document.querySelector('.goal-stack article.current')!.getBoundingClientRect()
    const prev = document.querySelector('.goal-stack article.prev')
    return {
      currentX: Math.round(current.x),
      prevX: prev ? Math.round(prev.getBoundingClientRect().x) : null,
      prevVisible: Boolean(prev && prev.getBoundingClientRect().x < current.x && prev.getBoundingClientRect().left < current.left),
    }
  })
  expect(prevGeometry.currentX - prevGeometry.prevX!).toBeGreaterThan(20)
  expect(prevGeometry.prevVisible).toBe(true)
  }
  await page.getByRole('button', { name: '上一个目标' }).click()
  await expect(page.locator('.goal-stack article.current')).toContainText('堆叠目标 2')

  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForTimeout(300)
  await expect(page.locator('.mobile-nav a')).toHaveCount(5)
  await expect(page.locator('.goal-stack article.current')).toContainText('堆叠目标 2')
  await expect(page.locator('.goal-stack article.prev')).toHaveCSS('opacity', '0')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  await page.screenshot({ path: `../artifacts/${testInfo.project.name}-goal-stack.png`, fullPage: true })
})
