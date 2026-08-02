import { expect, test } from '@playwright/test'

test('authentication is responsive, focused, and free of horizontal overflow', async ({ page }, testInfo) => {
  await page.goto('/auth')
  await expect(page.getByRole('heading', { name: '更好的自己' })).toBeVisible()
  await page.getByLabel('邮箱').focus()
  await expect(page.getByLabel('邮箱')).toBeFocused()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  await page.screenshot({ path: `../artifacts/${testInfo.project.name}-auth.png`, fullPage: true })
})
