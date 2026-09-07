/** Actual pointer approach to a demo-roster NPC in the production town. No actor or clock changes. */
import { chromium } from '@playwright/test'
import { installTownDemoData } from './town-demo-data.mjs'
import { mkdir,writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
const out=resolve('../test-results/town-social-closeup');await mkdir(out,{recursive:true})
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1280,height:900},recordVideo:{dir:out,size:{width:1280,height:900}}}),page=await context.newPage()
await installTownDemoData(page)
const errors=[];page.on('pageerror',e=>errors.push(e.message))
const snapshot=()=>page.evaluate(()=>{const s=window.__townScene;return {player:window.__town.snapshot().player,actors:s.walkers.map(w=>({id:w.id,x:w.sprite.x,y:w.sprite.y,facing:w.facing,state:w.state,frame:w.sprite.frame.name,speech:w.speech?.list?.filter(x=>typeof x.text==='string').map(x=>x.text)})),selected:document.querySelector('.resident-moment')?.textContent}})
try{
 await page.goto((process.env.TOWN_BASE_URL||'http://127.0.0.1:5182')+'/town/immersive');await page.waitForFunction(()=>window.__town?.snapshot().player?.controllable)
 const skip=page.getByRole('button',{name:'跳过引导',exact:true});if(await skip.isVisible())await skip.click()
 await page.getByRole('button',{name:'街角露台',exact:true}).click()
 await page.waitForFunction(()=>{const s=window.__townScene,p=s.selfWalker.sprite,e=s.entrances.get('terrace');return Math.hypot(p.x-e.x,p.y-e.y)<18&&s.selfWalker.state!=='walk'},{},{timeout:60000})
 await page.waitForTimeout(1300)
 await page.waitForFunction(()=>window.__townScene.walkers.some(w=>w.npc?.layer===2&&!window.__townScene.facilityNpcs.has(w)),{},{timeout:40000})
 const targetId=await page.evaluate(()=>{const s=window.__townScene;return s.walkers.filter(w=>w.npc?.layer===2&&!s.facilityNpcs.has(w)).sort((a,b)=>Math.hypot(a.sprite.x-s.selfWalker.sprite.x,a.sprite.y-s.selfWalker.sprite.y)-Math.hypot(b.sprite.x-s.selfWalker.sprite.x,b.sprite.y-s.selfWalker.sprite.y))[0]?.id})
 if(!targetId)throw new Error('No currently available demo NPC to approach')
 await page.evaluate(id=>{const s=window.__townScene,w=s.walkers.find(w=>w.id===id);s.releaseCameraFollow(60000);s.cameras.main.panEffect.reset();s.cameras.main.zoomEffect.reset();s.cameras.main.setZoom(2.8).centerOn(w.sprite.x,w.sprite.y+40)},targetId)
 const p=await page.evaluate(id=>{const s=window.__townScene,w=s.walkers.find(w=>w.id===id),c=s.cameras.main,o=c.getWorldPoint(0,0),b=document.querySelector('canvas').getBoundingClientRect(),z=s.scale.gameSize;return{x:b.left+(w.sprite.x-o.x)*c.zoom*b.width/z.width,y:b.top+(w.sprite.y-28-o.y)*c.zoom*b.height/z.height}},targetId)
 await page.mouse.click(p.x,p.y)
 await page.getByRole('region',{name:'路边闲聊'}).waitFor({timeout:30000})
 const approach=await snapshot();await page.screenshot({path:resolve(out,'npc-approach-44px.png')})
 await page.waitForTimeout(1000)
 await page.getByRole('button',{name:'结束闲聊',exact:true}).click()
 const afterClose=await snapshot();await page.screenshot({path:resolve(out,'after-goodbye.png')});await page.waitForTimeout(420)
 // Clearly disclosed controlled event: normal UI established positions; only the production greeting event is invoked.
 await page.evaluate(id=>{const s=window.__townScene,n=s.walkers.find(w=>w.id===id);s.releaseCameraFollow(60000);s.cameras.main.panEffect.reset();s.cameras.main.zoomEffect.reset();s.cameras.main.setZoom(3.2).centerOn((n.sprite.x+s.selfWalker.sprite.x)/2,n.sprite.y-26);const stream=document.querySelector('canvas').captureStream(30),chunks=[],recorder=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp9',videoBitsPerSecond:6000000});window.__socialCapture={stream,chunks,recorder};recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data)};recorder.start(250);s.triggerGreeting(s.selfWalker,n,s.time.now)},targetId)
 const eventFrames=[],eventStarted=Date.now()
 for(const ms of [0,450,850,1250,1800,2300,2850,3400,4100,5100,6500]){await page.waitForTimeout(Math.max(0,eventStarted+ms-Date.now()));eventFrames.push({ms,...await snapshot()});await page.screenshot({path:resolve(out,`greeting-${ms}.png`)})}
 const encoded=await page.evaluate(async()=>{const {recorder,stream,chunks}=window.__socialCapture;await new Promise(resolve=>{recorder.onstop=resolve;recorder.stop()});stream.getTracks().forEach(t=>t.stop());const b=new Uint8Array(await new Blob(chunks).arrayBuffer());let s='';for(let i=0;i<b.length;i+=32768)s+=String.fromCharCode(...b.subarray(i,i+32768));delete window.__socialCapture;return btoa(s)})
 await writeFile(resolve(out,'controlled-greeting-original.webm'),Buffer.from(encoded,'base64'))
 const label=resolve(out,'controlled-label.png');spawnSync('python3',['-c',"from PIL import Image,ImageDraw,ImageFont; import sys; im=Image.new('RGBA',(315,38),(12,25,21,200)); ImageDraw.Draw(im).text((12,10),'受控问候事件 · 本地演示数据',font=ImageFont.truetype('/System/Library/Fonts/STHeiti Medium.ttc',18),fill=(255,250,232,255)); im.save(sys.argv[1])",label])
 const converted=spawnSync('/opt/homebrew/bin/ffmpeg',['-y','-loglevel','error','-i',resolve(out,'controlled-greeting-original.webm'),'-i',label,'-filter_complex','[0:v][1:v]overlay=20:H-h-20[v]','-map','[v]','-t','6.5','-c:v','libx264','-pix_fmt','yuv420p','-r','30','-movflags','+faststart',resolve(out,'controlled-greeting.mp4')],{encoding:'utf8'});if(converted.status!==0)throw new Error(converted.stderr)

 // Re-establish ordinary social distance before the cancellation case; the previous NPC may have resumed its route.
 const again=await page.evaluate(id=>{const s=window.__townScene,w=s.walkers.find(w=>w.id===id),c=s.cameras.main,o=c.getWorldPoint(0,0),b=document.querySelector('canvas').getBoundingClientRect(),z=s.scale.gameSize;return{x:b.left+(w.sprite.x-o.x)*c.zoom*b.width/z.width,y:b.top+(w.sprite.y-28-o.y)*c.zoom*b.height/z.height}},targetId)
 await page.mouse.click(again.x,again.y);await page.getByRole('region',{name:'路边闲聊'}).waitFor({timeout:20000});await page.getByRole('button',{name:'结束闲聊',exact:true}).click();await page.waitForTimeout(420)
 await page.evaluate(id=>{const s=window.__townScene,n=s.walkers.find(w=>w.id===id);s.triggerGreeting(s.selfWalker,n,s.time.now)},targetId)

 await page.waitForTimeout(1200)
 const beforeInterrupt=await snapshot();await page.screenshot({path:resolve(out,'before-interrupt.png')})
 await page.keyboard.down('ArrowDown');await page.waitForTimeout(600);await page.keyboard.up('ArrowDown')
 await page.waitForTimeout(3000)
 const afterMove=await snapshot();await page.screenshot({path:resolve(out,'after-moving-away.png')})
 const npc=approach.actors.find(w=>w.id===targetId),distance=Math.hypot(npc.x-approach.player.x,npc.y-approach.player.y)
 const checks={socialDistance:distance>=36&&distance<=60,notSamePosition:distance>32,dialogueOpened:!!approach.selected,dialogueClosed:!afterClose.selected,controlledFirstTurn:eventFrames.some(f=>f.actors.some(a=>a.speech?.includes('嗨，路上慢慢走。'))),controlledReply:eventFrames.some(f=>f.actors.some(a=>a.speech?.includes('嗯，回头见。'))),cancelledPendingReply:beforeInterrupt.actors.some(a=>a.speech?.length)&&!afterMove.actors.filter(a=>a.id===targetId||a.id==='local-demo-player').some(a=>a.speech?.length),movedAway:Math.hypot(afterMove.player.x-afterClose.player.x,afterMove.player.y-afterClose.player.y)>12,noErrors:errors.length===0}
 await writeFile(resolve(out,'evidence.json'),JSON.stringify({dataSource:'本地演示数据 · 现有小镇引擎',checks,distance,targetId,errors,controlledGreeting:true,eventFrames,approach,afterClose,beforeInterrupt,afterMove},null,2))
 console.log(JSON.stringify({out,checks,distance,errors},null,2));if(Object.values(checks).some(x=>!x))process.exitCode=1
}finally{await context.close();await browser.close()}
