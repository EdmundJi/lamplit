/** Real backend inspection: no intercepted API responses or synthetic player movement. */
import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const baseURL = process.env.TOWN_BASE_URL ?? 'http://127.0.0.1:5176'
const output = resolve(process.env.TOWN_CAPTURE_DIR ?? '../test-results/town-live')
const email = process.env.TOWN_EMAIL
const password = process.env.TOWN_PASSWORD
if (!email || !password) throw new Error('Set TOWN_EMAIL and TOWN_PASSWORD to a local test account before running this capture.')
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: process.env.TOWN_HEADED !== '1' })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()
const errors = []
page.on('pageerror', error => errors.push({ kind: 'runtime', message: error.message, stack: error.stack }))
page.on('response', response => {
  if (response.status() >= 400 && !response.url().endsWith('/me') && !response.url().endsWith('/auth/refresh')) {
    errors.push({ kind: 'http', status: response.status(), url: response.url() })
  }
})
try {
  await page.goto(new URL('/auth', baseURL).href)
  const login = await page.evaluate(async ({ email, password }) => {
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    })
    return response.status
  }, { email, password })
  if (login !== 200) throw new Error(`Local login failed (${login})`)
  await page.goto(new URL('/town/immersive', baseURL).href)
  await page.waitForFunction(() => window.__town?.snapshot().player?.controllable)
  const skip = page.getByRole('button', { name: '跳过引导', exact: true })
  if (await skip.isVisible()) await skip.click()
  await page.waitForTimeout(500)
  const count = Math.max(1, Math.min(120, Number(process.env.TOWN_CAPTURE_FRAMES) || 4))
  for (let i = 0; i < count; i++) {
    const snapshot = await page.evaluate(() => window.__town.snapshot())
    const name = `frame-${String(i).padStart(3, '0')}`
    await page.screenshot({ path: resolve(output, `${name}.png`) })
    await writeFile(resolve(output, `${name}.json`), JSON.stringify(snapshot, null, 2))
    await writeFile(resolve(output, 'latest.txt'), await page.evaluate(() => window.__town.describe()))
    await writeFile(resolve(output, 'errors.json'), JSON.stringify(errors, null, 2))
    console.log(`${name}: ${snapshot.activeScene}, ${snapshot.actors.length} actors, ${snapshot.renderer?.fps ?? 0}fps, ${errors.length} errors`)
    if (i + 1 < count) await page.waitForTimeout(2000)
  }
  console.log(`Captures: ${output}`)
  if (errors.length) process.exitCode = 1
} finally {
  await browser.close()
}
