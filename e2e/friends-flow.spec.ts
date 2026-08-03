import { expect, test } from '@playwright/test'

async function register(page: any, email: string, name: string) {
  await page.goto('/auth')
  await page.getByRole('button', { name: '注册' }).click()
  await page.getByLabel('邮箱').fill(email)
  await page.getByLabel('密码').fill('Correct-Horse-Battery-2026!')
  await page.getByLabel('称呼').fill(name)
  await page.getByLabel('出生日期').fill('1990-01-01')
  await page.getByLabel('我同意服务条款').check()
  await page.getByLabel('我同意隐私政策').check()
  await page.getByLabel('我了解 AI 使用说明').check()
  await page.getByRole('button', { name: '创建账户' }).click()
  await expect(page).toHaveURL(/\/onboarding$/)
}

test('friend request by email, accept, and friend detail page (desktop then mobile)', async ({ browser }, testInfo) => {
  const stamp = `${testInfo.project.name}-${Date.now()}`
  const aliceEmail = `alice-${stamp}@example.test`
  const bobEmail = `bob-${stamp}@example.test`

  const alice = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const bob = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const alicePage = await alice.newPage()
  const bobPage = await bob.newPage()
  const aliceErrors: string[] = []
  const bobErrors: string[] = []
  alicePage.on('pageerror', error => aliceErrors.push(error.message))
  bobPage.on('pageerror', error => bobErrors.push(error.message))

  await register(alicePage, aliceEmail, '爱丽丝')
  await register(bobPage, bobEmail, '鲍勃')
  const bobCsrf = await bobPage.evaluate(() => {
    const match = document.cookie.split(';').map(v => v.trim()).find(v => v.startsWith('csrf_token='))
    return match ? decodeURIComponent(match.slice('csrf_token='.length)) : ''
  })
  const petResponse = await bobPage.request.post('/api/v1/partners/pets', {
    headers: { 'X-CSRF-Token': bobCsrf },
    data: { speciesCode: 'SNAKE', name: '小绿', breed: '翠青蛇', furColor: '绿色' },
  })
  expect(petResponse.status()).toBe(201)

  await alicePage.goto('/friends')
  await alicePage.getByRole('button', { name: '关闭欢迎介绍' }).click().catch(() => {})
  await expect(alicePage.getByRole('heading', { name: '好友', exact: true })).toBeVisible()
  await alicePage.locator('.add-friend-band input').fill(bobEmail)
  await alicePage.getByRole('button', { name: '发送申请' }).click()
  await expect(alicePage.getByText('申请已发送给 鲍勃')).toBeVisible()
  await expect(alicePage.locator('.outgoing-card')).toHaveCount(1)
  await expect(alicePage.locator('.outgoing-card')).toContainText('鲍勃')

  await bobPage.goto('/friends')
  await bobPage.getByRole('button', { name: '关闭欢迎介绍' }).click().catch(() => {})
  await expect(bobPage.locator('.incoming-card')).toHaveCount(1)
  await expect(bobPage.locator('.incoming-card')).toContainText('爱丽丝')
  await bobPage.locator('.incoming-card button.primary').click()
  await expect(bobPage.getByText('已和 爱丽丝 成为好友')).toBeVisible()
  await expect(bobPage.locator('.friend-grid .friend-link')).toHaveCount(1)

  await alicePage.goto('/friends')
  await expect(alicePage.locator('.friend-grid .friend-link')).toHaveCount(1)
  await expect(alicePage.locator('.friend-link')).toContainText('鲍勃')
  await expect(alicePage.getByText('1 位好友')).toBeVisible()

  await alicePage.locator('.friend-link').click()
  await expect(alicePage).toHaveURL(/\/friends\//)
  await expect(alicePage.getByRole('heading', { name: '鲍勃' })).toBeVisible()
  await expect(alicePage.locator('.error')).toHaveCount(0)
  await expect(alicePage.locator('.radar-chart canvas')).toBeVisible()
  await expect.poll(async () => alicePage.locator('.radar-chart canvas').evaluate((element: HTMLCanvasElement) => {
    const context = element.getContext('2d')
    if (!context || !element.width || !element.height) return false
    const pixels = context.getImageData(0, 0, element.width, element.height).data
    let coloredPixels = 0
    for (let index = 0; index < pixels.length; index += 16) {
      if (pixels[index + 3] > 0) coloredPixels += 1
    }
    return coloredPixels > 100
  })).toBe(true)
  await expect(alicePage.locator('.badge-card')).toHaveCount(20)
  await expect(alicePage.locator('.badge-summary span')).toHaveText(/^\d+ \/ 20$/)
  await expect(alicePage.locator('.today-band .section-count')).toContainText('已完成')
  await expect(alicePage.locator('.pet-band .rive-pet img')).toBeVisible()
  await expect(alicePage.locator('.pet-details strong')).toHaveText('小绿')
  await expect(alicePage.locator('.metrics div')).toHaveCount(4)
  await alicePage.screenshot({ path: `../artifacts/${testInfo.project.name}-friend-detail.png`, fullPage: true })

  await alicePage.setViewportSize({ width: 390, height: 844 })
  await alicePage.waitForTimeout(300)
  const noOverflow = await alicePage.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)
  expect(noOverflow).toBe(true)
  const nav = alicePage.locator('.mobile-nav')
  await expect(nav).toBeVisible()
  await expect(nav.locator('a')).toHaveCount(5)
  await alicePage.goto('/friends')
  await expect(alicePage.locator('.mobile-nav a')).toHaveCount(5)
  await expect(alicePage.locator('.add-friend-row input')).toBeVisible()
  expect(await alicePage.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)

  expect(aliceErrors).toEqual([])
  expect(bobErrors).toEqual([])
  await alice.close()
  await bob.close()
})
