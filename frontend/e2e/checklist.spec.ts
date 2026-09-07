import { test, expect } from '@playwright/test'

test('minimal mode supports the full checklist flow and persists across reload', async ({ page }, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {
    localStorage.setItem('better-self:workspace-mode:checklist-visual', 'minimal')
    localStorage.setItem('better-self:appearance', JSON.stringify({ theme: 'light', accent: 'forest', motion: 'off' }))
  })
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
  let next = 3
  const row = (id: string, taskTitle: string) => ({ publicId: id, taskPublicId: `task-${id}`, taskTitle, status: 'PLANNED', localDate: today, plannedStartAt: `${today}T00:00:00Z`, updatedAt: new Date().toISOString() })
  const tasks = [row('1', '修改项目介绍'), row('2', '买咖啡豆'), row('3', '看完第三章')]
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data: unknown = []
    if (path === '/me') data = { publicId: 'checklist-visual', displayName: '清单用户', email: 'test@example.test', role: 'USER', timezone: 'Asia/Shanghai' }
    else if (path === '/task-schedules') data = tasks
    else if (path === '/tasks/quick') { tasks.push(row(String(++next), route.request().postDataJSON().title)); data = {} }
    else if (path.endsWith('/reverse')) { const id = path.split('/')[2]; tasks.find(task => task.publicId === id)!.status = 'PLANNED'; data = {} }
    else if (path.endsWith('/events')) {
      const task = tasks.find(task => task.publicId === path.split('/')[2])!
      task.status = route.request().postDataJSON().eventType === 'COMPLETED' ? 'DONE' : 'CANCELLED'
      data = { scheduleStatus: task.status, eventPublicId: `event-${task.publicId}` }
    } else if (path.startsWith('/tasks/') && route.request().method() === 'PATCH') {
      tasks.find(task => task.taskPublicId === path.split('/')[2])!.taskTitle = route.request().postDataJSON().title
      data = {}
    }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data }) })
  })
  await page.goto('/today')
  await expect(page.getByRole('heading', { name: '今天' })).toBeVisible()
  await expect(page.locator('.sidebar, .mobile-nav, .workspace-topbar')).toHaveCount(0)
  expect(await page.evaluate(() => document.body.style.overflow)).not.toBe('hidden')
  const input = page.getByRole('textbox', { name: '添加一件事', exact: true })
  await input.fill('整理本周笔记')
  await input.press('Enter')
  await expect(page.getByRole('button', { name: '整理本周笔记', exact: true })).toBeVisible()
  await expect(input).toBeFocused()
  await page.getByRole('button', { name: '完成：整理本周笔记', exact: true }).click()
  await expect(page.locator('.completed-list summary')).toHaveText('已完成 1 项')
  await page.getByRole('button', { name: '撤销上次' }).click()
  await expect(page.getByRole('button', { name: '完成：整理本周笔记', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '买咖啡豆', exact: true }).click()
  await page.getByRole('textbox', { name: '修改任务名称' }).fill('买咖啡豆和牛奶')
  await page.getByRole('textbox', { name: '修改任务名称' }).press('Enter')
  await expect(page.getByRole('button', { name: '买咖啡豆和牛奶', exact: true })).toBeVisible()
  await page.reload()
  await expect(page.locator('.minimal-topbar')).toBeVisible()
  await expect(page.locator('.task-title')).toHaveCount(4)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const checkbox = await page.locator('.task-check').first().boundingBox()
  expect(checkbox?.width).toBe(checkbox?.height)
  await page.screenshot({ path: `../artifacts/checklist-${testInfo.project.name}.png`, fullPage: true })
  await page.evaluate(() => { document.documentElement.dataset.theme = 'dark' })
  await page.screenshot({ path: `../artifacts/checklist-${testInfo.project.name}-dark.png`, fullPage: true })
  await page.getByRole('button', { name: '切换成长模式' }).click()
  await expect(page.locator('.checklist')).toHaveCount(0)
  expect(errors).toEqual([])
})
