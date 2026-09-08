import { test, expect, type Page } from '@playwright/test'

// The reordering, snapping and theme reveal added to the shared interaction layer,
// checked with motion on. No writes or authentication against the real backend.
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())

async function fixture(page: Page, options: { minimal?: boolean } = {}) {
  await page.addInitScript(({ minimal }) => {
    localStorage.setItem('better-self:welcome:interaction-user', 'dismissed')
    localStorage.setItem('better-self:appearance', JSON.stringify({ theme: 'light', accent: 'forest', motion: 'standard', density: 'comfortable', radius: 'modern' }))
    if (minimal) localStorage.setItem('better-self:workspace-mode:interaction-user', 'minimal')
  }, { minimal: Boolean(options.minimal) })
  const row = (id: string, taskTitle: string) => ({ publicId: id, taskPublicId: `task-${id}`, taskTitle, status: 'PLANNED', localDate: today, plannedStartAt: `${today}T00:00:00Z`, updatedAt: new Date().toISOString() })
  const tasks = [row('1', '修改项目介绍'), row('2', '买咖啡豆'), row('3', '看完第三章')]
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data: unknown = []
    if (path === '/me') data = { publicId: 'interaction-user', displayName: '林间', email: 'design@example.test', role: 'USER', timezone: 'Asia/Shanghai' }
    else if (path === '/task-schedules' && options.minimal) data = tasks
    else if (path === '/daily-status') data = { energy: 'STEADY', availableMinutes: 30, advice: 'KEEP' }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ data }) })
  })
}

function titles(page: Page) {
  return page.locator('.task-title').allInnerTexts()
}

test('a checklist row can be carried to a new position with the pointer', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await fixture(page, { minimal: true })
  await page.goto('/today')
  await expect(page.getByRole('button', { name: '修改项目介绍', exact: true })).toBeVisible()
  expect(await titles(page)).toEqual(['修改项目介绍', '买咖啡豆', '看完第三章'])

  const handle = page.getByRole('button', { name: '调整顺序：修改项目介绍' })
  const grip = (await handle.boundingBox())!
  const target = (await page.locator('.checklist-row').nth(1).boundingBox())!
  await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2)
  await page.mouse.down()
  await page.mouse.move(grip.x + grip.width / 2, grip.y + grip.height / 2 + target.height * 0.7, { steps: 8 })
  await page.mouse.up()

  await expect.poll(() => titles(page)).toEqual(['买咖啡豆', '修改项目介绍', '看完第三章'])
  await page.reload()
  await expect.poll(() => titles(page)).toEqual(['买咖啡豆', '修改项目介绍', '看完第三章'])
  expect(errors).toEqual([])
})

test('the same reordering is available from the keyboard', async ({ page }) => {
  await fixture(page, { minimal: true })
  await page.goto('/today')
  await expect(page.getByRole('button', { name: '修改项目介绍', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '调整顺序：修改项目介绍' }).focus()
  await page.keyboard.press('ArrowDown')
  await expect.poll(() => titles(page)).toEqual(['买咖啡豆', '修改项目介绍', '看完第三章'])
})

test('the rhythm slider settles on a tick after a free drag', async ({ page }) => {
  await fixture(page)
  await page.goto('/today')
  await page.getByRole('button', { name: '调整今日节奏' }).click()
  const slider = page.locator('#available-minutes')
  await expect(slider).toBeVisible()
  await slider.scrollIntoViewIfNeeded()
  const track = (await slider.boundingBox())!
  await page.mouse.move(track.x + track.width * 0.32, track.y + track.height / 2)
  await page.mouse.down()
  await page.mouse.move(track.x + track.width * 0.67, track.y + track.height / 2, { steps: 12 })
  await page.mouse.up()
  await page.waitForTimeout(500)
  const value = Number(await slider.inputValue())
  expect(value % 5).toBe(0)
  expect(value).toBeGreaterThan(30)
  await expect(page.locator('.minutes-control span')).toHaveText(`${value} 分钟`)
})

test('switching the theme expands from the pressed control without losing the change', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await fixture(page)
  await page.goto('/settings')
  await page.getByRole('button', { name: '深色' }).click()
  await expect.poll(() => page.evaluate(() => document.documentElement.dataset.theme)).toBe('dark')
  await page.getByRole('button', { name: '莓果晚霞' }).click()
  await expect.poll(() => page.evaluate(() => document.documentElement.dataset.accent)).toBe('plum')
  expect(errors).toEqual([])
})
