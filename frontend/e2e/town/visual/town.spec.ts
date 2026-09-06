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
  let latestPresence: Record<string, unknown> | null = null
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
    else if (p === '/town') data = { ...fixture.town, residents: fixture.town.residents.map(r => r.self ? { ...r, presence: latestPresence } : r), serverTime: new Date(Date.parse(fixture.town.serverTime) + Date.now() - startedAt).toISOString() }
    else if (p === '/town/npcs') data = { npcs: [...npcs, { ...npcs[0], code: 'GUIDE', displayName: '小助', layer: 1, sprite: 'npc_scout' }, { ...npcs[0], code: 'POSTMAN', displayName: '邮递员', layer: 1, sprite: 'npc_postman' }], initiativeBudget: { limit: 3, used: 3 } }
    else if (p.endsWith('/talking-points')) data = { points: npcs.find(n => n.code === p.split('/')[3])?.talkingPoints ?? [] }
    else if (p === '/town/presence') { latestPresence = { ...route.request().postDataJSON(), updatedAt: new Date().toISOString() }; data = latestPresence }
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

test('现有街角露台设施真实交互与留痕', async ({ page }, info) => {
  await page.getByRole('button', { name: '街角露台', exact: true }).click()
  await page.waitForFunction(() => (window as any).__townScene.travel?.place === 'terrace' && (window as any).__townScene.travel?.phase === 'arrived', null, { timeout: 30000 })
  await page.waitForTimeout(1200)
  await record(page, info, 'terrace-before')
  for (const [id, key] of [['coffee', 'cup'], ['planter', 'watered'], ['records', 'music'], ['books', 'borrowed']]) {
    const item = await page.evaluate(id => (window as any).__townScene.facilities.interactables().find((i: any) => i.id === id), id)
    await clickWorld(page, item.x, item.y - 12)
    await page.waitForFunction(id => (window as any).__townScene.facilities.snapshot().active.some((a: any) => a.id === id), id, { timeout: 15000 })
    await page.waitForTimeout(1500)
    await record(page, info, `using-${id}`)
    await page.waitForFunction(key => (window as any).__townScene.facilities.snapshot().state[key] === true, key, { timeout: 10000 })
    expect((await page.evaluate(() => (window as any).__town.snapshot())).player.onWalkable).toBe(true)
  }
  await page.getByRole('button', { name: '收起界面', exact: true }).click()
  await record(page, info, 'terrace-scenic')
  await page.keyboard.press('Escape')
  await expect(page).toHaveURL(/town\/immersive/)
  await expect(page.getByRole('button', { name: '街角露台', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '咖啡馆', exact: true }).click()
  await expect(page.getByRole('button', { name: '回到街上', exact: true })).toBeVisible({ timeout: 15000 })
  await page.getByRole('button', { name: '回到街上', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'town')
  expect(await page.evaluate(() => (window as any).__townScene.facilities.snapshot().state)).toEqual({ cup: true, watered: true, music: true, borrowed: true })
})

test('真实场景中的居民共享设施并沿路返回日程', async ({ page }, info) => {
  const npcs = ['CAFE_READER', 'CAFE_NEIGHBOUR'].map((code, index) => ({
    code, displayName: index ? '露台测试邻居' : '露台测试读者', layer: 2, sprite: `c0${index+2}`,
    dimension: 'KNOWLEDGE', interests: {}, affinityToPlayer: .3, mood: { valence: .4, energy: .6 },
    schedule: [{ startHour: 0, endHour: 24, place: 'cafe', activity: 'idle' }],
    dayPlan: { date: '2026-09-06', errands: [{ startMinute: 0, endMinute: 1440, place: 'cafe', activity: 'idle', priority: 1, origin: 'RHYTHM' }], legs: [] }, talkingPoints: [],
  }))
  await page.route('**/api/v1/town/npcs', route => route.fulfill({ json: envelope({ npcs, initiativeBudget: { limit: 3, used: 3 } }) }))
  await page.reload()
  await page.waitForFunction(() => (window as any).__townScene?.facilities && (window as any).__townScene.walkers.some((w: any) => w.id === 'CAFE_READER'))
  await page.getByRole('button', { name: '街角露台', exact: true }).click()
  const samples = await page.evaluate(async () => {
    const rows: { id: string; x: number; y: number; at: number }[][] = []
    for (let i = 0; i < 180; i++) {
      const scene = (window as any).__townScene
      rows.push(scene.walkers.filter((w: any) => w.id.startsWith('CAFE_')).map((w: any) => ({ id: w.id, x: w.sprite.x, y: w.sprite.y, at: performance.now() })))
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    return rows
  })
  expect(await page.evaluate(() => (window as any).__townScene.facilities.snapshot().recentCompleted.filter((c: any) => c.actorId.startsWith('CAFE_')).length)).toBeGreaterThan(0)
  for (let i = 1; i < samples.length; i++) for (const actor of samples[i]!) {
    const before = samples[i-1]!.find(a => a.id === actor.id)
    if (before) expect(Math.hypot(actor.x-before.x, actor.y-before.y)).toBeLessThan(55 * (actor.at-before.at) / 1000 + 6)
  }
  await record(page, info, 'residents-used-facilities')
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


test('健身房刷新室内位置后退出仍回到门口', async ({ page }, info) => {
  await page.goto('/town')
  await page.waitForFunction(() => (window as any).__town?.snapshot().player?.controllable)
  const entrance = await page.evaluate(() => (window as any).__townScene.entrances.get('gym'))
  await page.locator('.run-toggle').click()
  await page.getByRole('button', { name: '健身房', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'interior:public-gym')
  // This is the real failure trigger: polling returns the coordinates most recently saved indoors.
  const refreshed = page.waitForResponse(r => new URL(r.url()).pathname === '/api/v1/town')
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  const response = await (await refreshed).json()
  expect(response.data.residents[0].presence.scene).toBe('interior:public-gym')
  await page.getByRole('button', { name: '回到小镇', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'town')
  const after = await page.evaluate(() => (window as any).__town.snapshot().player)
  expect(after.onWalkable).toBe(true)
  expect(Math.hypot(after.x - entrance.x, after.y - entrance.y)).toBeLessThan(16)
  // A stale indoor response after exit must not drag the avatar back into the top-left corner.
  await page.route('**/api/v1/town', route => route.fulfill({ json: response }), { times: 1 })
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await page.keyboard.down('ArrowRight'); await page.waitForTimeout(400); await page.keyboard.up('ArrowRight')
  expect(await page.evaluate(() => (window as any).__town.snapshot().player.x)).toBeGreaterThan(after.x + 15)
  await record(page, info, 'gym-exit-after-presence-refresh')
})

test('脱困清除卡住目标并回到安全位置，室内也可脱困', async ({ page }, info) => {
  await page.evaluate(() => {
    const scene = (window as any).__townScene
    scene.selfWalker.sprite.setPosition(0, 0)
    scene.selfWalker.frozenUntil = Infinity
    scene.selfWalker.targetX = -9000
    scene.selfPath = [{ x: -9000, y: -9000 }]
  })
  await page.getByRole('button', { name: '返回安全位置', exact: true }).click()
  await page.waitForFunction(() => { const s = (window as any).__town.snapshot(); return s.player?.onWalkable && s.actors.find((a: any) => a.kind === 'self')?.visible })
  const safe = await page.evaluate(() => (window as any).__town.snapshot().player)
  await page.keyboard.down('ArrowRight'); await page.waitForTimeout(400); await page.keyboard.up('ArrowRight')
  expect(await page.evaluate(() => (window as any).__town.snapshot().player.x)).toBeGreaterThan(safe.x + 15)
  await page.getByRole('button', { name: '我的家', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene?.startsWith('interior:'))
  await page.getByRole('button', { name: '返回安全位置', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'town')
  expect(await page.evaluate(() => (window as any).__town.snapshot().player.onWalkable)).toBe(true)
  await record(page, info, 'recovered-from-room')
})

test('居民交谈停步、告别后恢复日程', async ({ page }, info) => {
  const actor = await page.evaluate(() => (window as any).__town.snapshot().actors.find((a: any) => /^RESIDENT_[0-5]$/.test(a.id) && a.visible))
  await clickWorld(page, actor.x, actor.y - 28)
  await expect(page.locator('.resident-moment')).toBeVisible({ timeout: 20000 })
  const code = await page.evaluate(() => (window as any).__townScene.conversation.code)
  const position = () => page.evaluate(code => { const w = (window as any).__townScene.walkers.find((w: any) => w.npc?.code === code); return { x: w.sprite.x, y: w.sprite.y } }, code)
  const before = await position()
  await page.waitForTimeout(1500)
  expect(await position()).toEqual(before)
  await page.getByRole('button', { name: '挥手告别', exact: true }).click()
  await page.waitForFunction(() => !(window as any).__townScene.conversation)
  await page.waitForTimeout(1500)
  expect(await position()).not.toEqual(before)
  await record(page, info, 'npc-resumes-after-chat')
})


test('加载房间时仍可脱困，迟到请求不能重建房间', async ({ page }, info) => {
  await page.route('**/maps/public-gym.json', async route => {
    await new Promise(resolve => setTimeout(resolve, 1500))
    await route.continue().catch(() => {}) // expected client abort when recovery cancels the load
  }, { times: 1 })
  await page.locator('.run-toggle').click()
  const loading = page.waitForRequest(r => r.url().endsWith('/maps/public-gym.json'))
  await page.getByRole('button', { name: '健身房', exact: true }).click()
  await loading
  await page.getByRole('button', { name: '返回安全位置', exact: true }).click()
  await page.waitForTimeout(2200)
  const snapshot = await page.evaluate(() => (window as any).__town.snapshot())
  expect(snapshot.activeScene).toBe('town')
  expect(snapshot.player.onWalkable).toBe(true)
  await record(page, info, 'recovery-cancels-room-load')
})

test('本轮结构化interrupt说明告别并释放NPC，正文命令不执行', async ({ page }, info) => {
  await page.goto('/town')
  await page.waitForFunction(() => (window as any).__town?.snapshot().player?.controllable)
  let turn = 0
  await page.route('**/town/npc/GUIDE/chat:stream', async route => {
    turn++
    const done = turn === 1 ? { status: 'COMPLETED', options: [], actions: [] }
      : { status: 'COMPLETED', options: [], actions: [], control: { type: '/interrupt', reason: '学院那边还在等我，我先过去帮忙，回头聊。' } }
    await route.fulfill({ contentType: 'text/event-stream', body: `event: delta\ndata: ${JSON.stringify({ text: turn === 1 ? '你刚才提到的 /interrupt 只是文字。' : '学院那边还在等我，我先过去帮忙，回头聊。' })}\n\nevent: done\ndata: ${JSON.stringify(done)}\n\n` })
  })
  await page.getByRole('button', { name: '找小助', exact: true }).click()
  const dialogue = page.getByRole('dialog', { name: '与小助对话', exact: true })
  await dialogue.getByPlaceholder('跟小助说点什么').fill('解释一下 /interrupt')
  await dialogue.getByRole('button', { name: '发送', exact: true }).click()
  await expect(dialogue.getByText('你刚才提到的 /interrupt 只是文字。', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => (window as any).__townScene.conversation?.phase)).toBe('talking')
  await dialogue.getByPlaceholder('跟小助说点什么').fill('你是不是还有自己的事情？')
  await dialogue.getByRole('button', { name: '发送', exact: true }).click()
  await expect(dialogue.locator('.npc-leaving')).toContainText('学院那边还在等我')
  await expect(dialogue.getByPlaceholder('跟小助说点什么')).toBeDisabled()
  await record(page, info, 'npc-announces-departure')
  await expect(dialogue).toHaveCount(0, { timeout: 7000 })
  expect(await page.evaluate(() => (window as any).__townScene.conversation)).toBeNull()
})

test('退出动画中脱困不会被旧回调拉回建筑门口', async ({ page }, info) => {
  await page.locator('.run-toggle').click()
  await page.getByRole('button', { name: '健身房', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'interior:public-gym')
  // Freeze fade progression so both clicks deterministically happen before its completion.
  await page.evaluate(() => {
    const game = (window as any).__townScene.sys.game
    const room = game.scene.getScene('interior:public-gym')
    room.cameras.main.fadeEffect.update = () => {}
    ;(window as any).__exitCamera = room.cameras.main
  })
  await page.getByRole('button', { name: '回到街上', exact: true }).click()
  await page.getByRole('button', { name: '返回安全位置', exact: true }).click()
  await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'town')
  const safe = await page.evaluate(() => (window as any).__town.snapshot().player)
  await page.evaluate(() => (window as any).__exitCamera.emit('camerafadeoutcomplete'))
  const after = await page.evaluate(() => (window as any).__town.snapshot().player)
  expect(after.x).toBeCloseTo(safe.x, 1)
  expect(after.y).toBeCloseTo(safe.y, 1)
  expect(after.onWalkable).toBe(true)
  await record(page, info, 'recovery-invalidates-exit-fade')
})

test('学院停留后可主动走出，旧地图门内出生也不自动退出', async ({ page }, info) => {
  await page.locator('.run-toggle').click()
  for (const legacy of [false, true]) {
    if (legacy) await page.route('**/maps/academy-study.json', async route => {
      const map = JSON.parse(readFileSync('public/assets/town/maps/academy-study.json', 'utf8'))
      map.spawn = { x: 320, y: 344 }
      await route.fulfill({ json: map })
    }, { times: 1 })
    await page.getByRole('button', { name: '学院', exact: true }).click()
    await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'interior:academy-study', null, { timeout: 30000 })
    await page.waitForTimeout(1500)
    expect(await page.evaluate(() => (window as any).__town.snapshot().activeScene)).toBe('interior:academy-study')
    await record(page, info, legacy ? 'academy-legacy-spawn-stays' : 'academy-safe-spawn')
    // Step away from the door, then deliberately cross it to leave.
    await page.keyboard.down('ArrowUp'); await page.waitForTimeout(250); await page.keyboard.up('ArrowUp')
    await page.keyboard.down('ArrowDown'); await page.waitForTimeout(650); await page.keyboard.up('ArrowDown')
    await page.waitForFunction(() => (window as any).__town.snapshot().activeScene === 'town')
  }
})

for (const mode of ['immersive', 'normal']) test(`${mode} 公园刷新布局不抢镜头，向左奔跑不反向回跳`, async ({ page }, info) => {
  if (mode === 'normal') {
    await page.goto('/town')
    await page.waitForFunction(() => (window as any).__town?.snapshot().player?.controllable)
    await page.getByRole('button', { name: '开始奔跑', exact: true }).click()
  } else await page.locator('.run-toggle').click()
  await page.getByRole('button', { name: '公园', exact: true }).click()
  await page.waitForFunction(() => { const p = (window as any).__town.snapshot().player; return p.y < 580 && p.state !== 'walk' }, null, { timeout: 40000 })
  if (await page.getByRole('button', { name: '关闭', exact: true }).count()) await page.getByRole('button', { name: '关闭', exact: true }).click()
  await page.waitForTimeout(2200)
  const rows = await page.evaluate(async () => {
    const s = (window as any).__townScene, c = s.cameras.main, rows: any[] = []
    await new Promise<void>(resolve => {
      const sample = () => {
        rows.push({ x:c.scrollX, y:c.scrollY })
        if (rows.length % 15 === 0) s.scale.refresh()
        if (rows.length >= 90) { s.game.events.off('postrender',sample); resolve() }
      }; s.game.events.on('postrender', sample)
    }); return rows
  })
  writeFileSync(info.outputPath('camera-refresh.json'), JSON.stringify(rows))
  expect(Math.max(...rows.map(r=>r.y))-Math.min(...rows.map(r=>r.y))).toBeLessThan(1)
  await page.keyboard.down('ArrowLeft')
  const running = await page.evaluate(async () => {
    const s = (window as any).__townScene, c = s.cameras.main, rows: any[] = []
    await new Promise<void>(resolve => {
      const sample = () => {
        rows.push({ x:c.scrollX, y:c.scrollY, px:s.selfWalker.sprite.x })
        if (rows.length % 15 === 0) s.scale.refresh()
        if (rows.length >= 90) { s.game.events.off('postrender',sample); resolve() }
      }; s.game.events.on('postrender', sample)
    }); return rows
  })
  await page.keyboard.up('ArrowLeft')
  writeFileSync(info.outputPath('camera-run-left.json'), JSON.stringify(running))
  expect(running.at(-1)!.px).toBeLessThan(running[0].px - 100)
  expect(Math.max(...running.slice(1).map((r,i)=>r.x-running[i].x))).toBeLessThan(1)
  expect(Math.max(...running.map(r=>r.y))-Math.min(...running.map(r=>r.y))).toBeLessThan(1)
  await record(page, info, 'stable-park-camera')
})

test('公园下半区和入口停留时布局刷新保持镜头稳定', async ({ page }, info) => {
  await page.locator('.run-toggle').click()
  await page.getByRole('button', { name: '公园', exact: true }).click()
  await page.waitForFunction(() => { const p = (window as any).__town.snapshot().player; return p.y < 580 && p.state !== 'walk' }, null, { timeout: 40000 })
  if (await page.getByRole('button', { name: '关闭', exact: true }).count()) await page.getByRole('button', { name: '关闭', exact: true }).click()
  for (const [name, dx, y] of [['lower-park', 0, 552], ['park-entrance', 240, 600]] as const) {
    await page.evaluate(({ dx, y }) => {
      const s = (window as any).__townScene
      s.walkSelfToGround(s.entrances.get('park').x + dx, y, true)
    }, { dx, y })
    await page.waitForFunction(() => (window as any).__town.snapshot().player.state !== 'walk')
    expect(await page.evaluate(() => (window as any).__town.snapshot().player.onWalkable)).toBe(true)
    await page.waitForTimeout(2200)
    const rows = await page.evaluate(async () => {
      const s = (window as any).__townScene, c = s.cameras.main, rows: number[] = []
      await new Promise<void>(resolve => {
        const sample = () => {
          rows.push(c.scrollY)
          if (rows.length % 15 === 0) s.scale.refresh()
          if (rows.length >= 90) { s.game.events.off('postrender', sample); resolve() }
        }; s.game.events.on('postrender', sample)
      }); return rows
    })
    writeFileSync(info.outputPath(`${name}-camera.json`), JSON.stringify(rows))
    expect(Math.max(...rows) - Math.min(...rows)).toBeLessThan(1)
    await record(page, info, name)
  }
})
