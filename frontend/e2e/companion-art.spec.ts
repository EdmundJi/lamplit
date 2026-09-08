import { expect, test } from '@playwright/test'

test('licensed action art loads and topic emoji remain readable in the pixel bubble', async ({ page }, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('response', response => {
    if (response.url().includes('/assets/town/') && response.status() >= 400) errors.push(response.url())
  })
  await page.goto('/harness/companion-art.html')
  await expect(page.locator('canvas')).toBeVisible()
  await expect(page.locator('.resident-person')).toHaveCount(5)
  await expect(page.locator('.resident-person[aria-label^="阿川"]')).toHaveAttribute('aria-label', /正在创作/)
  await page.screenshot({ path: testInfo.outputPath('afternoon.png'), fullPage: true })
  await page.getByRole('button', { name: '看夜晚', exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('evening.png'), fullPage: true })
  await page.getByRole('button', { name: '看白天', exact: true }).click()
  await page.getByRole('button', { name: '聊聊海报' }).click()
  const topic = page.locator('.resident-topic').first()
  await expect(topic).toBeVisible({ timeout: 15000 })
  await expect(topic.locator('.resident-topic-emoji')).toHaveText(/🎨|☕/)
  await topic.focus()
  await expect(page.getByRole('tooltip')).toContainText('海报')
  await page.screenshot({ path: testInfo.outputPath('conversation.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('canvas')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('mobile.png'), fullPage: true })
  expect(errors).toEqual([])
})
