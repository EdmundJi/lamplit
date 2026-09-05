import { chromium } from '/Users/asherji/code/personal_study/frontend/node_modules/@playwright/test/index.mjs'
const OUT = '/Users/asherji/.claude/jobs/4675194c/tmp/ui'
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('pageerror', e => console.log('PAGEERROR', e.message))
await page.addInitScript(() => { try { localStorage.setItem('better-self:welcome:seen','dismissed') } catch {} })
await page.goto('http://127.0.0.1:5173/auth', { waitUntil: 'domcontentloaded' }); await page.waitForTimeout(1200)
await page.evaluate(async () => { await fetch('/api/v1/auth/login', { method:'POST', credentials:'include', headers:{'Content-Type':'application/json',Accept:'application/json'}, body: JSON.stringify({ email:'town-demo-1788569807@example.com', password:'Correct-Horse-Battery-2026!' }) }) })
for (const r of ['/insights','/friends','/today','/goals','/profile','/settings']) {
  await page.goto('http://127.0.0.1:5173' + r, { waitUntil: 'domcontentloaded' }); await page.waitForTimeout(2200)
  for (const el of await page.$$('.welcome-backdrop button')) { try { await el.click({ timeout: 400 }); break } catch {} }
  await page.screenshot({ path: `${OUT}/v2${r.replace(/\//g,'_')}.png`, fullPage: true })
}
// dark mode
await page.goto('http://127.0.0.1:5173/today', { waitUntil: 'domcontentloaded' }); await page.waitForTimeout(1500)
await page.evaluate(() => document.documentElement.setAttribute('data-theme','dark'))
await page.waitForTimeout(800)
await page.screenshot({ path: `${OUT}/v2_today_dark.png` })
// mobile
const m = await browser.newPage({ viewport: { width: 390, height: 844 } })
await m.addInitScript(() => { try { localStorage.setItem('better-self:welcome:seen','dismissed') } catch {} })
await m.goto('http://127.0.0.1:5173/auth', { waitUntil: 'domcontentloaded' }); await m.waitForTimeout(1000)
await m.evaluate(async () => { await fetch('/api/v1/auth/login', { method:'POST', credentials:'include', headers:{'Content-Type':'application/json',Accept:'application/json'}, body: JSON.stringify({ email:'town-demo-1788569807@example.com', password:'Correct-Horse-Battery-2026!' }) }) })
for (const r of ['/today','/insights']) {
  await m.goto('http://127.0.0.1:5173' + r, { waitUntil: 'domcontentloaded' }); await m.waitForTimeout(2000)
  for (const el of await m.$$('.welcome-backdrop button')) { try { await el.click({ timeout: 400 }); break } catch {} }
  await m.screenshot({ path: `${OUT}/m2${r.replace(/\//g,'_')}.png`, fullPage: true })
}
await browser.close(); console.log('done')
