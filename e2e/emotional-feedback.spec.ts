import { expect, test } from '@playwright/test'

function localDate(daysFromToday: number) {
  const value = new Date()
  value.setDate(value.getDate() + daysFromToday)
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

test('goal creation and completion provide supportive feedback', async ({ page }, testInfo) => {
  await page.goto('/auth')
  await page.getByRole('button', { name: '注册' }).click()
  await page.getByLabel('邮箱').fill(`emotion-e2e-${testInfo.project.name}-${Date.now()}@example.test`)
  await page.getByLabel('密码').fill('Correct-Horse-Battery-2026!')
  await page.getByLabel('称呼').fill('成长体验用户')
  await page.getByLabel('出生日期').fill('1990-01-01')
  await page.getByLabel('我同意服务条款').check()
  await page.getByLabel('我同意隐私政策').check()
  await page.getByLabel('我了解 AI 使用说明').check()
  await page.getByRole('button', { name: '创建账户' }).click()
  await expect(page).toHaveURL(/\/onboarding$/)

  await page.goto('/goals')
  await page.getByRole('button', { name: '目标', exact: true }).click()
  await expect(page.locator('.support-line')).not.toHaveText('')
  await page.getByLabel('目标名称').fill('完成一本学习笔记')
  await page.getByLabel('完成标准').fill('整理四个章节并各写一页总结')
  await page.getByLabel('开始日期').fill(localDate(0))
  await page.getByLabel('结束日期').fill(localDate(27))
  await page.getByRole('button', { name: '保存目标' }).click()
  await expect(page.locator('.feedback-banner[data-tone="support"]')).not.toHaveText('')

  const goal = page.locator('article').filter({ hasText: '完成一本学习笔记' })
  await goal.getByRole('button', { name: '完成目标' }).click()
  await expect(page.locator('.feedback-banner[data-tone="celebrate"]')).not.toHaveText('')
  await page.screenshot({ path: `../artifacts/${testInfo.project.name}-goal-celebration.png`, fullPage: true })
})
