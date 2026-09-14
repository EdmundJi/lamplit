import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// Generate this response via the real HTTP integration test first:
// cd backend && ./mvnw -Dtest=CompanionV2HttpIT test
// These browser checks replay that isolated test town; they never contact a user's world.
function savedSnapshot() {
  const path = process.env.TOWN_V2_SNAPSHOT ?? resolve(process.cwd(), '../backend/target/town-v2-http-world.json')
  try { return JSON.parse(readFileSync(path, 'utf8')) }
  catch { throw new Error(`Missing V2 HTTP response at ${path}. Run backend CompanionV2HttpIT first.`) }
}

async function townFixture(page: Page, failFirstLoad = false) {
  const snapshot = savedSnapshot()
  expect(snapshot.world.residents).toHaveLength(25)
  const now = new Date().toISOString()
  const writes: string[] = []
  let failing = failFirstLoad
  await page.addInitScript(() => {
    localStorage.setItem('better-self:welcome:town-v2-test', 'dismissed')
    localStorage.setItem('better-self:appearance', JSON.stringify({ theme: 'light', accent: 'forest', motion: 'off' }))
  })
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    if (route.request().method() !== 'GET') writes.push(path)
    if (path === '/town/companion' && failing) {
      await route.fulfill({ status: 503, json: { error: { code: 'UNAVAILABLE', message: '连接暂时中断' } } })
      return
    }
    let data: unknown = []
    if (path === '/me') data = { publicId: 'town-v2-test', displayName: '地图验收', email: 'fixture@example.test', role: 'USER', timezone: 'Asia/Shanghai' }
    else if (path === '/town/companion' || path === '/town/companion/advance') data = snapshot
    else if (path === '/friends/unread-summary') data = { totalUnread: 0 }
    else if (path === '/partners/profile') data = { pets: [], selectedPet: null, wallet: { coinBalance: 0 } }
    await route.fulfill({ json: { data, requestId: 'town-v2-browser', timestamp: now } })
  })
  return { snapshot, writes, reconnect: () => { failing = false } }
}

function expectedResidentIds(snapshot: any) {
  return [snapshot.world.avatar, ...snapshot.world.residents].map((resident: { id: string }) => resident.id).sort()
}

async function expectCompleteResidentRoster(page: Page, snapshot: any) {
  const ids = await page.locator('.companion-scene__roster [data-resident-id]').evaluateAll(nodes => nodes.map(node => node.getAttribute('data-resident-id')).filter((id): id is string => Boolean(id)).sort())
  expect(ids).toEqual(expectedResidentIds(snapshot))
}

test('the saved 25-person town renders, navigates, and keeps viewing separate from life commands', async ({ page }, info) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('response', response => {
    if (response.url().includes('/assets/town/') && response.status() >= 400) errors.push(`${response.status()} ${response.url()}`)
  })
  const { writes, snapshot } = await townFixture(page)
  await page.goto('/town')
  await expect(page.getByTestId('town-v2')).toBeVisible()
  await expect(page.locator('.companion-scene canvas')).toBeVisible({ timeout: 30_000 })
  // A close camera deliberately exposes only one non-selected edge pin per direction. The roster
  // is the complete accessible resident index, rather than treating a crowded edge as evidence
  // that all 26 people are visible in this particular camera frame.
  await expectCompleteResidentRoster(page, snapshot)
  expect(await page.locator('.resident-person.is-offscreen').count()).toBeLessThanOrEqual(4)
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: info.outputPath('town.png'), fullPage: true })
  // The full-world view also catches actors that accidentally resolve to the old street fallback.
  await page.getByRole('button', { name: '看整条街', exact: true }).click()
  await expect(page.locator('.resident-person')).toHaveCount(26)
  expect((await page.locator('.resident-person').evaluateAll(nodes => nodes.map(node => node.getAttribute('data-resident-id')).filter((id): id is string => Boolean(id)).sort()))).toEqual(expectedResidentIds(snapshot))
  await page.screenshot({ path: info.outputPath('overview.png'), fullPage: true })
  await page.getByRole('button', { name: '查看地点与居民', exact: true }).click()
  await expect(page.getByTestId('place-browser')).toBeVisible()
  await expect(page.locator('[data-place-id="shop"]').first()).toBeVisible()
  for (const place of ['academy', 'gym', 'board', 'shop', 'garden', 'cafe']) {
    await page.locator(`button[data-place-id="${place}"]`).first().click()
    await expect(page.locator('.companion-scene canvas')).toBeVisible()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
  expect(writes.filter(path => path !== '/town/companion/advance')).toEqual([])
  expect(errors).toEqual([])
})

test('a failed initial load can reconnect without treating it as an empty town', async ({ page }) => {
  const fixture = await townFixture(page, true)
  await page.goto('/town')
  await expect(page.getByRole('button', { name: '重新连接', exact: true })).toBeVisible()
  await expect(page.locator('.arrival form')).toHaveCount(0)
  fixture.reconnect()
  await page.getByRole('button', { name: '重新连接', exact: true }).click()
  await expect(page.locator('.companion-scene canvas')).toBeVisible({ timeout: 30_000 })
  await expectCompleteResidentRoster(page, fixture.snapshot)
  expect(await page.locator('.resident-person.is-offscreen').count()).toBeLessThanOrEqual(4)
})
