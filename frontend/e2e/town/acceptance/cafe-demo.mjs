/** Independent acceptance: real renderer, no response mocks. */
import { chromium } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const out = resolve('../test-results/cafe-demo-acceptance')
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, recordVideo: { dir: out, size: { width: 1440, height: 1000 } } })
const page = await context.newPage()
const errors = []
const results = {}
const checks = {}
page.on('pageerror', e => errors.push({ kind: 'runtime', message: e.message }))
page.on('response', r => { if (r.status() >= 400) errors.push({ kind: 'http', status: r.status(), url: r.url() }) })
const save = async name => {
  await page.screenshot({ path: resolve(out, `${name}.png`), fullPage: true })
  const state = await page.evaluate(() => window.__cafeDemo.snapshot())
  await writeFile(resolve(out, `${name}.json`), JSON.stringify(state, null, 2))
  return state
}
try {
  await page.goto(`${process.env.TOWN_BASE_URL || 'http://127.0.0.1:5176'}/town/demo`)
  await page.waitForFunction(() => window.__cafeDemo?.snapshot().ready)
  await page.getByRole('button', {name: '听听街角', exact: true}).click()
  await page.getByRole('button', {name: '关闭声音', exact: true}).waitFor()
  await page.getByRole('button', {name: '关闭声音', exact: true}).click()
  checks.soundToggle = await page.getByRole('button', {name: '听听街角', exact: true}).isVisible()
  await page.getByRole('button', {name: '再看一次', exact: true}).click()
  await page.getByRole('button', {name: '隐藏气泡', exact: true}).click()
  await page.getByRole('button', {name: '收起界面', exact: true}).click()
  for (let seconds = 0; seconds <= 30; seconds += 5) {
    if (seconds) await page.waitForFunction(ms => window.__cafeDemo.snapshot().elapsed >= ms, seconds * 1000, { timeout: 20000 })
    await save(`silent-${String(seconds).padStart(2, '0')}s`)
  }
  await page.getByRole('button', {name: '显示界面', exact: true}).click()
  await page.getByRole('button', {name: '暂停', exact: true}).click()
  results.pausedBefore = await save('paused-before')
  await page.waitForTimeout(1100)
  results.pausedAfter = await page.evaluate(() => window.__cafeDemo.snapshot())
  await page.evaluate(() => window.__cafeDemo.seek(14000))
  await save('hero-14s-no-bubbles')
  await page.evaluate(() => { window.__cafeDemo.restart(); window.__cafeDemo.setPaused(true); window.__cafeDemo.setLabelsVisible(true) })
  results.restarted = await save('restarted')
  await page.evaluate(() => { window.__cafeDemo.setWalking(true); window.__cafeDemo.setPaused(false) })
  results.walkBefore = await page.evaluate(() => window.__cafeDemo.snapshot())
  await page.keyboard.down('ArrowRight')
  await page.waitForTimeout(900)
  await page.keyboard.up('ArrowRight')
  results.walkAfter = await save('walked-right')
  const canvasBox = await page.locator('canvas').boundingBox()
  await page.mouse.click(canvasBox.x + canvasBox.width * 0.68, canvasBox.y + canvasBox.height * 0.77)
  await page.waitForTimeout(4500)
  results.clickAfter = await save('clicked-path')
  await page.getByRole('button', {name: '收起界面', exact: true}).click()
  results.clean = await save('clean-desktop')
  await page.getByRole('button', {name: '显示界面', exact: true}).click()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', {name: '保存画面', exact: true}).click()
  const download = await downloadPromise
  await download.saveAs(resolve(out, 'exported-frame.png'))
  checks.exportPng = download.suggestedFilename().endsWith('.png')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.waitForTimeout(800)
  results.mobile = await save('mobile-portrait')
  results.mobileOverflow = await page.evaluate(() => ({ width: innerWidth, scrollWidth: document.documentElement.scrollWidth }))
  await page.setViewportSize({ width: 844, height: 390 })
  await page.waitForTimeout(800)
  results.landscape = await save('mobile-landscape')
  results.buttons = await page.getByRole('button').allTextContents()
  checks.pauseFreezesWorld = JSON.stringify(results.pausedBefore) === JSON.stringify(results.pausedAfter)
  checks.restartResetsLife = results.restarted.elapsed === 0 && !results.restarted.life.wetSoil && results.restarted.life.cups.every(c => c.location === 'hand')
  checks.keyboardMovesPlayer = results.walkAfter.player.x > results.walkBefore.player.x + 30 && results.walkAfter.player.standable
  checks.clickMovesPlayer = results.clickAfter.player.x > results.walkAfter.player.x + 100 && results.clickAfter.player.standable
  checks.mobileNoHorizontalOverflow = results.mobileOverflow.scrollWidth === results.mobileOverflow.width
  checks.noRuntimeOrHttpErrors = errors.length === 0
  await writeFile(resolve(out, 'results.json'), JSON.stringify({ checks, results, errors }, null, 2))
  if (Object.values(checks).some(v => !v)) process.exitCode = 1
  console.log(JSON.stringify({ out, checks, errors, buttons: results.buttons, mobileOverflow: results.mobileOverflow }, null, 2))
} finally { await context.close(); await browser.close() }
