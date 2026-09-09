import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

// This is intentionally opt-in: it creates one disposable local test account through the normal
// registration flow and never routes or mocks `/api/v1`. Credentials exist only in this process.
const enabled = process.env.TOWN_REAL_E2E === '1'
const actualDir = resolve(process.cwd(), '../output/town-life-review/actual')

test.describe('real backend town', () => {
  test.skip(!enabled, 'Set TOWN_REAL_E2E=1 only while the local backend is ready for a disposable account.')

  test('a new resident can enter the real town and inspect a neighbour', async ({ page }) => {
    await mkdir(actualDir, { recursive: true })
    const suffix = `${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
    const email = `town-e2e-${suffix}@example.test`
    const password = `Town-test-${suffix}-safe!`

    await page.goto('/auth')
    await page.getByRole('button', { name: '注册', exact: true }).click()
    await page.locator('#email').fill(email)
    await page.locator('#password').fill(password)
    await page.locator('#name').fill('小镇实测')
    await page.locator('#birth').fill('1994-04-18')
    await page.locator('fieldset input[type="checkbox"]').evaluateAll(inputs => inputs.forEach(input => (input as HTMLInputElement).click()))
    await page.getByRole('button', { name: '创建账户', exact: true }).click()
    await page.waitForURL(/\/(onboarding|today)/)

    await page.goto('/town')
    const arrival = page.locator('.arrival')
    await expect(arrival).toBeVisible()
    await page.locator('#neighbor-name').fill('小镇实测')
    await page.getByRole('button', { name: '搬进这条小街', exact: true }).click()
    await expect(page.locator('.companion-scene canvas')).toBeVisible({ timeout: 30_000 })
    await expect(page.getByRole('button', { name: /青叔/ })).toBeVisible({ timeout: 30_000 })
    await page.screenshot({ path: resolve(actualDir, 'actual-default.png'), fullPage: true })

    await page.getByRole('button', { name: /青叔/ }).click()
    await expect(page.getByRole('heading', { name: '青叔', exact: true })).toBeVisible()
    await expect(page.getByText('长期想走的方向', { exact: true })).toBeVisible()
    await page.screenshot({ path: resolve(actualDir, 'actual-resident-panel.png'), fullPage: true })

    await page.locator('.scene-navigation .view-toggle').click()
    await expect(page.locator('.scene-navigation .view-toggle')).toHaveAttribute('aria-pressed', 'true')
    await page.screenshot({ path: resolve(actualDir, 'actual-overview-with-panel.png'), fullPage: true })
    await page.getByRole('button', { name: '收起居民记忆', exact: true }).click()
    await page.screenshot({ path: resolve(actualDir, 'actual-overview.png'), fullPage: true })
  })
})
