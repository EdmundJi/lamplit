import { chromium } from '/Users/asherji/code/personal_study/frontend/node_modules/@playwright/test/index.mjs'
const OUT = '/Users/asherji/.claude/jobs/4675194c/tmp'
const email = process.argv[2] || 'town-demo-1788569807@example.com'
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('pageerror', e => console.log('PAGEERROR', e.message))
await page.addInitScript(() => { try { localStorage.setItem('better-self:welcome:seen', 'dismissed') } catch {} })
await page.goto('http://127.0.0.1:5173/auth', { waitUntil: 'domcontentloaded' }); await page.waitForTimeout(1500)
const login = await page.evaluate(async ({ email }) => {
  const r = await fetch('/api/v1/auth/login', { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify({ email, password: 'Correct-Horse-Battery-2026!' }) })
  return r.status
}, { email })
console.log('login status', login)
await page.goto('http://127.0.0.1:5173/town', { waitUntil: 'domcontentloaded' })
await page.waitForSelector('canvas', { timeout: 30000 })
await page.waitForTimeout(3500)
for (const el of await page.$$('.welcome-backdrop button')) { try { await el.click({ timeout: 500 }); break } catch {} }
await page.screenshot({ path: `${OUT}/real-town.png` })
await page.getByRole('button', { name: '找小助' }).click()
await page.waitForTimeout(1500)
await page.screenshot({ path: `${OUT}/real-dialogue-open.png` })
const input = page.locator('.town-panel textarea, .town-panel input[type="text"], .town-panel input').first()
await input.fill('五分钟看完了，感觉还行，接下来呢？')
const t0 = Date.now()
await input.press('Enter')
const body = page.locator('.npc-dialogue .dialogue-body, .npc-dialogue').first()
let firstText = null
for (let i = 0; i < 180; i++) {
  const txt = (await body.innerText().catch(() => '')) || ''
  if (!firstText && txt && !txt.includes('在想怎么说')) { firstText = txt; console.log(`+${((Date.now()-t0)/1000).toFixed(1)}s first visible text: ${txt.slice(0, 80).replace(/\n/g, ' | ')}`) }
  const chips = await page.locator('.npc-chip').count().catch(() => 0)
  const acts = await page.locator('.npc-action-button').count().catch(() => 0)
  if (firstText && chips > 0) { console.log(`+${((Date.now()-t0)/1000).toFixed(1)}s chips=${chips} actions=${acts}`); break }
  await page.waitForTimeout(1000)
}
await page.waitForTimeout(1500)
console.log('final text:\n' + (await body.innerText().catch(() => '(none)')))
await page.screenshot({ path: `${OUT}/real-dialogue-reply.png` })
await browser.close()
console.log('done in', ((Date.now()-t0)/1000).toFixed(1), 's')
