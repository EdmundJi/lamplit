import { test, expect, type Page } from '@playwright/test'
import { townFixture, SELF_ID } from '../../../cypress/support/town-fixture'
const envelope = (data: unknown) => ({ data, requestId: 'town-m10', timestamp: new Date().toISOString() })
async function boot(page: Page, onboarding = false) {
  const fixture = townFixture()
  await page.route('**/api/v1/**', async route => {
    const p = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data: unknown = []
    if (p === '/me') data = fixture.me
    else if (p === '/me/profile') data = fixture.profile
    else if (p === '/town') data = fixture.town
    else if (p === '/town/npcs') data = { npcs: [], initiativeBudget: { limit: 3, used: 3 } }
    else if (p === '/town/reflection/latest') data = fixture.reflection
    else if (p === '/partners/profile') data = fixture.partnerProfile
    else if (p === '/insights/attributes') data = { overallLevel: 7, totalExperience: 4200, attributes: [] }
    else if (p === '/town/letters') data = { letters: [], unreadCount: 0 }
    else if (p === '/town/letters/unread') data = { unreadCount: 0 }
    else if (p === '/friends/unread-summary') data = { totalUnread: 0 }
    else if (p === '/friends') data = { friends: [], incoming: [], outgoing: [] }
    else if (p === '/daily-status' || p === '/town/confidant') data = null
    else if (p === '/task-schedules') data = [{ publicId: 'm10-task', taskTitle: '阅读一页', plannedStartAt: new Date().toISOString(), estimatedMinutes: 25, status: 'PLANNED' }]
    else if (p === '/ai/sessions') data = route.request().method() === 'POST' ? { publicId: 'm10-ai' } : []
    await route.fulfill({ json: envelope(data) })
  })
  await page.addInitScript(({ id, onboarding }) => {
    localStorage.setItem(`better-self:welcome:${id}`, 'dismissed')
    if (!onboarding) localStorage.setItem(`better-self:town-onboarding:${id}:completed`, 'true')
  }, { id: SELF_ID, onboarding })
  await page.goto('/town/immersive')
  await page.waitForFunction(() => (window as any).__town?.snapshot().player?.controllable)
  await expect(page.locator('.immersive-canvas canvas')).toBeVisible()
}
const primary = (page: Page, label: string) => page.locator('.immersive-topbar').getByRole('button', { name: label, exact: true })
async function openStyles(page: Page) {
  await primary(page, '更多').click()
  await page.locator('#immersive-secondary-actions').getByRole('button', { name: '布置我的家', exact: true }).click()
}

test('M10 入住三步连接真实布置、手账和小助', async ({ page }) => {
  await boot(page, true)
  await expect(page.getByRole('dialog', { name: '入住小镇' })).toContainText('第 1 步，共 3 步')
  await page.getByRole('button', { name: '布置我的家', exact: true }).click()
  await expect(page.locator('.town-style-panel')).toBeVisible()
  await page.getByRole('button', { name: '保存布置', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '入住小镇' })).toContainText('今天的一小步')
  await page.getByRole('dialog', { name: '入住小镇' }).getByRole('button', { name: '打开手账' }).click()
  await expect(page.locator('.world-window').filter({ has: page.locator('.today-panel') })).toBeVisible()
  await page.locator('.world-window').getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '入住小镇' })).toContainText('认识小助')
  await page.getByRole('button', { name: '和小助聊聊', exact: true }).click()
  await expect(page.getByLabel('给 AI 助手发消息')).toBeVisible()
  expect(await page.evaluate(id => localStorage.getItem(`better-self:town-onboarding:${id}:completed`), SELF_ID)).toBe('true')
})

test('M10 今天与信箱直接打开，不触发走路', async ({ page }) => {
  await boot(page)
  const before = await page.evaluate(() => { const p = (window as any).__town.snapshot().player; return { x: p.x, y: p.y } })
  await primary(page, '今天').click()
  await expect(page.locator('.today-panel')).toBeVisible()
  await primary(page, '信箱').click()
  await expect(page.locator('.world-window:visible')).toHaveCount(2)
  const after = await page.evaluate(() => { const s = (window as any).__town.snapshot(); return { x: s.player.x, y: s.player.y, phase: (window as any).__townScene.travel?.phase } })
  expect(after.x).toBeCloseTo(before.x, 1)
  expect(after.y).toBeCloseTo(before.y, 1)
  expect(after.phase).not.toBe('walking')
})

test('M10 家居配色预览、取消与保存', async ({ page }, info) => {
  await boot(page)
  await primary(page, '去哪里').click()
  await page.locator('.town-wayfinder').getByRole('button', { name: '我的家', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'interior:home-living-room', null, { timeout: 30000 })
  await openStyles(page)
  await page.getByRole('button', { name: /草木清晨/ }).click()
  await expect(page.getByRole('button', { name: /草木清晨/ })).toHaveAttribute('aria-pressed', 'true')
  expect(await page.evaluate(id => localStorage.getItem(`town-home:v1:${id}`), SELF_ID)).toBeNull()
  await page.screenshot({ path: info.outputPath('home-meadow-preview.png') })
  await page.getByRole('button', { name: '取消预览', exact: true }).click()
  // More remains open while using the style panel; use its concrete entry directly.
  await page.locator('#immersive-secondary-actions').getByRole('button', { name: '布置我的家', exact: true }).click()
  await expect(page.getByRole('button', { name: /原木日常/ })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: /暮色阅读/ }).click()
  await page.getByRole('button', { name: '保存布置', exact: true }).click()
  expect(JSON.parse((await page.evaluate(id => localStorage.getItem(`town-home:v1:${id}`), SELF_ID))!)).toMatchObject({ style: 'dusk' })
  await page.screenshot({ path: info.outputPath('home-dusk-saved.png') })
})

test('M10 手机只显示一扇窗口并保存输入草稿', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await boot(page)
  await page.locator('.immersive-dock-handle button').click()
  await page.locator('.dock-button').filter({ hasText: 'AI 助手' }).click()
  await page.getByLabel('给 AI 助手发消息').fill('我想保留这段未发送的想法')
  await primary(page, '今天').click()
  await expect(page.locator('.world-window:visible')).toHaveCount(1)
  await expect(page.getByLabel('给 AI 助手发消息')).toBeHidden()
  await page.locator('.dock-button').filter({ hasText: 'AI 助手' }).click()
  await expect(page.locator('.world-window:visible')).toHaveCount(1)
  await expect(page.getByLabel('给 AI 助手发消息')).toHaveValue('我想保留这段未发送的想法')
  await page.screenshot({ path: info.outputPath('mobile-draft-preserved.png') })
})

test('M10 请柬关联真实活动，意向与参加分开，刷新显示取消', async ({ page }, info) => {
  await boot(page)
  let event = { publicId: 'm10-invite', kind: 'PARK_WALK', venue: 'park', hostName: '安禾', startsAt: '2026-09-07T18:00:00+08:00', endsAt: '2026-09-07T20:00:00+08:00', dimension: null, phase: 'UPCOMING', response: 'UNDECIDED', attendedAt: null }
  const mutations: string[] = []
  const letter = { publicId: 'm10-letter', kind: 'INVITE', senderKind: 'NPC', senderRef: 'RESIDENT_1', senderName: '安禾', body: '傍晚去公园走走，按自己的节奏来。', eventPublicId: event.publicId, deliverAt: '2026-09-07T08:00:00+08:00', createdAt: '2026-09-07T07:00:00+08:00', readAt: null }
  await page.route('**/api/v1/town/letters', route => route.fulfill({ json: envelope({ letters: [letter], unreadCount: 1 }) }))
  await page.route('**/api/v1/town/events/m10-invite', route => route.fulfill({ json: envelope(event) }))
  await page.route('**/api/v1/town/events/m10-invite/**', async route => {
    mutations.push(new URL(route.request().url()).pathname)
    event = { ...event, response: route.request().postDataJSON().response }
    await route.fulfill({ json: envelope(event) })
  })
  await primary(page, '信箱').click()
  const mailbox = page.getByRole('region', { name: '小镇信箱', exact: true })
  await mailbox.getByRole('button', { name: '活动请柬', exact: true }).click()
  await mailbox.locator('.letter-summary').click()
  const invitation = page.getByRole('region', { name: '请柬对应的活动', exact: true })
  await expect(invitation).toContainText('9月7日 18:00')
  await invitation.getByRole('button', { name: '想去坐坐', exact: true }).click()
  await expect(invitation).toContainText('已记下：想去坐坐')
  expect(mutations).toEqual(['/api/v1/town/events/m10-invite/response'])
  await expect(invitation.getByRole('button', { name: '我参加了，留下回忆', exact: true })).toHaveCount(0)
  await expect(invitation.getByRole('button', { name: '去这里走走', exact: true })).toBeVisible()
  event = { ...event, phase: 'CANCELLED' }
  await invitation.getByRole('button', { name: '刷新活动安排', exact: true }).click()
  await expect(invitation).toContainText('这次安排取消了')
  await expect(invitation.getByRole('button', { name: '去这里走走', exact: true })).toHaveCount(0)
  expect(await page.evaluate(() => (window as any).__townScene.travel?.phase)).not.toBe('walking')
  await page.screenshot({ path: info.outputPath('invitation-cancellation-refreshed.png') })
})

test('M10 沉浸小助故事暂停后刷新续读', async ({ page }, info) => {
  await boot(page)
  let stage = 0, revision = 0, paused = false
  await page.route('**/api/v1/town/stories/GUIDE**', async route => {
    if (route.request().method() === 'POST') {
      const command = route.request().postDataJSON()
      if (command.expectedRevision === revision) {
        revision++
        if (command.action === 'PAUSE') paused = true
        else if (command.action === 'RESUME') paused = false
        else stage++
      }
    }
    const actions = paused ? [{ code: 'RESUME', label: '接着上次这一段' }] : stage === 0 ? [{ code: 'BEGIN', label: '听听这个小计划' }] : [{ code: 'PAUSE', label: '今天先到这里' }]
    await route.fulfill({ json: envelope({ npcCode: 'GUIDE', displayName: '小助', title: '窗边的一小格书架', stage, revision, paused, participation: 'UNDECIDED', heading: '准备起来', body: '小助正在挑选两本短篇集。', actions, completedAt: null }) })
  })
  async function open() {
    if (!await page.getByLabel('给 AI 助手发消息').isVisible()) {
      if (!await page.locator('.dock-button').filter({ hasText: 'AI 助手' }).isVisible()) await page.locator('.immersive-dock-handle button').click()
      await page.locator('.dock-button').filter({ hasText: 'AI 助手' }).click()
    }
    await page.getByRole('button', { name: '小助的小故事', exact: true }).click()
  }
  await open()
  await page.getByRole('button', { name: '听听这个小计划' }).click()
  await page.getByRole('button', { name: '今天先到这里' }).click()
  await expect(page.locator('.town-story')).toContainText('暂时停在这里')
  await page.reload()
  await page.waitForFunction(() => (window as any).__town?.snapshot().player?.controllable)
  await open()
  await expect(page.getByRole('button', { name: '接着上次这一段' })).toBeVisible()
  await page.getByRole('button', { name: '接着上次这一段' }).click()
  await expect(page.locator('.town-story')).not.toContainText('暂时停在这里')
  expect(stage).toBe(1)
  await page.screenshot({ path: info.outputPath('story-resumed.png') })
})

test('M10 好友精选拜访、主动分享和明信片', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await boot(page)
  let mine = { publicId: SELF_ID, displayName: '我', enabled: false, style: 'original', mementos: [] }
  const posted: unknown[] = []
  await page.route('**/api/v1/town/visits**', async route => {
    const path = new URL(route.request().url()).pathname
    let data: unknown = []
    if (path.endsWith('/mine')) {
      if (route.request().method() === 'PUT') mine = { ...mine, ...route.request().postDataJSON() }
      data = mine
    } else if (path.endsWith('/postcards')) { posted.push(route.request().postDataJSON()); data = { publicId: 'postcard' } }
    else if (path.endsWith('/friend')) data = { publicId: 'friend', displayName: '安然', enabled: true, style: 'meadow', mementos: [] }
    else if (path.endsWith('/visits')) data = [{ publicId: 'friend', displayName: '安然' }]
    await route.fulfill({ json: envelope(data) })
  })
  await primary(page, '信箱').click()
  await page.getByRole('button', { name: '好友拜访', exact: true }).click()
  await page.getByRole('button', { name: '我的分享', exact: true }).click()
  await expect(page.getByLabel('允许已接受的好友查看')).not.toBeChecked()
  await page.getByLabel('允许已接受的好友查看').check()
  await page.getByRole('button', { name: '保存分享设置' }).click()
  await expect(page.locator('.visits')).toContainText('已分享，只有已接受的好友可以拜访')
  await page.getByRole('button', { name: '去朋友家', exact: true }).click()
  await page.getByRole('button', { name: '安然的家 · 拜访' }).click()
  await expect(page.locator('.portrait')).toContainText('主人精选 · 异步展示')
  await page.getByLabel('留一张明信片', { exact: false }).fill('喜欢你家的绿色，下次一起散步。')
  await page.getByRole('button', { name: '投递明信片', exact: true }).click()
  await expect(page.locator('.visits')).toContainText('明信片已送到')
  expect(posted).toHaveLength(1)
  await page.screenshot({ path: info.outputPath('mobile-friend-visit.png') })
})
