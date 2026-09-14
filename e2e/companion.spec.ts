import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'

test('moving in persists a private world and intentions never complete real Todo', async ({ page }, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const registration = await page.request.post('/api/v1/auth/register', { data: {
    email: `companion-${randomUUID()}@example.test`, password: 'Correct-Horse-Battery-2026!',
    displayName: '小街住客', birthDate: '1990-01-01', timezone: 'Asia/Shanghai',
    consents: { terms: '2026-07', privacy: '2026-07', ai: '2026-07' },
  } })
  expect(registration.ok(), await registration.text()).toBeTruthy()
  const cookies = await page.context().cookies()
  const headers = { 'X-CSRF-Token': decodeURIComponent(cookies.find(cookie => cookie.name === 'csrf_token')!.value) }
  async function post(path: string, data?: unknown) {
    const response = await page.request.post(`/api/v1${path}`, { headers, data })
    expect(response.ok(), `${path}: ${await response.text()}`).toBeTruthy()
    return (await response.json()).data
  }
  const initial = await page.request.get('/api/v1/town/companion')
  expect((await initial.json()).data).toEqual({ joined: false, world: null })
  const joined = await post('/town/companion/join', { name: '小街住客', timezone: 'Asia/Shanghai' })
  expect(joined.world.residents).toHaveLength(25)
  const again = await post('/town/companion/join', { name: '不会重建', timezone: 'UTC' })
  expect(again.world.id).toBe(joined.world.id)

  const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
  const monday = new Date(`${date}T00:00:00Z`)
  monday.setUTCDate(monday.getUTCDate() - (monday.getUTCDay() + 6) % 7)
  const dimensions = (await (await page.request.get('/api/v1/dimensions')).json()).data
  const dimension = dimensions[0]
  const end = new Date(`${date}T00:00:00Z`)
  end.setUTCDate(end.getUTCDate() + 20)
  const goal = await post('/goals', { dimensionPublicId: dimension.publicId, title: '读书', startDate: date, endDate: end.toISOString().slice(0, 10) })
  const plan = await post('/plans/weekly', { goalPublicId: goal.publicId, weekStartDate: monday.toISOString().slice(0, 10), timezone: 'Asia/Shanghai' })
  await post('/tasks', { weeklyPlanPublicId: plan.publicId, goalPublicId: goal.publicId,
    title: '私密 Todo 标题不进入居民记忆', notes: '', estimatedMinutes: 25, difficulty: 1,
    rrule: 'FREQ=DAILY', dimensionWeights: { [dimension.code]: 10 }, plannedLocalTime: '23:59', activeFrom: date, activeUntil: date,
  })
  await post(`/plans/weekly/${plan.publicId}/materialize`)
  const tasks = (await (await page.request.get(`/api/v1/task-schedules?localDate=${date}`)).json()).data
  expect(tasks.length).toBeGreaterThan(0)
  const focusId = randomUUID()
  const focus = await post('/town/companion/intents', { id: focusId, kind: 'focus', priority: 'explicit', taskId: tasks[0].taskPublicId, durationMinutes: 25 })
  expect(focus.world.focus.taskId).toBe(tasks[0].taskPublicId)
  const thoughtId = randomUUID()
  const thought = { id: thoughtId, kind: 'flowers', priority: 'passing' }
  await post('/town/companion/intents', thought)
  const duplicate = await post('/town/companion/intents', thought)
  expect(duplicate.world.intents.filter((intent: { id: string }) => intent.id === thoughtId)).toHaveLength(1)
  expect(duplicate.world.intents.find((intent: { id: string }) => intent.id === thoughtId).status).toBe('pending')
  const cancelled = await page.request.delete(`/api/v1/town/companion/intents/${thoughtId}`, { headers })
  expect(cancelled.ok()).toBeTruthy()

  await page.goto('/town')
  await page.evaluate(() => {
    for (const key of Object.keys(localStorage)) if (key.startsWith('better-self:welcome:')) localStorage.setItem(key, 'dismissed')
  })
  const closeWelcome = page.getByRole('button', { name: '关闭欢迎介绍' })
  if (await closeWelcome.isVisible()) await closeWelcome.click()
  await expect(page.locator('.companion-scene')).toBeVisible()
  await expect(page.locator('canvas')).toBeVisible()
  await page.reload()
  await expect(page.locator('.companion-scene')).toBeVisible()
  const saved = (await (await page.request.get('/api/v1/town/companion')).json()).data.world
  expect(saved.id).toBe(joined.world.id)
  expect(saved.focus.taskId).toBe(tasks[0].taskPublicId)
  expect(saved.intents.find((intent: { id: string }) => intent.id === thoughtId).status).toBe('cancelled')
  expect(JSON.stringify(saved.memories)).not.toContain('私密 Todo 标题')
  const afterTasks = (await (await page.request.get(`/api/v1/task-schedules?localDate=${date}`)).json()).data
  expect(afterTasks.find((task: { publicId: string }) => task.publicId === tasks[0].publicId).status).toBe(tasks[0].status)
  // The calm default keeps full dialogue out of the scene; focus and click reveal it on demand.
  await expect(page.locator('.resident-speech')).toHaveCount(0)
  await page.getByRole('button', { name: '收起专注' }).click()
  const owner = page.locator('.resident-person[aria-label^="阿禾"]')
  await expect(owner).toBeVisible()
  await owner.focus()
  await expect(owner.getByRole('tooltip')).toContainText('阿禾')
  await owner.click()
  await expect(page.getByRole('heading', { name: '阿禾', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '收起居民记忆' }).click()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy()
  expect(errors).toEqual([])
  await page.screenshot({ path: testInfo.outputPath('companion.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.companion-scene')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBeTruthy()
  await page.screenshot({ path: testInfo.outputPath('companion-mobile.png'), fullPage: true })
})
