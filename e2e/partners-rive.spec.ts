import { expect, test } from '@playwright/test'

test('Rive partner renders and direct interaction updates affection', async ({ page }, testInfo) => {
  const pageErrors: string[] = []
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.goto('/auth')
  await page.getByRole('button', { name: '注册' }).click()
  await page.getByLabel('邮箱').fill(`partners-rive-${testInfo.project.name}-${Date.now()}@example.test`)
  await page.getByLabel('密码').fill('Correct-Horse-Battery-2026!')
  await page.getByLabel('称呼').fill('伙伴测试用户')
  await page.getByLabel('出生日期').fill('1990-01-01')
  await page.getByLabel('我同意服务条款').check()
  await page.getByLabel('我同意隐私政策').check()
  await page.getByLabel('我了解 AI 使用说明').check()
  await page.getByRole('button', { name: '创建账户' }).click()
  await expect(page).toHaveURL(/\/onboarding$/)

  await page.goto('/partners')
  await page.getByRole('button', { name: '关闭欢迎介绍' }).click()
  const canvas = page.locator('.rive-pet canvas')
  await expect(canvas).toBeVisible()
  await expect.poll(async () => canvas.evaluate((element: HTMLCanvasElement) => {
    if (!element.width || !element.height) return false
    const context2d = element.getContext('2d')
    if (context2d) {
      const pixels = context2d.getImageData(0, 0, element.width, element.height).data
      let coloredPixels = 0
      for (let index = 0; index < pixels.length; index += 16) {
        if (pixels[index + 3] > 0) coloredPixels += 1
      }
      return coloredPixels > 100
    }
    const context = element.getContext('webgl2') || element.getContext('webgl')
    if (!context) return false
    const pixels = new Uint8Array(element.width * element.height * 4)
    context.readPixels(0, 0, element.width, element.height, context.RGBA, context.UNSIGNED_BYTE, pixels)
    let coloredPixels = 0
    for (let index = 0; index < pixels.length; index += 16) {
      if (pixels[index + 3] > 0) coloredPixels += 1
    }
    return coloredPixels > 100
  }), { timeout: 15_000 }).toBe(true)

  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  let notifyProfileRefresh!: () => void
  const profileRefreshStarted = new Promise<void>(resolve => { notifyProfileRefresh = resolve })
  await page.route('**/api/v1/partners/profile', async route => {
    notifyProfileRefresh()
    await new Promise(resolve => setTimeout(resolve, 500))
    await route.continue()
  })
  await page.getByRole('button', { name: '摸摸脑袋' }).click()
  await profileRefreshStarted
  await expect(canvas).toBeVisible()
  await expect(page.getByText('正在唤醒伙伴…')).toHaveCount(0)
  await expect(page.locator('.dialogue-bar blockquote')).not.toBeEmpty()
  await expect(page.getByText('首次互动 +2')).toBeVisible()
  await expect(page.locator('.affection-panel strong')).toHaveText('2 / 10')
  await page.unroute('**/api/v1/partners/profile')

  await page.getByRole('button', { name: '关闭对话' }).click()
  await page.getByRole('button', { name: '摸摸脑袋' }).click()
  await expect(page.locator('.dialogue-bar blockquote')).not.toBeEmpty()
  await expect(page.getByText('今日奖励已领取')).toBeVisible()
  await expect(page.locator('.affection-panel strong')).toHaveText('2 / 10')

  const species = [
    { code: 'DOG', label: '狗', name: '小柯' },
    { code: 'HAMSTER', label: '仓鼠', name: '奶糖' },
    { code: 'SNAKE', label: '蛇', name: '小青' },
    { code: 'RABBIT', label: '兔子', name: '月团' },
    { code: 'BIRD', label: '小鸟', name: '啾啾' },
    { code: 'TURTLE', label: '乌龟', name: '慢慢' },
    { code: 'FOX', label: '狐狸', name: '赤赤' },
  ]

  for (const animal of species) {
    await page.getByRole('button', { name: '新伙伴' }).click()
    await page.locator('.species-grid').getByRole('button', { name: new RegExp(`${animal.label}$`) }).click()
    await page.getByLabel('名字').fill(animal.name)
    await page.getByRole('button', { name: '创建伙伴' }).click()

    const renderer = page.locator(`.rive-pet[data-species="${animal.code}"]`)
    await expect(renderer).toBeVisible()
    await expect(renderer).not.toHaveClass(/unavailable/)

    if (animal.code === 'SNAKE') {
      await expect.poll(() => renderer.locator('.static-pet').evaluate((image: HTMLImageElement) => (
        image.complete && image.naturalWidth > 0 && image.getAttribute('src') === '/assets/pets/snake-cartoon.svg'
      ))).toBe(true)
    } else {
      await expect.poll(() => renderer.locator('canvas').evaluate((element: HTMLCanvasElement) => {
        const context = element.getContext('2d')
        if (!context || !element.width || !element.height) return false
        const pixels = context.getImageData(0, 0, element.width, element.height).data
        for (let index = 3; index < pixels.length; index += 16) {
          if (pixels[index] > 0) return true
        }
        return false
      }), { timeout: 15_000 }).toBe(true)
    }
  }

  await expect(page.locator('.shop-art img')).toHaveCount(8)
  await expect.poll(() => page.locator('.shop-art img').evaluateAll(images => images.every(image => (
    (image as HTMLImageElement).complete && (image as HTMLImageElement).naturalWidth > 0
  )))).toBe(true)
  expect(pageErrors).toEqual([])
})
