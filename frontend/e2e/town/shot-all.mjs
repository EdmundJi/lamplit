import { chromium } from '/Users/asherji/code/personal_study/frontend/node_modules/@playwright/test/index.mjs'
const OUT = '/Users/asherji/.claude/jobs/4675194c/tmp/ui'
const routes = ['/today','/goals','/ai','/attributes','/insights','/partners','/friends','/friends/chat','/profile','/settings']
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('pageerror', e => console.log('PAGEERROR', e.message))
await page.addInitScript(() => { try { localStorage.setItem('better-self:welcome:seen','dismissed') } catch {} })
await page.goto('http://localhost:5174/auth', { waitUntil: 'domcontentloaded' }); await page.waitForTimeout(1200)
const st = await page.evaluate(async () => (await fetch('/api/v1/auth/login', { method:'POST', credentials:'include', headers:{'Content-Type':'application/json',Accept:'application/json'}, body: JSON.stringify({ email:'town-demo-1788569807@example.com', password:'Correct-Horse-Battery-2026!' }) })).status)
console.log('login', st)
for (const r of routes) {
  await page.goto('http://localhost:5174' + r, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2600)
  for (const el of await page.$$('.welcome-backdrop button')) { try { await el.click({ timeout: 400 }); break } catch {} }
  const name = r.replace(/\//g,'_').replace(/^_/,'') || 'root'
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true })
  console.log('shot', r)
}
await browser.close()
