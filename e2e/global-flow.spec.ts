import { expect, test } from '@playwright/test'

test('adult registration reaches onboarding with all three consents', async ({ page }) => {
  await page.goto('/auth')
  await page.getByRole('button', { name: '注册' }).click()
  await page.getByLabel('邮箱').fill(`e2e-${Date.now()}@example.test`)
  await page.getByLabel('密码').fill('Correct-Horse-Battery-2026!')
  await page.getByLabel('称呼').fill('端到端学习者')
  await page.getByLabel('出生日期').fill('1990-01-01')
  await page.getByLabel('我同意服务条款').check()
  await page.getByLabel('我同意隐私政策').check()
  await page.getByLabel('我了解 AI 使用说明').check()
  await page.getByRole('button', { name: '创建账户' }).click()
  await expect(page).toHaveURL(/\/onboarding$/)
  await expect(page.getByRole('heading', { name: '你的节奏' })).toBeVisible()
})
