/** Independent visual check: production town + labeled local demo input, genuine pointer/keyboard input. */
import { chromium } from '@playwright/test'
import { installTownDemoData } from './town-demo-data.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
const out=resolve('../test-results/town-coffee-closeup')
await mkdir(out,{recursive:true})
const browser=await chromium.launch({headless:true})
const context=await browser.newContext({viewport:{width:1280,height:900},recordVideo:{dir:out,size:{width:1280,height:900}}})
const page=await context.newPage()
await installTownDemoData(page)
const errors=[],samples=[]
page.on('pageerror',e=>errors.push(e.message))
async function snapshot(){return page.evaluate(()=>{const s=window.__townScene;return {player:window.__town.snapshot().player,id:s.selfWalker.id,frame:s.selfWalker.sprite.frame.name,anim:s.selfWalker.sprite.anims.currentAnim?.key,facilities:s.facilities.snapshot()}})}
async function clickCoffee(){
 const p=await page.evaluate(()=>{const s=window.__townScene,i=s.facilities.interactables().find(i=>i.id==='coffee'),c=s.cameras.main,o=c.getWorldPoint(0,0),b=document.querySelector('canvas').getBoundingClientRect(),z=s.scale.gameSize;return {x:b.left+(i.x-o.x)*c.zoom*b.width/z.width,y:b.top+(i.y-12-o.y)*c.zoom*b.height/z.height}})
 await page.mouse.click(p.x,p.y)
}
try{
 await page.goto((process.env.TOWN_BASE_URL||'http://127.0.0.1:5182')+'/town/immersive')
 await page.waitForFunction(()=>window.__town?.snapshot().player?.controllable)
 const skip=page.getByRole('button',{name:'跳过引导',exact:true});if(await skip.isVisible())await skip.click()
 await page.getByRole('button',{name:'街角露台',exact:true}).click()
 await page.waitForFunction(()=>{const s=window.__townScene,p=window.__town.snapshot().player,e=s.entrances.get('terrace');return e&&Math.hypot(p.x-e.x,p.y-e.y)<18&&p.state!=='walk'},{},{timeout:60000})
 await page.waitForTimeout(1200)
 await page.getByRole('button',{name:'收起界面',exact:true}).click()
 // Camera-only inspection. Actor positions, frames, clocks and action state remain owned by production code.
 await page.evaluate(()=>{const s=window.__townScene,i=s.facilities.interactables().find(i=>i.id==='coffee');s.releaseCameraFollow(60000);s.cameras.main.panEffect.reset();s.cameras.main.zoomEffect.reset();s.cameras.main.setZoom(4).centerOn(i.x+12,i.y-23)})
 await page.waitForTimeout(300)
 await page.waitForFunction(()=>{const s=window.__townScene,z=s.facilities.snapshot();return !z.reserved.some(r=>r.id==='coffee'&&r.actorId!==s.selfWalker.id)},{},{timeout:30000})
 await page.evaluate(()=>{const stream=document.querySelector('canvas').captureStream(30),chunks=[],recorder=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp9',videoBitsPerSecond:7000000});window.__closeupCapture={stream,chunks,recorder};recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data)};recorder.start(250)})
 await clickCoffee()
 const started=Date.now(),seen=new Set()
 while(Date.now()-started<10000){
  const z=await snapshot(),active=z.facilities.active.find(a=>a.id==='coffee'&&a.actorId===z.id)
  samples.push({wallMs:Date.now()-started,...z})
  if(active?.motion&&!seen.has(active.motion.phase)){
   seen.add(active.motion.phase)
   await page.screenshot({path:resolve(out,`${active.motion.phase}.png`)})
  }
  await page.waitForTimeout(70)
 }
 await page.screenshot({path:resolve(out,'finished.png')})
 const encoded=await page.evaluate(async()=>{const {recorder,stream,chunks}=window.__closeupCapture;await new Promise(resolve=>{recorder.onstop=resolve;recorder.stop()});stream.getTracks().forEach(t=>t.stop());const b=new Uint8Array(await new Blob(chunks).arrayBuffer());let s='';for(let i=0;i<b.length;i+=32768)s+=String.fromCharCode(...b.subarray(i,i+32768));delete window.__closeupCapture;return btoa(s)})
 await writeFile(resolve(out,'coffee-original.webm'),Buffer.from(encoded,'base64'))
 // Cancellation: start again, interrupt a real sip using the normal movement keys.
 await clickCoffee()
 await page.waitForFunction(()=>{const s=window.__townScene;return s.facilities.snapshot().active.some(a=>a.actorId===s.selfWalker.id&&a.motion?.phase==='sip')},{},{timeout:15000})
 const beforeCancel=await snapshot()
 await page.keyboard.down('ArrowRight');await page.waitForTimeout(450);await page.keyboard.up('ArrowRight')
 const afterCancel=await snapshot()
 await page.screenshot({path:resolve(out,'cancelled-by-walking.png')})
 const checks={nativePose:samples.some(s=>s.facilities.active.some(a=>a.actorId===s.id&&a.motion?.nativePose)),allCorePhases:['reach','lift','sip','breathe','replace'].every(p=>seen.has(p)),completion:samples.some(s=>s.facilities.recentCompleted.some(c=>c.id==='coffee'&&c.actorId===s.id)),cancelRemovedAction:!afterCancel.facilities.active.some(a=>a.actorId===afterCancel.id),cancelMovedPlayer:Math.hypot(afterCancel.player.x-beforeCancel.player.x,afterCancel.player.y-beforeCancel.player.y)>12,noErrors:errors.length===0}
 await writeFile(resolve(out,'evidence.json'),JSON.stringify({dataSource:'本地演示数据 · 现有小镇引擎',url:page.url(),checks,errors,beforeCancel,afterCancel,samples},null,2))
 const label=resolve(out,'source-label.png')
 spawnSync('python3',['-c',"from PIL import Image,ImageDraw,ImageFont; import sys; im=Image.new('RGBA',(267,38),(12,25,21,190)); ImageDraw.Draw(im).text((12,10),'本地演示数据 · 现有小镇引擎',font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',18),fill=(255,250,232,255)); im.save(sys.argv[1])",label])
 const converted=spawnSync('/opt/homebrew/bin/ffmpeg',['-y','-loglevel','error','-i',resolve(out,'coffee-original.webm'),'-i',label,'-filter_complex','[0:v][1:v]overlay=20:H-h-20[v]','-map','[v]','-t','10','-c:v','libx264','-pix_fmt','yuv420p','-r','30','-movflags','+faststart',resolve(out,'coffee-closeup.mp4')],{encoding:'utf8'})
 if(converted.status!==0)throw new Error(converted.stderr)
 console.log(JSON.stringify({out,checks,phases:[...seen],errors},null,2))
 if(Object.values(checks).some(v=>!v))process.exitCode=1
}finally{await context.close();await browser.close()}
