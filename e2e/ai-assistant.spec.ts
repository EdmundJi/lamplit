import { expect, test } from '@playwright/test'

test('authenticated users receive an AI SSE response', async ({ page }, testInfo) => {
  await page.goto('/auth')
  await page.getByRole('button', { name: '注册' }).click()
  await page.getByLabel('邮箱').fill(`ai-e2e-${testInfo.project.name}-${Date.now()}@example.test`)
  await page.getByLabel('密码').fill('Correct-Horse-Battery-2026!')
  await page.getByLabel('称呼').fill('AI 验证用户')
  await page.getByLabel('出生日期').fill('1990-01-01')
  await page.getByLabel('我同意服务条款').check()
  await page.getByLabel('我同意隐私政策').check()
  await page.getByLabel('我了解 AI 使用说明').check()
  await page.getByRole('button', { name: '创建账户' }).click()
  await expect(page).toHaveURL(/\/onboarding$/)

  await page.goto('/ai')
  await page.getByRole('button', { name: '关闭欢迎介绍' }).click().catch(() => {})
  await page.getByLabel('输入消息').fill('请帮我把今天的学习拆成一个小步骤')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.locator('.message.assistant p')).not.toHaveText('', { timeout: 30_000 })
  await expect(page.getByText('AI 暂时不可用，请稍后再试。')).toHaveCount(0)
})
