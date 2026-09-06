import { readFileSync, writeFileSync } from 'node:fs'
import { parseRoomMap, defaultInteractionHit } from '../../../src/modules/town/map-loader'
import { test, expect, type Page, type TestInfo } from '@playwright/test'
import { letterFixture } from '../../../src/modules/town/social/social.fixtures'
import { townFixture, SELF_ID } from '../../../cypress/support/town-fixture'

test.beforeAll(() => {
  for (const room of ['home-living-room', 'academy-study', 'public-gym', 'cafe-interior']) {
    expect(() => parseRoomMap(JSON.parse(readFileSync(`public/assets/town/maps/${room}.json`, 'utf8')))).not.toThrow()
  }
})

const observedErrors: string[] = []
const envelope = (data: unknown) => ({ data, requestId: 'town-visual', timestamp: new Date().toISOString() })

async function record(page: Page, info: TestInfo, name: string) {
  const png = info.outputPath(`${name}.png`)
  await page.screenshot({ path: png })
  await info.attach(`${name}-screenshot`, { path: png, contentType: 'image/png' })
  const snapshot = await page.evaluate(() => (window as any).__town?.snapshot())
  writeFileSync(info.outputPath(`${name}.json`), JSON.stringify(snapshot ?? {}, null, 2))
  await info.attach(name, { body: JSON.stringify(await page.evaluate(() => (window as any).__town?.snapshot() ?? { url: location.href, error: 'world did not initialize' }), null, 2), contentType: 'application/json' })
}

test.beforeEach(async ({ page }) => {
  observedErrors.length = 0
  page.on('pageerror', e => observedErrors.push(e.message))
  page.on('response', r => { if (r.status() >= 400) observedErrors.push(`${r.status()} ${r.url()}`) })
  const fixture = townFixture()
  const startedAt = Date.now()
  const letters = [letterFixture('LONG', { body: '你的来信我认真读过了。愿你今晚安稳入睡。' }), letterFixture('NOTE', { body: '我今天翻到一页有趣的书，给你捎个问候。' }), letterFixture('INVITE', { body: '午后在树荫公园聊聊，路过时来坐坐吧。' })]
  const plannedTasks = [{ publicId: 'sch-planned', taskTitle: '晚间复盘', plannedStartAt: new Date().toISOString(), status: 'PLANNED', estimatedMinutes: 20, roleCode: 'WORKER', roleName: '职场人' }]
  const otherPet = { ...fixture.partnerProfile.selectedPet, publicId: 'pet-2', name: '花花', selected: false }
  fixture.partnerProfile.pets.push(otherPet)
  // Noon in a fixed calendar day; serverOffset keeps the simulation advancing normally.
  fixture.town.serverTime = '2026-09-06T10:30:00+08:00'
  fixture.town.localDate = '2026-09-06'
  const names = ['林知', '陆夏', '沈牧', '安禾', '许宁', '柯云', '王芳', '李明', '赵晨', '周雨', '陈晓', '苏叶', '江川', '吴桐', '方晴', '唐果']
  const npcs = names.map((name, i) => ({
    code: `RESIDENT_${i}`, displayName: name, layer: i < 6 ? 2 : 3, sprite: `c${String(i + 1).padStart(2,'0')}`,
    dimension: i < 6 ? 'KNOWLEDGE' : null, interests: {}, affinityToPlayer: .4,
    mood: { valence: .4, energy: .6 },
    schedule: [{ startHour: 0, endHour: 24, place: 'cafe', activity: 'reading' }],
    dayPlan: { date: fixture.town.localDate,
      errands: [{ startMinute: 0, endMinute: 629, place: i % 2 ? 'academy' : 'gym', activity: 'reading', priority: 1, origin: 'RHYTHM' },
        { startMinute: 633, endMinute: 1440, place: i % 2 ? 'cafe' : 'park', activity: 'reading', priority: 1, origin: 'RHYTHM' }],
      legs: [{ departMinute: 629, arriveMinute: 633, fromPlace: i % 2 ? 'academy' : 'gym', toPlace: i % 2 ? 'cafe' : 'park' }] },
    talkingPoints: [{ factId: `fact-${i}`, text: `${name}：${['听说公园的花开了，等会儿去看看。', '刚在学院翻到一本旧游记。', '咖啡馆靠窗的位置，晒太阳正合适。', '我想把路边的小花画下来。', '今天准备慢慢走，听听街上的声音。', '收拾口袋时，又找到一张旧车票。'][i % 6]}`, hops: 1, salience: .7 }],
  }))
  await page.route('**/api/v1/**', async route => {
    const url = new URL(route.request().url())
    const p = url.pathname.replace('/api/v1','')
    let data: unknown = []
    if (p === '/me') data = fixture.me
    else if (p === '/me/profile') data = fixture.profile
    else if (p === '/town') data = { ...fixture.town, serverTime: new Date(Date.parse(fixture.town.serverTime) + Date.now() - startedAt).toISOString() }
    else if (p === '/town/npcs') data = { npcs: [...npcs, { ...npcs[0], code: 'GUIDE', displayName: '小助', layer: 1, sprite: 'npc_scout' }, { ...npcs[0], code: 'POSTMAN', displayName: '邮递员', layer: 1, sprite: 'npc_postman' }], initiativeBudget: { limit: 3, used: 3 } }
    else if (p.endsWith('/talking-points')) data = { points: npcs.find(n => n.code === p.split('/')[3])?.talkingPoints ?? [] }
    else if (p === '/town/presence') data = fixture.presence
    else if (p === '/town/reflection/latest') data = fixture.reflection
    else if (p === '/partners/profile') data = fixture.partnerProfile
    else if (p === '/friends/unread-summary') data = { totalUnread: 2 }
    else if (p === '/insights/attributes') data = { overallLevel: 7, totalExperience: 4200, attributes: [] }
    else if (p === '/town/letters') data = { letters, unreadCount: letters.filter(l => !l.readAt).length }
    else if (p === '/town/letters/unread') data = { unreadCount: letters.filter(l => !l.readAt).length }
    else if (/^\/town\/letters\/[^/]+\/read$/.test(p)) { const letter = letters.find(l => l.publicId === p.split('/')[3]); if (letter) letter.readAt = new Date().toISOString(); data = null }
    else if (p === '/town/confidant') data = null
    else if (p === '/town/events') data = [{ publicId: 'event-1', kind: 'READING_CIRCLE', venue: 'park', hostName: '林知', startsAt: '2026-09-06T10:00:00+08:00', endsAt: '2026-09-06T11:00:00+08:00', dimension: 'KNOWLEDGE' }]
    else if (p === '/daily-status') data = null
    else if (p === '/task-schedules') data = plannedTasks
    else if (p === '/task-schedules/sch-planned/events') {
      const event = route.request().postDataJSON().eventType
      plannedTasks[0]!.status = event === 'STARTED' ? 'IN_PROGRESS' : event === 'COMPLETED' ? 'DONE' : 'DEFERRED'
      fixture.town.residents[0]!.schedules[1]!.status = plannedTasks[0]!.status
      data = { scheduleStatus: plannedTasks[0]!.status, eventPublicId: 'event-test', coinDelta: 0, roleExperienceDelta: 0 }
    }
    else if (/^\/partners\/pets\/[^/]+\/select$/.test(p)) {
      const selected = fixture.partnerProfile.pets.find(pet => pet.publicId === p.split('/')[3])!
      fixture.partnerProfile.pets.forEach(pet => { pet.selected = pet === selected })
      fixture.partnerProfile.selectedPet = selected
      data = selected
    }
    else if (p.endsWith('/interact')) data = { pet: fixture.partnerProfile.selectedPet, affectionDelta: 5, rewarded: true, interactionDate: fixture.town.localDate }
    else if (p === '/ai/sessions') data = route.request().method() === 'POST' ? { publicId: 'ai-test' } : []
    else if (p.endsWith('/messages:stream')) {
      await route.fulfill({ contentType: 'text/event-stream', body: 'event: delta\ndata: {"text":"我们先做一小步，再留一点时间休息。"}\n\nevent: done\ndata: {}\n\n' })
      return
    }
    await route.fulfill({ json: envelope(data) })
  })
  await page.addInitScript(id => {
    localStorage.setItem(`better-self:welcome:${id}`, 'dismissed')
    localStorage.setItem(`better-self:town-onboarding:${id}:completed`, 'true')
  }, SELF_ID)
  await page.goto('/town/immersive')
  await page.waitForFunction(() => (window as any).__town?.snapshot().player?.controllable)
  await page.waitForFunction(() => (window as any).__town?.snapshot().actors.length > 5)
})

test.afterEach(async ({ page }, info) => {
  if (!page.isClosed()) await record(page, info, 'final-state')
  expect(observedErrors, 'No hidden runtime or resource errors').toEqual([])
})

test('可见世界、真实键盘、居民移动与观察镜头', async ({ page }, info) => {
  await record(page, info, 'street')
  const before = await page.evaluate(() => (window as any).__town.snapshot())
  expect(before.actors.filter((a: any) => a.kind === 'resident')).toHaveLength(0)
  expect(before.renderer.missingTextures).toEqual([])
  expect(before.actors.filter((a: any) => a.name.startsWith('小助'))).toHaveLength(1)
  expect(before.actors.filter((a: any) => a.name.startsWith('邮递员'))).toHaveLength(1)
  await page.keyboard.down('ArrowRight')
  await page.waitForTimeout(700)
  await page.keyboard.up('ArrowRight')
  const after = await page.evaluate(() => (window as any).__town.snapshot())
  expect(after.player.x).toBeGreaterThan(before.player.x + 20)
  expect(after.actors.some((a: any) => a.kind === 'npc' && a.x !== before.actors.find((b: any) => b.id === a.id)?.x)).toBe(true)
  await page.getByRole('button', { name: '观察小镇', exact: true }).click()
  for (let i = 0; i < 3; i++) { await page.waitForTimeout(10_000); await record(page, info, `observation-${i}`) }
  expect(await page.evaluate(() => (window as any).__town.snapshot().world.observation)).toBe(true)
  await page.keyboard.press('Escape')
  await expect(page).toHaveURL(/town\/immersive/)
  expect(await page.evaluate(() => (window as any).__town.snapshot().world.observation)).toBe(false)
})

test('走进家中再返回、面板最小化、窄屏布局', async ({ page }, info) => {
  await page.getByRole('button', { name: '我的家', exact: true }).click()
  await expect(page.getByRole('button', { name: '回到街上', exact: true })).toBeVisible({ timeout: 35_000 })
  await page.waitForFunction(() => { const s = (window as any).__townScene?.sys.game.scene.getScenes(true).at(-1); return s?.sys.settings.key.startsWith('interior:') && !s.cameras.main.fadeEffect.isRunning })
  await record(page, info, 'home')
  expect(await page.evaluate(() => (window as any).__town.snapshot().activeScene)).toContain('interior:')
  await page.getByRole('button', { name: '回到街上', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'town')
  await page.getByTitle('展开功能栏', { exact: true }).click()
  await page.getByRole('button', { name: '目标', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '目标' })).toBeVisible()
  await page.getByRole('button', { name: '最小化', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.locator('.immersive-minimized').getByRole('button', { name: '目标' }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForFunction(() => (window as any).__townScene?.cameras.main.width === 390)
  await record(page, info, 'mobile-panel')
  const rect = await page.getByRole('dialog').boundingBox()
  expect(rect!.x).toBeGreaterThanOrEqual(0)
  expect(rect!.x + rect!.width).toBeLessThanOrEqual(391)
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await record(page, info, 'mobile-street')
})

async function clickWorld(page: Page, x: number, y: number) {
  const screen = await page.evaluate(({ x, y }) => {
    const scene = (window as any).__townScene.sys.game.scene.getScenes(true).at(-1)
    const camera = scene.cameras.main, origin = camera.getWorldPoint(0, 0)
    const canvas = document.querySelector('canvas')!.getBoundingClientRect()
    const size = scene.scale.gameSize
    return { x: canvas.left + (x - origin.x) * camera.zoom * canvas.width / size.width, y: canvas.top + (y - origin.y) * camera.zoom * canvas.height / size.height }
  }, { x, y })
  await page.mouse.click(screen.x, screen.y)
}

async function useFurniture(page: Page, roomFile: string, action: string) {
  const room = parseRoomMap(JSON.parse(readFileSync(`public/assets/town/maps/${roomFile}.json`, 'utf8')))
  const piece = room.furniture.find(f => f.interactive?.actionId === action)!
  expect(piece, `${roomFile} has ${action}`).toBeTruthy()
  const hit = piece.interactive?.hit ?? defaultInteractionHit(piece, room.tileSize)
  await clickWorld(page, hit.x + hit.w / 2, hit.y + hit.h / 2)
}

test('公共建筑进入真实室内，点击家具使用功能', async ({ page }, info) => {
  await page.locator('.run-toggle').click()
  for (const [place, label, file, action, panel] of [
    ['gym', '健身房', 'public-gym', 'gym.open-attributes', '属性'],
    ['cafe', '咖啡馆', 'cafe-interior', 'cafe.open-goals', '目标'],
  ]) {
    await page.getByRole('button', { name: label, exact: true }).click()
    await page.waitForFunction(file => (window as any).__town.snapshot().activeScene === `interior:${file}`, file, { timeout: 30_000 })
    await page.waitForFunction(() => !(window as any).__townScene.sys.game.scene.getScenes(true).at(-1).cameras.main.fadeEffect.isRunning)
    await record(page, info, `${place}-interior`)
    await useFurniture(page, file!, action!)
    await expect(page.getByRole('dialog', { name: panel, exact: true })).toBeVisible()
    await page.getByRole('button', { name: '关闭', exact: true }).click()
    await page.getByRole('button', { name: '回到街上', exact: true }).click()
    await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'town')
  }
})


test('淡入时立即离开、再次进屋后 Esc 返回仍可行走', async ({ page }, info) => {
  for (let i = 0; i < 2; i++) {
    await page.getByRole('button', { name: '我的家', exact: true }).click()
    await expect(page.getByRole('button', { name: '回到街上', exact: true })).toBeVisible()
    if (i === 0) await page.getByRole('button', { name: '回到街上', exact: true }).click()
    else await page.keyboard.press('Escape')
    await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'town', null, { timeout: 5000 })
    await expect(page).toHaveURL(/town\/immersive/)
  }
  const before = await page.evaluate(() => (window as any).__town.snapshot().player.y)
  await page.keyboard.down('ArrowDown')
  await page.waitForTimeout(400)
  await page.keyboard.up('ArrowDown')
  expect(await page.evaluate(() => (window as any).__town.snapshot().player.y)).toBeGreaterThan(before)
  await record(page, info, 'rapid-room-roundtrip')
})


test('普通小镇面板真正关闭，居民卡不溢出，健身房能进入', async ({ page }, info) => {
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/town')
  await page.waitForFunction(() => (window as any).__town?.snapshot().player?.controllable)
  await expect(page.getByRole('complementary', { name: '小镇面板' })).toBeVisible()
  await page.getByRole('button', { name: '关闭面板', exact: true }).click()
  await expect(page.locator('#town-info-panel')).toHaveCount(0)
  await page.getByRole('button', { name: '打开面板', exact: true }).click()
  await expect(page.locator('#town-info-panel')).toBeVisible()
  await page.getByRole('button', { name: '关闭面板', exact: true }).click()
  const actor = await page.evaluate(() => (window as any).__town.snapshot().actors.find((a: any) => /^RESIDENT_[0-5]$/.test(a.id) && a.visible))
  expect(actor).toBeTruthy()
  await clickWorld(page, actor.x, actor.y - 28)
  await expect(page.locator('.resident-moment')).toBeVisible({ timeout: 20_000 })
  const moment = await page.locator('.resident-moment').boundingBox(), stage = await page.locator('.town-stage').boundingBox()
  expect(moment!.x + moment!.width).toBeLessThanOrEqual(stage!.x + stage!.width)
  await record(page, info, 'ordinary-npc-panel')
  await page.getByRole('button', { name: '关闭面板', exact: true }).click()
  await page.locator('.run-toggle').click()
  await page.getByRole('button', { name: '健身房', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'interior:public-gym', null, { timeout: 30_000 })
  await record(page, info, 'ordinary-gym')
  await page.getByRole('button', { name: '回到小镇', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'town')
})

test('沿转角支路走进树荫公园', async ({ page }, info) => {
  await page.locator('.run-toggle').click()
  await page.getByRole('button', { name: '公园', exact: true }).click()
  await page.waitForFunction(() => { const p = (window as any).__town.snapshot().player; return p && p.y < 580 && p.state !== 'walk' }, null, { timeout: 40_000 })
  const snapshot = await page.evaluate(() => (window as any).__town.snapshot())
  expect(snapshot.player.onWalkable).toBe(true)
  expect(snapshot.world.walkableRects).toBeGreaterThan(2)
  if (await page.getByRole('button', { name: '关闭', exact: true }).count()) await page.getByRole('button', { name: '关闭', exact: true }).click()
  await record(page, info, 'garden-corner')
})


test('信箱三轨阅读、树洞寄信、关闭后清除私密草稿', async ({ page }, info) => {
  const originalPlayer = await page.evaluate(() => (window as any).__town.snapshot().player)
  await page.locator('.immersive-topbar').getByRole('button', { name: /信箱/ }).click()
  const mailbox = page.getByRole('region', { name: '小镇信箱', exact: true })
  await expect(mailbox.getByText('3 封未读', { exact: true })).toBeVisible()
  for (const label of ['树洞长信', '居民短笺', '活动请柬']) {
    await mailbox.locator('nav').getByRole('button', { name: label, exact: true }).click()
    await mailbox.locator('.letter-summary').click()
    await expect(mailbox.getByRole('article', { name: '信件正文' })).toBeVisible()
  }
  await expect(mailbox.getByText('0 封未读', { exact: true })).toBeVisible()
  await mailbox.getByRole('button', { name: '写给树洞', exact: true }).click()
  await mailbox.getByPlaceholder('把想说的话慢慢写下来').fill('这是一封测试用的私密长信。')
  const sent = page.waitForRequest(r => r.url().endsWith('/town/confidant') && r.method() === 'POST')
  await mailbox.getByRole('button', { name: '寄出信件', exact: true }).click()
  expect((await sent).postDataJSON()).toEqual({ message: '这是一封测试用的私密长信。' })
  await expect(mailbox.getByText(/信已寄出/)).toBeVisible()
  await expect(page.locator('.travel-status')).toHaveCount(0)
  const still = await page.evaluate(() => (window as any).__town.snapshot().player)
  expect([still.x, still.y]).toEqual([originalPlayer.x, originalPlayer.y])
  await record(page, info, 'mailbox-three-tracks')
  await mailbox.getByPlaceholder('把想说的话慢慢写下来').fill('关闭后不保留的测试草稿')
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.getByText('关闭后不保留的测试草稿')).toHaveCount(0)
})

test('今天完成、伙伴切换互动、AI对话全程留在小镇', async ({ page }, info) => {
  const originalPlayer = await page.evaluate(() => (window as any).__town.snapshot().player)
  await page.getByTitle('展开功能栏', { exact: true }).click()
  await page.locator('.immersive-dock').getByRole('button', { name: '今天', exact: true }).click()
  const today = page.getByRole('dialog', { name: '今天', exact: true })
  await expect(today.getByText('晚间复盘', { exact: true })).toBeVisible()
  await today.getByRole('button', { name: '开始', exact: true }).click()
  await expect(today.locator('.task-row .status')).toContainText('进行')
  await today.getByRole('button', { name: '完成', exact: true }).click()
  await expect(today.locator('.feedback-banner')).toBeVisible()
  await expect(today.getByText('打开完整页面')).toHaveCount(0)
  await today.getByRole('button', { name: '关闭', exact: true }).click()
  await page.locator('.immersive-dock').getByRole('button', { name: '伙伴', exact: true }).click()
  const partners = page.getByRole('dialog', { name: '伙伴', exact: true })
  await partners.getByRole('button', { name: '切换到 花花', exact: true }).click()
  await expect(partners.getByText('已切换到 花花', { exact: true })).toBeVisible()
  await partners.locator('.action-button').first().click()
  await expect(partners).toContainText('+5')
  await partners.getByRole('button', { name: '关闭', exact: true }).click()
  await page.locator('.immersive-dock').getByRole('button', { name: 'AI 助手', exact: true }).click()
  const ai = page.getByRole('dialog', { name: 'AI 助手', exact: true })
  const input = ai.getByLabel('给 AI 助手发消息')
  await input.pressSequentially('Plan a calm day with WASD')
  await expect(input).toHaveValue('Plan a calm day with WASD')
  await ai.getByRole('button', { name: '发送', exact: true }).click()
  await expect(ai.getByText('我们先做一小步，再留一点时间休息。', { exact: true })).toBeVisible()
  await expect(ai.getByText('打开完整页面')).toHaveCount(0)
  await expect(page.locator('.travel-status')).toHaveCount(0)
  const still = await page.evaluate(() => (window as any).__town.snapshot().player)
  expect([still.x, still.y]).toEqual([originalPlayer.x, originalPlayer.y])
  await record(page, info, 'core-panels-complete')
  await expect(page).toHaveURL(/town\/immersive/)
})


test('新手引导期间可真实移动并自动进入下一步', async ({ page }, info) => {
  await page.getByTitle('重新打开新手引导', { exact: true }).click()
  await page.getByRole('button', { name: '下一步', exact: false }).click()
  await expect(page.getByRole('heading', { name: '四处走走', exact: true })).toBeVisible()
  await page.keyboard.down('ArrowRight')
  await page.waitForTimeout(900)
  await page.keyboard.up('ArrowRight')
  await expect(page.getByRole('heading', { name: '和 NPC 打个招呼', exact: true })).toBeVisible()
  await record(page, info, 'onboarding-real-movement')
  await page.getByRole('button', { name: '关闭引导', exact: true }).click()
  await expect(page.locator('.town-onboarding')).toHaveCount(0)
})
