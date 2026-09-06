/** Capture-only film direction. No test runner/assertions, credentials, or actor/clock rewrites. */
import { chromium } from '@playwright/test'
import { installTownDemoData } from '../e2e/town/acceptance/town-demo-data.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'

const base = process.env.TOWN_BASE_URL || 'http://127.0.0.1:5184'
const out = resolve(process.env.TOWN_FILM_DIR || '../artifacts/town-film-v2')
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 })
const page = await context.newPage()
await installTownDemoData(page)
const notes = [], errors = []
page.on('pageerror', e => errors.push(e.message))
async function clickWorld(point) {
  const pixel = await page.evaluate(p => {
    const s = window.__townScene, c = s.cameras.main, origin = c.getWorldPoint(0, 0)
    const b = document.querySelector('canvas').getBoundingClientRect(), z = s.scale.gameSize
    return { x: b.left + (p.x-origin.x)*c.zoom*b.width/z.width, y: b.top + (p.y-origin.y)*c.zoom*b.height/z.height }
  }, point)
  await page.mouse.click(pixel.x, pixel.y)
  await page.mouse.move(1890, 1060)
}
async function facility(id) { return page.evaluate(id => window.__townScene.facilities.interactables().find(i => i.id === id), id) }
async function use(id) {
  const item = await facility(id)
  const since = await page.evaluate(() => window.__townScene.facilities.snapshot().recentCompleted.at(-1)?.atMs ?? -1)
  await clickWorld({ x: item.x, y: item.y-12 })
  await page.waitForFunction(({ id, since }) => {
    const s = window.__townScene
    return s.facilities.snapshot().recentCompleted.some(c => c.id === id && c.actorId === s.selfWalker.id && c.atMs > since)
  }, { id, since }, { timeout: 25000 })
}
async function camera(x, y, zoom, duration = 0) {
  await page.evaluate(({ x, y, zoom, duration }) => {
    const s = window.__townScene, c = s.cameras.main
    s.releaseCameraFollow(120000); c.panEffect.reset(); c.zoomEffect.reset()
    if (duration) { c.pan(x,y,duration,'Sine.easeInOut'); c.zoomTo(zoom,duration,'Sine.easeInOut') }
    else c.setZoom(zoom).centerOn(x,y)
  }, { x, y, zoom, duration })
}
let start = 0
async function at(seconds) { await page.waitForTimeout(Math.max(0, start + seconds*1000 - Date.now())) }
try {
  await page.goto(base + '/town/immersive')
  try { await page.waitForFunction(() => window.__town?.snapshot().player?.controllable) }
  catch(error) {
    await page.screenshot({path:resolve(out,'capture-startup.png')})
    console.log(JSON.stringify({errors,body:await page.locator('body').innerText()},null,2))
    throw error
  }
  await page.getByRole('button', { name: '街角露台', exact: true }).click()
  await page.waitForFunction(() => window.__townScene.travel?.place === 'terrace' && window.__townScene.travel.phase === 'arrived', null, { timeout: 50000 })
  await page.waitForTimeout(1200)
  await use('records')
  await page.getByRole('button', { name: '收起界面', exact: true }).click()
  const coffee = await facility('coffee')
  const terrace = await page.evaluate(() => window.__townScene.lifeStage.origin)
  await camera(terrace.x-30, terrace.y+48, 2.0)
  await page.waitForTimeout(400)
  await page.evaluate(() => {
    // Presentation-only: retain actual dialogue during its shot while the normal clean HUD stays hidden.
    window.__townFilmSpeechIds = []
    window.__townScene.events.on('postupdate', () => {
      for (const w of window.__townScene.walkers) if (window.__townFilmSpeechIds?.includes(w.id)) w.speech?.setVisible(true)
    })
    const stream = document.querySelector('canvas').captureStream(30)
    const music = window.__townScene.facilities.captureAudio()
    if (music) music.getAudioTracks().forEach(t => stream.addTrack(t))
    const chunks = [], recorder = new MediaRecorder(stream, { mimeType: music ? 'video/webm;codecs=vp9,opus' : 'video/webm;codecs=vp9', videoBitsPerSecond: 10000000 })
    recorder.ondataavailable = e => { if(e.data.size) chunks.push(e.data) }
    window.__townFilm = { stream, chunks, recorder }; recorder.start(250)
  })
  start = Date.now()
  notes.push({ at: 0, shot: 'establish', action: 'Player walks to the café while residents use the terrace.' })
  await clickWorld(coffee.actionPoint)
  await camera(terrace.x-20, terrace.y+45, 2.35, 5500)
  await at(5.8)
  await page.screenshot({ path: resolve(out,'01-street.png') })
  await camera(coffee.x+22, coffee.y-17, 4.15, 1000)
  await at(7.3)
  notes.push({ at: (Date.now()-start)/1000, shot: 'coffee', action: 'Native same-character cup motion, real facility click.' })
  await clickWorld({ x: coffee.x, y: coffee.y-12 })
  await at(9.0)
  await page.screenshot({ path: resolve(out,'02-coffee.png') })
  await at(14.5)
  const neighbour = await page.evaluate(() => {
    const s = window.__townScene
    const w = s.walkers.filter(w => w.npc?.layer === 2 && w.state === 'act' && w.frozenUntil <= s.time.now && !s.facilityNpcs.has(w) && !s.facilities.isBusy(w.id)).sort((a,b) => Math.hypot(a.sprite.x-s.selfWalker.sprite.x,a.sprite.y-s.selfWalker.sprite.y)-Math.hypot(b.sprite.x-s.selfWalker.sprite.x,b.sprite.y-s.selfWalker.sprite.y))[0]
    if (!w) return null
    return { id:w.id,x:w.sprite.x,y:w.sprite.y,approach:{x:w.sprite.x+44,y:w.sprite.y} }
  })
  if (neighbour) {
    await camera((neighbour.x+coffee.actionPoint.x)/2,neighbour.y+38,3.1,900)
    await clickWorld(neighbour.approach)
    await at(18)
    // A disclosed event cue: normal walking established positions; no actor or timeline is rewritten.
    const cue = await page.evaluate(id => {
      const s=window.__townScene,w=s.walkers.find(w=>w.id===id)
      if (!w) return { reason:'Neighbour left the area' }
      const distance=Math.hypot(w.sprite.x-s.selfWalker.sprite.x,w.sprite.y-s.selfWalker.sprite.y)
      window.__townFilmSpeechIds=[s.selfWalker.id,w.id]
      s.triggerGreeting(s.selfWalker,w,s.time.now)
      return { distance, until:w.frozenUntil, now:s.time.now }
    }, neighbour.id)
    notes.push({ at:(Date.now()-start)/1000,shot:'greeting',controlledEvent:true,...cue })
    await at(19.1);await page.screenshot({path:resolve(out,'03-greeting.png')})
    await at(20.2);await page.screenshot({path:resolve(out,'04-reply.png')})
    await at(23)
    await page.evaluate(() => { window.__townFilmSpeechIds=[] })
    await clickWorld({x:terrace.x-165,y:terrace.y+155})
  }
  await at(24)
  await camera(terrace.x-40,terrace.y+40,2.55,3000)
  await at(28)
  await page.screenshot({path:resolve(out,'05-afterglow.png')})
  await at(30)
  const encoded = await page.evaluate(async () => {
    const {recorder,stream,chunks}=window.__townFilm
    await new Promise(resolve=>{recorder.onstop=resolve;recorder.stop()});stream.getTracks().forEach(t=>t.stop())
    const bytes=new Uint8Array(await new Blob(chunks).arrayBuffer());let text=''
    for(let i=0;i<bytes.length;i+=32768)text+=String.fromCharCode(...bytes.subarray(i,i+32768))
    delete window.__townFilm;return btoa(text)
  })
  await writeFile(resolve(out,'original.webm'),Buffer.from(encoded,'base64'))
  await writeFile(resolve(out,'direction.json'),JSON.stringify({source:{baseCommit:'3a893f1',appliedPatchCommit:'4289ff0'},dataSource:'本地演示数据 · 现有小镇引擎',controlledGreeting:true,notes,errors,final:await page.evaluate(()=>window.__townScene.facilities.snapshot())},null,2))
  console.log(JSON.stringify({out,notes,errors},null,2))
} finally { await context.close();await browser.close() }

// Restrained titles and explicit provenance. No game-art replacement, speed-up or actor compositing.
const titles = `from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
import sys
p=Path(sys.argv[1]);sans='/System/Library/Fonts/STHeiti Medium.ttc';serif='/System/Library/Fonts/Supplemental/Songti.ttc'
def title(name,lines,w=1100,h=180):
 im=Image.new('RGBA',(w,h));d=ImageDraw.Draw(im)
 for text,size,y,font in lines:
  try:f=ImageFont.truetype(font,size)
  except OSError:f=ImageFont.truetype(sans,size)
  d.text((3,y+2),text,font=f,fill=(12,24,20,150),stroke_width=1)
  d.text((0,y),text,font=f,fill=(246,236,214,245))
 im.save(p/name)
title('opening.png',[('成长小镇',64,0,serif),('各自生活，偶尔相遇。',28,88,sans)])
title('coffee-title.png',[('一杯咖啡的时间',32,0,serif)],700,65)
title('closing.png',[('生活，留得下痕迹。',40,0,serif)],900,80)
title('source.png',[('本地演示数据 · 现有小镇引擎',19,0,sans)],650,35)
title('cue.png',[('问候镜头：受控事件触发',18,0,sans)],650,35)
`
const generated=spawnSync('python3',['-c',titles,out],{encoding:'utf8'})
if(generated.status!==0)throw new Error(generated.stderr)
const filter="[1:v]format=rgba,fade=t=in:st=0.4:d=0.7:alpha=1,fade=t=out:st=4.8:d=0.7:alpha=1[a];[2:v]format=rgba,fade=t=in:st=8:d=0.5:alpha=1,fade=t=out:st=12.8:d=0.5:alpha=1[b];[3:v]format=rgba,fade=t=in:st=26:d=0.8:alpha=1[c];[0:v][a]overlay=86:86[v1];[v1][b]overlay=86:906[v2];[v2][c]overlay=86:914[v3];[v3][4:v]overlay=86:1020[v4];[v4][5:v]overlay=1480:1020:enable='between(t,17.8,24)'[v]"
const args=['-y','-loglevel','error','-i',resolve(out,'original.webm')]
for(const name of ['opening.png','coffee-title.png','closing.png','source.png','cue.png'])args.push('-loop','1','-i',resolve(out,name))
args.push('-filter_complex',filter,'-map','[v]','-map','0:a?','-af','volume=14dB','-t','30','-c:v','libx264','-preset','slow','-crf','18','-pix_fmt','yuv420p','-r','30','-c:a','aac','-b:a','128k','-movflags','+faststart',resolve(out,'growth-town-demo-v2.mp4'))
const converted=spawnSync('/opt/homebrew/bin/ffmpeg',args,{encoding:'utf8'})
if(converted.status!==0)throw new Error(converted.stderr)
console.log('Film: '+resolve(out,'growth-town-demo-v2.mp4'))
