/** Independent production-town capture. Local demo input is explicitly labeled; credential mode requires authorization. */
import { chromium } from '@playwright/test'
import { mkdir, writeFile, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
const baseURL = process.env.TOWN_BASE_URL || 'http://127.0.0.1:5182'
const demoData = process.env.TOWN_DEMO_DATA === '1'
const dataSource = demoData ? '本地演示数据 · 现有小镇引擎' : '授权本地后端数据 · 现有小镇引擎'
const statePath = process.env.TOWN_STORAGE_STATE || '/tmp/town-live-acceptance-state.json'
const out = resolve(demoData ? '../test-results/town-integrated-demo-data' : '../test-results/town-integrated-live')
if (!demoData) await readFile(statePath) // No fallback credential discovery or login.
await mkdir(out,{recursive:true})
const browser = await chromium.launch({headless:true})
const context=await browser.newContext({...(demoData ? {} : {storageState:statePath}),viewport:{width:1440,height:900},recordVideo:{dir:out,size:{width:1440,height:900}}})
const page=await context.newPage()
if (demoData) {
 const { installTownDemoData } = await import('./town-demo-data.mjs')
 await installTownDemoData(page)
}
const errors=[],network=[],frames=[],operations=[]
let captureStartedAt=0
async function facilityStatus(){
 return page.evaluate(()=>({selfId:window.__townScene.selfWalker.id, ...window.__townScene.facilities.snapshot(), items:window.__townScene.facilities.interactables()}))
}
async function clickFacility(id){
 const screen=await page.evaluate(id=>{
  const scene=window.__townScene,item=scene.facilities.interactables().find(i=>i.id===id)
  if(!item)throw new Error('Facility unavailable: '+id)
  const camera=scene.cameras.main,origin=camera.getWorldPoint(0,0),canvas=document.querySelector('canvas').getBoundingClientRect(),size=scene.scale.gameSize
  return {x:canvas.left+(item.x-origin.x)*camera.zoom*canvas.width/size.width,y:canvas.top+(item.y-12-origin.y)*camera.zoom*canvas.height/size.height}
 },id)
 await page.mouse.click(screen.x,screen.y)
}
async function useFacility(id,deadline){
 const before=await facilityStatus()
 const baseline=Math.max(-1,...before.recentCompleted.filter(c=>c.actorId===before.selfId&&c.id===id).map(c=>c.atMs))
 if(Date.now()>=deadline)return false
 await clickFacility(id)
 const entry={id,clickedAtMs:captureStartedAt?Date.now()-captureStartedAt:null,completed:false};operations.push(entry)
 try {
  await page.waitForFunction(({id,actorId,baseline})=>window.__townScene.facilities.snapshot().recentCompleted.some(c=>c.id===id&&c.actorId===actorId&&c.atMs>baseline),{id,actorId:before.selfId,baseline},{timeout:Math.max(1,deadline-Date.now()),polling:150})
  entry.completed=true;entry.completedAtMs=captureStartedAt?Date.now()-captureStartedAt:null
  return true
 } catch(error){entry.reason=Date.now()>=deadline?'recording-deadline':error.message;return false}
}
async function performPlayerSequence(deadline){
 const remaining=new Set(['books','coffee','planter'])
 while(remaining.size&&Date.now()<deadline){
  const state=await facilityStatus()
  const available=[...remaining].filter(id=>!state.active.some(a=>a.id===id&&a.actorId!==state.selfId)&&!state.reserved.some(r=>r.id===id&&r.actorId!==state.selfId))
  if(!available.length){await page.waitForTimeout(Math.min(250,Math.max(1,deadline-Date.now())));continue}
  // Prefer the nearest unoccupied item, using only real scene coordinates.
  const player=await page.evaluate(()=>window.__town.snapshot().player)
  available.sort((a,b)=>{const x=state.items.find(i=>i.id===a).actionPoint,y=state.items.find(i=>i.id===b).actionPoint;return Math.hypot(x.x-player.x,x.y-player.y)-Math.hypot(y.x-player.x,y.y-player.y)})
  const id=available[0];remaining.delete(id)
  if(!await useFacility(id,Math.min(deadline,Date.now()+12000))&&Date.now()>=deadline)break
 }
 return {completed:operations.filter(o=>o.completed&&o.clickedAtMs!==null).map(o=>o.id),unattempted:[...remaining],finishedWithinThirtySeconds:Date.now()<=deadline}
}
page.on('pageerror',e=>errors.push({kind:'runtime',message:e.message}))
page.on('response',async r=>{
 if(r.status()>=400)errors.push({kind:'http',status:r.status(),path:new URL(r.url()).pathname})
 if(['/api/v1/town','/api/v1/town/npcs'].includes(new URL(r.url()).pathname)) {
  let count=null
  try{const b=await r.json();const d=b.data??b;if(Array.isArray(d))count=d.length;else if(Array.isArray(d.npcs))count=d.npcs.length}catch{}
  network.push({path:new URL(r.url()).pathname,status:r.status(),count})
 }
})
try {
 await page.goto(baseURL+'/town/immersive')
 await page.waitForFunction(()=>window.__town?.snapshot().player?.controllable,{},{timeout:30000})
 const skip=page.getByRole('button',{name:'跳过引导',exact:true});if(await skip.isVisible())await skip.click()
 await page.getByRole('button',{name:'街角露台',exact:true}).click()
 await page.waitForFunction(()=>{
  const s=window.__townScene,p=window.__town.snapshot().player
  const c=s?.entrances?.get('terrace')
  return c&&p&&Math.hypot(p.x-c.x,p.y-c.y)<18&&p.state!=='walk'
 },{},{timeout:60000})
 await page.waitForTimeout(1500)
 if(!await useFacility('records',Date.now()+45000))throw new Error('The player could not finish using the real record player')
 if(!(await facilityStatus()).state.music && !await useFacility('records',Date.now()+12000))throw new Error('The record player did not turn on through ordinary interaction')
 // Wait for a real roster visitor; do not create actors or alter the schedule to satisfy this condition.
 try{await page.waitForFunction(()=>window.__townScene.facilityNpcs?.size>0,{},{timeout:25000})}
 catch{throw new Error('No eligible real NPC visited after the record player started; capture was not fabricated')}
 await page.waitForTimeout(2500)
 await page.screenshot({path:resolve(out,'real-town-ui.png')})
 await page.getByRole('button',{name:'收起界面',exact:true}).click()
 await page.waitForTimeout(500)
 // Capture the actual live Phaser canvas; animation, backend polling and simulation continue normally.
 await page.evaluate(()=>{
  const canvas=document.querySelector('canvas')
  const stream=canvas.captureStream(30)
  const music=window.__townScene.facilities.captureAudio()
  if(music)music.getAudioTracks().forEach(track=>stream.addTrack(track))
  const chunks=[]
  const recorder=new MediaRecorder(stream,{mimeType:music?'video/webm;codecs=vp9,opus':'video/webm;codecs=vp9',videoBitsPerSecond:7000000})
  window.__acceptanceVideo={recorder,chunks,stream}
  recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data)}
  recorder.start(250)
 })
 const start=captureStartedAt=Date.now()
 const playerSequence=performPlayerSequence(start+30000).catch(error=>({error:error.message}))
 for(let sec=0;sec<=30;sec+=5){
  if(sec)await page.waitForTimeout(Math.max(0,start+sec*1000-Date.now()))
  const snapshot=await page.evaluate(()=>({ ...window.__town.snapshot(), facilities: window.__townScene?.facilities?.snapshot() }))
  frames.push({seconds:sec,snapshot})
  await page.screenshot({path:resolve(out,`real-${String(sec).padStart(2,'0')}s.png`)})
 }
 const playerActions=await playerSequence
 const encoded=await page.evaluate(async()=>{
  const {recorder,chunks,stream}=window.__acceptanceVideo
  await new Promise(resolve=>{recorder.onstop=resolve;recorder.stop()})
  stream.getTracks().forEach(t=>t.stop())
  const buffer=await new Blob(chunks,{type:'video/webm'}).arrayBuffer()
  const bytes=new Uint8Array(buffer);let binary=''
  for(let i=0;i<bytes.length;i+=32768)binary+=String.fromCharCode(...bytes.subarray(i,i+32768))
  delete window.__acceptanceVideo
  return btoa(binary)
 })
 const raw=resolve(out,'town-30s-original.webm'),mp4=resolve(out,demoData?'town-demo-data-30s.mp4':'real-town-30s.mp4')
 await writeFile(raw,Buffer.from(encoded,'base64'))
 const watermark=[]
 if(demoData){
  const label=resolve(out,'data-source-label.png')
  const generated=spawnSync('python3',['-c',"from PIL import Image,ImageDraw,ImageFont; import sys; f=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',18); im=Image.new('RGBA',(267,38),(12,25,21,190)); ImageDraw.Draw(im).text((12,10),'本地演示数据 · 现有小镇引擎',font=f,fill=(255,250,232,255)); im.save(sys.argv[1])",label],{encoding:'utf8'})
  if(generated.status!==0)throw new Error('Could not create honest video data-source label')
  watermark.push('-i',label,'-filter_complex','[0:v][1:v]overlay=20:H-h-20[v]','-map','[v]','-map','0:a?')
 }
 const converted=spawnSync('/opt/homebrew/bin/ffmpeg',['-y','-i',raw,...watermark,'-t','30','-af','volume=14dB','-c:a','aac','-b:a','96k','-c:v','libx264','-pix_fmt','yuv420p','-r','30','-movflags','+faststart',mp4],{encoding:'utf8'})
 if(converted.status!==0)throw new Error('ffmpeg conversion failed: '+converted.stderr.slice(-1200))
 await page.keyboard.press('Escape')
 await page.getByRole('button',{name:'收起界面',exact:true}).waitFor()
 const before=await page.evaluate(()=>window.__town.snapshot().player)
 await page.keyboard.down('ArrowRight');await page.waitForTimeout(600);await page.keyboard.up('ArrowRight')
 const after=await page.evaluate(()=>window.__town.snapshot().player)
 await writeFile(resolve(out,'evidence.json'),JSON.stringify({dataSource,apiIntercepted:demoData,url:page.url(),network,errors,frames,operations,playerActions,keyboard:{before,after}},null,2))
 console.log(JSON.stringify({out,dataSource,apiIntercepted:demoData,url:page.url(),network,errors,operations,playerActions,mp4},null,2))
 const completedInsideClip=new Set(operations.filter(o=>o.completed&&o.clickedAtMs!==null&&o.completedAtMs<=30000).map(o=>o.id))
 if(errors.length||!['books','coffee','planter'].every(id=>completedInsideClip.has(id)))process.exitCode=1
} finally {await context.close();await browser.close()}
