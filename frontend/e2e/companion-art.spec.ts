import { expect, test } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { CAFE_SERVICE, CAFE_SEATS, CAFE_WINDOW_SEATS, POSITION_SLOTS } from '../src/modules/companion/companion-art'
import { companionPath, COMPANION_COLLISION } from '../src/modules/companion/companion-navigation'
import { canStand } from '../src/shared/scene/collision'
import { clearSegment } from '../src/shared/scene/pathfinding'

function sampledMovementConnects(from: { x: number; y: number }, to: { x: number; y: number }) {
  if (clearSegment(from, to, COMPANION_COLLISION)) return true
  // A 120ms sample can straddle a real 90-degree corner. Joining only its two endpoints would
  // falsely cut across the unwalkable corner; allow a short, verified bend, not a route around a table.
  const path = companionPath(from, to)
  if (!path.length) return false
  let previous = from, distance = 0
  for (const point of path) { distance += Math.hypot(point.x - previous.x, point.y - previous.y); previous = point }
  return distance <= Math.hypot(to.x - from.x, to.y - from.y) + 4
}

test('licensed action art loads and topic emoji remain readable in the pixel bubble', async ({ page }, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('response', response => {
    if (response.url().includes('/assets/town/') && response.status() >= 400) errors.push(response.url())
  })
  await page.goto('/harness/companion-art.html')
  await expect(page.locator('canvas')).toBeVisible()
  await expect(page.locator('.resident-person')).toHaveCount(5)
  await expect(page.locator('.resident-person[aria-label^="阿川"]')).toHaveAttribute('aria-label', /正在创作/)
  await page.screenshot({ path: testInfo.outputPath('afternoon.png'), fullPage: true })
  await page.getByRole('button', { name: '看看全街', exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('overview.png'), fullPage: true })
  await page.getByRole('button', { name: '聚焦生活区', exact: true }).click()
  await page.getByRole('button', { name: '看细雨', exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('rain.png'), fullPage: true })
  await page.getByRole('button', { name: '看晴天', exact: true }).click()
  await page.getByRole('button', { name: '看夜晚', exact: true }).click()
  await page.screenshot({ path: testInfo.outputPath('evening.png'), fullPage: true })
  await page.getByRole('button', { name: '看白天', exact: true }).click()
  await page.getByRole('button', { name: '聊聊海报' }).click()
  const topic = page.locator('.resident-topic').first()
  await expect(topic).toBeVisible({ timeout: 15000 })
  await expect(topic.locator('.resident-topic-emoji')).toHaveText(/🎨|☕/)
  await topic.focus()
  await expect(page.getByRole('tooltip')).toContainText('海报')
  await page.screenshot({ path: testInfo.outputPath('conversation.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('canvas')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('mobile.png'), fullPage: true })
  expect(errors).toEqual([])
})

test('opposing cafe seats and the staff route remain grounded during real movement', async ({ page }) => {
  test.setTimeout(60_000)
  const output = resolve(process.cwd(), '../output/town-life-review/cafe-layout/after-window-row')
  await mkdir(output, { recursive: true })
  await page.goto('/harness/companion-art.html')
  await expect(page.locator('.resident-person')).toHaveCount(5)
  await page.getByRole('button', { name: '看咖啡厅', exact: true }).click()
  await page.screenshot({ path: resolve(output, 'cafe-close.png'), fullPage: true })
  const ids = ['self', 'ahe', 'achuan', 'xiaxia', 'alin']
  type Sample = { id: string; x: number; y: number; facing: string | null }
  const samples: Sample[][] = []
  async function settle(targets: Record<string, { x: number; y: number }>) {
    await expect.poll(async () => {
      const sample = await page.locator('.resident-person').evaluateAll(nodes => nodes.map(node => ({
        id: node.getAttribute('data-resident-id')!, x: Number(node.getAttribute('data-world-x')),
        y: Number(node.getAttribute('data-world-y')), facing: node.getAttribute('data-facing'),
      })))
      samples.push(sample)
      return sample.every(point => !targets[point.id] || Math.hypot(point.x - targets[point.id]!.x, point.y - targets[point.id]!.y) < .5)
    }, { timeout: 25_000, intervals: [120] }).toBe(true)
  }
  await page.getByRole('button', { name: '看五席入座', exact: true }).click()
  await settle(Object.fromEntries(ids.map((id, index) => [id, CAFE_SEATS[index]!])))
  for (const [index, id] of ids.entries()) {
    await expect(page.locator(`[data-resident-id="${id}"]`)).toHaveAttribute('data-facing', CAFE_SEATS[index]!.facing)
  }
  await page.screenshot({ path: resolve(output, 'five-seats.png'), fullPage: true })
  await page.getByRole('button', { name: '看点单与服务', exact: true }).click()
  await settle({ self: CAFE_SERVICE.waiting[0], ahe: CAFE_SERVICE.operator })
  await expect(page.locator('[data-resident-id="ahe"]')).toHaveAttribute('data-facing', 'up')
  await page.screenshot({ path: resolve(output, 'service-close.png'), fullPage: true })
  await page.getByRole('button', { name: '看看全街', exact: true }).click()
  await page.screenshot({ path: resolve(output, 'overview.png'), fullPage: true })
  await page.getByRole('button', { name: '看夜晚', exact: true }).click()
  await page.screenshot({ path: resolve(output, 'evening-overview.png'), fullPage: true })
  await page.getByRole('button', { name: '聚焦生活区', exact: true }).click()
  await page.screenshot({ path: resolve(output, 'evening-cafe.png'), fullPage: true })
  // These are sampled Phaser actor coordinates exposed by the same labels the UI positions.
  // Every sample and every short movement segment must stay off the actual furniture footprint.
  const previous = new Map<string, Sample>()
  for (const sample of samples) for (const point of sample) {
    expect(canStand(point, COMPANION_COLLISION), `${point.id} at ${point.x},${point.y}`).toBe(true)
    const from = previous.get(point.id)
    if (from) expect(sampledMovementConnects(from, point), `${point.id} crossed furniture`).toBe(true)
    previous.set(point.id, point)
  }
  await writeFile(resolve(output, 'movement-samples.json'), JSON.stringify(samples, null, 2))
})

test('six independent window desks remain reachable beside occupied discussion tables and service space', async ({ page }) => {
  test.setTimeout(60_000)
  const output = resolve(process.cwd(), '../output/town-life-review/cafe-layout/after-window-row')
  await mkdir(output, { recursive: true })
  await page.goto('/harness/companion-art.html')
  await expect(page.locator('.resident-person')).toHaveCount(5)
  await page.getByRole('button', { name: '看咖啡厅', exact: true }).click()
  await page.getByRole('button', { name: '看窗边与讨论区', exact: true }).click()
  await expect(page.locator('.resident-person')).toHaveCount(11)
  const windows = ['student', 'artist', 'gardener', 'window-guest-4', 'window-guest-5', 'window-guest-6']
  for (const [index, id] of windows.entries()) {
    const person = page.locator(`[data-resident-id="${id}"]`)
    await expect(person).toHaveAttribute('data-world-x', String(CAFE_WINDOW_SEATS[index]!.x))
    await expect(person).toHaveAttribute('data-world-y', String(CAFE_WINDOW_SEATS[index]!.y))
    await expect(person).toHaveAttribute('data-facing', 'right')
  }
  for (const [index, id] of ['self', 'discussion-a', 'discussion-b', 'discussion-c'].entries()) {
    await expect(page.locator(`[data-resident-id="${id}"]`)).toHaveAttribute('data-facing', CAFE_SEATS[index]!.facing)
  }
  await expect(page.locator('[data-resident-id="owner"]')).toHaveAttribute('data-facing', 'up')
  await page.screenshot({ path: resolve(output, 'window-row-and-discussion.png'), fullPage: true })
  await page.getByRole('button', { name: '看看全街', exact: true }).click()
  await page.screenshot({ path: resolve(output, 'window-row-overview.png'), fullPage: true })
  await page.getByRole('button', { name: '聚焦生活区', exact: true }).click()
  await page.getByRole('button', { name: '看夜晚', exact: true }).click()
  await page.screenshot({ path: resolve(output, 'window-row-evening.png'), fullPage: true })
  await page.getByRole('button', { name: '看白天', exact: true }).click()

  // Send one reader to the public entrance and back while all other chairs remain occupied.
  const fixture = await page.locator('.resident-person').evaluateAll(nodes => nodes.map(node => ({ id: node.getAttribute('data-resident-id')!, name: node.getAttribute('aria-label')!.split('，')[0]!, role: '受控访客' })))
  const positions: Record<string, string> = { self: 'cafe-worktable', 'discussion-a': 'cafe-worktable', 'discussion-b': 'cafe-worktable', 'discussion-c': 'cafe-worktable', student: 'cafe-window-seat', artist: 'cafe-window-2', gardener: 'cafe-window-3', 'window-guest-4': 'cafe-window-4', 'window-guest-5': 'cafe-window-5', 'window-guest-6': 'cafe-window-6', owner: 'cafe-counter' }
  const atSeat = fixture.map(person => ({ ...person, location: 'cafe', activity: person.id === 'owner' ? 'prepare' : 'read', action: '受控路线检查', positionId: positions[person.id] }))
  const walking = atSeat.map(person => person.id === 'artist' ? { ...person, location: 'cafe', activity: 'travel', destination: 'cafe', positionId: null } : person)
  await page.evaluate(value => (window as any).cafeLayoutHarness.setResidents(value), walking)
  const reader = page.locator('[data-resident-id="artist"]')
  await expect.poll(async () => Math.abs(Number(await reader.getAttribute('data-world-y')) - CAFE_SERVICE.entry.y), { timeout: 15_000 }).toBeLessThan(.5)
  await page.evaluate(value => (window as any).cafeLayoutHarness.setResidents(value), atSeat)
  const samples: { x: number; y: number }[] = []
  await expect.poll(async () => {
    const point = { x: Number(await reader.getAttribute('data-world-x')), y: Number(await reader.getAttribute('data-world-y')) }
    samples.push(point)
    return Math.hypot(point.x - POSITION_SLOTS['cafe-window-2']![0]!.x, point.y - POSITION_SLOTS['cafe-window-2']![0]!.y)
  }, { timeout: 15_000, intervals: [100] }).toBeLessThan(.5)
  for (const [index, point] of samples.entries()) {
    expect(canStand(point, COMPANION_COLLISION)).toBe(true)
    if (index) expect(sampledMovementConnects(samples[index - 1]!, point)).toBe(true)
  }
  await expect(reader).toHaveAttribute('data-facing', 'right')
  await writeFile(resolve(output, 'window-entry-movement.json'), JSON.stringify(samples, null, 2))
})
