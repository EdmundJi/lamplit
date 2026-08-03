import { expect, test } from '@playwright/test'

test('daily status check saves, reorders tasks, restores and reaches insights', async ({ page }, testInfo) => {
  test.setTimeout(120_000)
  const stamp = `${testInfo.project.name}-${Date.now()}`
  const email = `status-${stamp}@example.test`

  await page.goto('/auth')
  await page.getByRole('button', { name: '注册' }).click()
  await page.getByLabel('邮箱').fill(email)
  await page.getByLabel('密码').fill('Correct-Horse-Battery-2026!')
  await page.getByLabel('称呼').fill('状态测试')
  await page.getByLabel('出生日期').fill('1990-01-01')
  await page.getByLabel('我同意服务条款').check()
  await page.getByLabel('我同意隐私政策').check()
  await page.getByLabel('我了解 AI 使用说明').check()
  await page.getByRole('button', { name: '创建账户' }).click()
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.goto('/today')
  await page.getByRole('button', { name: '关闭欢迎介绍' }).click().catch(() => {})

  const csrf = await page.evaluate(() => {
    const match = document.cookie.split(';').map(v => v.trim()).find(v => v.startsWith('csrf_token='))
    return match ? decodeURIComponent(match.slice('csrf_token='.length)) : ''
  })
  const dimensions = await (await page.request.get('/api/v1/dimensions')).json()
  const dim = dimensions.data[0].publicId
  const localDate = () => {
    const now = new Date()
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  }
  const goal = await (await page.request.post('/api/v1/goals', {
    headers: { 'X-CSRF-Token': csrf },
    data: { dimensionPublicId: dim, title: '状态测试目标', description: '验证状态检查', startDate: localDate(), endDate: '2026-08-30' },
  })).json()
  const plan = await (await page.request.post('/api/v1/plans/weekly', {
    headers: { 'X-CSRF-Token': csrf },
    data: { goalPublicId: goal.data.publicId, weekStartDate: localDate(), timezone: 'Asia/Shanghai' },
  })).json()
  await page.request.post('/api/v1/tasks', {
    headers: { 'X-CSRF-Token': csrf },
    data: {
      weeklyPlanPublicId: plan.data.publicId,
      title: '短任务', notes: '', estimatedMinutes: 10, difficulty: 1,
      rrule: 'FREQ=DAILY', plannedLocalTime: '09:00', activeFrom: localDate(), activeUntil: localDate(),
      sourceTemplatePublicId: null, roleCode: 'STUDENT',
      dimensionWeights: { KNOWLEDGE: 10 },
    },
  })
  await page.request.post('/api/v1/tasks', {
    headers: { 'X-CSRF-Token': csrf },
    data: {
      weeklyPlanPublicId: plan.data.publicId,
      title: '长任务', notes: '', estimatedMinutes: 45, difficulty: 3,
      rrule: 'FREQ=DAILY', plannedLocalTime: '08:00', activeFrom: localDate(), activeUntil: localDate(),
      sourceTemplatePublicId: null, roleCode: 'STUDENT',
      dimensionWeights: { KNOWLEDGE: 10 },
    },
  })
  await page.request.post(`/api/v1/plans/weekly/${plan.data.publicId}/materialize`, {
    headers: { 'X-CSRF-Token': csrf },
  })

  await page.goto('/today')
  await expect(page.locator('.task-row')).toHaveCount(2)
  await expect(page.locator('.task-row h2').first()).toContainText('长任务')

  await page.locator('.mood-control button').first().click()
  await page.locator('#available-minutes').fill('15')
  await page.getByRole('button', { name: '缩小今天' }).click()
  await expect(page.getByText('已按“缩小任务”整理今天')).toBeVisible()
  await expect(page.locator('.daily-guidance')).toContainText('每项控制在 15 分钟以内')
  await expect(page.locator('.task-row h2').first()).toContainText('短任务')
  await expect(page.locator('.recommended-badge')).toHaveCount(2)
  await expect(page.locator('.task-row time').first()).toContainText('10 分钟')

  await page.reload()
  await expect(page.locator('.check-result strong')).toHaveText('缩小任务')
  await expect(page.locator('.check-result .secondary')).toHaveText('更新建议')
  await expect(page.locator('.daily-guidance')).toContainText('每项控制在 15 分钟以内')

  await page.goto('/insights')
  await expect(page.getByRole('heading', { name: '你如何安排今天' })).toBeVisible()
  await expect(page.locator('.status-days strong')).toHaveText('1')
  await expect(page.locator('.advice-row').first()).toContainText('缩小任务')
  await expect(page.locator('.advice-row').first()).toContainText('1 天')

  await page.screenshot({ path: `../artifacts/${testInfo.project.name}-daily-status.png`, fullPage: false })
})
