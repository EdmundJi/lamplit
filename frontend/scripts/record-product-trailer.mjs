/** Product footage capture with actual UI actions; no test runner, credentials, or live AI calls. */
import { chromium } from '@playwright/test'
import { mkdir,writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createTrailerState,installTrailerData,setTrailerTime } from './trailer-data.mjs'
import { camera,canvasShot,clickWorld,closePanels,leaveRoom,openPanel,quietWorld,screenRecording,travel } from './trailer-capture.mjs'

const base=process.env.TOWN_BASE_URL||'http://127.0.0.1:5182'
const out=resolve(process.env.TOWN_TRAILER_DIR||'../artifacts/product-trailer')
await mkdir(out,{recursive:true})
const state=createTrailerState(),errors=[],clips={}
const browser=await chromium.launch({headless:true})
const context=await browser.newContext({viewport:{width:1920,height:1080},deviceScaleFactor:1})
const page=await context.newPage();await installTrailerData(page,state)
page.on('pageerror',error=>{errors.push(error.message);console.log('Page notice: '+error.message)})
async function ready(){await page.waitForFunction(()=>window.__town?.snapshot().player?.controllable,null,{timeout:40000})}
async function box(selector){const r=await page.locator(selector).last().boundingBox();return r?{x:r.x,y:r.y,width:r.width,height:r.height}:null}
async function useFacility(id){
  const item=await page.evaluate(id=>window.__townScene.facilities.interactables().find(x=>x.id===id),id)
  await clickWorld(page,{x:item.x,y:item.y-12})
}
async function shot(name,seconds,action){await canvasShot(page,out,name,seconds,action);clips[name]={kind:'canvas',seconds}}
try{
  await page.goto(base+'/town/immersive');await ready()
  await page.getByRole('button',{name:'环境声关',exact:true}).click()
  await travel(page,'街角露台');await quietWorld(page)
  const terrace=await page.evaluate(()=>window.__townScene.lifeStage.origin)
  const coffee=await page.evaluate(()=>window.__townScene.facilities.interactables().find(x=>x.id==='coffee'))
  await clickWorld(page,coffee.actionPoint)
  await page.waitForFunction(()=>window.__townScene.selfWalker.state!=='walk')
  await camera(page,{x:coffee.x+12,y:coffee.y-18,zoom:4.3})
  await shot('coffee',7,()=>useFacility('coffee'))
  await shot('reveal',8,()=>camera(page,{x:terrace.x-40,y:terrace.y+35,zoom:1.75,duration:6200}))

  await travel(page,'学院')
  await shot('academy',5,async()=>{await clickWorld(page,{x:280,y:330})})
  await leaveRoom(page)
  await travel(page,'健身房')
  await shot('gym',5,()=>clickWorld(page,{x:240,y:290}))
  await leaveRoom(page)
  await travel(page,'公园');await quietWorld(page)
  const park=await page.evaluate(()=>window.__townScene.entrances.get('park'))
  await camera(page,{x:park.x,y:park.y-25,zoom:2.9})
  await shot('park',5,()=>camera(page,{x:park.x-55,y:park.y-15,zoom:2.5,duration:4200}))

  await travel(page,'街角露台');await quietWorld(page)
  const friend=await page.evaluate(()=>{const s=window.__townScene,w=s.walkers.find(w=>w.npc?.layer===2&&w.state==='act'&&!s.facilityNpcs.has(w));return w?{id:w.id,x:w.sprite.x,y:w.sprite.y}:null})
  if(friend){
    await camera(page,{x:friend.x+20,y:friend.y+28,zoom:3.3})
    await clickWorld(page,{x:friend.x+44,y:friend.y})
    await page.waitForFunction(()=>window.__townScene.selfWalker.state!=='walk')
    await shot('social',5,()=>page.evaluate(id=>{const s=window.__townScene,w=s.walkers.find(w=>w.id===id);window.__trailerSpeech=[s.selfWalker.id,id];s.triggerGreeting(s.selfWalker,w,s.time.now)},friend.id))
    await page.evaluate(()=>{window.__trailerSpeech=[]})
  }

  // The same thought becomes a real goal and task through the production panels.
  await travel(page,'学院')
  await openPanel(page,'AI 助手')
  const ui=await screenRecording(page,out,'workflow')
  await page.getByLabel('给 AI 助手发消息').fill('我想重新开始阅读，但总是拖着。今天只有十分钟，能从哪一步开始？')
  ui.mark('question');await page.waitForTimeout(900)
  await page.getByRole('button',{name:'发送',exact:true}).click()
  await page.getByRole('button',{name:'帮我拆出第一步',exact:true}).waitFor({timeout:15000})
  ui.mark('reply');clips.aiBox=await box('.ai-message.assistant');await page.waitForTimeout(3300)
  await page.getByRole('button',{name:'帮我拆出第一步',exact:true}).click()
  await page.getByLabel('今天的第一步').waitFor()
  ui.mark('step');clips.stepBox=await box('.next-step');await page.waitForTimeout(3800)
  await page.getByRole('button',{name:'确认这一步',exact:true}).click()
  await page.getByRole('dialog',{name:'目标编辑',exact:true}).waitFor()
  await page.getByRole('button',{name:'保存目标',exact:true}).scrollIntoViewIfNeeded()
  ui.mark('goal');clips.goalBox=await box('.ai-starter-tasks');await page.waitForTimeout(1500)
  await page.getByRole('button',{name:'保存目标',exact:true}).click()
  await page.getByRole('button',{name:'去今天开始',exact:true}).waitFor()
  await page.waitForTimeout(1000)
  await page.getByRole('button',{name:'去今天开始',exact:true}).click()
  await page.getByRole('button',{name:'开始',exact:true}).waitFor()
  ui.mark('task');clips.taskBox=await box('.today-panel');await page.waitForTimeout(1800)
  await page.getByRole('button',{name:'开始',exact:true}).click()
  ui.mark('started');await page.waitForTimeout(3000)
  clips.workflow=await ui.stop()

  const rhythm=await screenRecording(page,out,'rhythm')
  await page.getByRole('button',{name:'调整节奏',exact:true}).click()
  await page.getByRole('button',{name:'偏低',exact:true}).click()
  clips.rhythmBox=await box('#panel-daily-rhythm')
  rhythm.mark('low');await page.waitForTimeout(5500);clips.rhythm=await rhythm.stop()
  await closePanels(page)
  await shot('study',5,()=>clickWorld(page,{x:220,y:280}))

  // Editing represents returning after the real-world action; no timer is fast-forwarded.
  await openPanel(page,'今天')
  const completion=await screenRecording(page,out,'completion')
  await page.getByRole('button',{name:'完成',exact:true}).click()
  await page.getByRole('region',{name:'今天留下的进展'}).waitFor().catch(()=>page.locator('.completed-steps').waitFor())
  completion.mark('done');clips.doneBox=await box('.today-panel')
  await page.waitForTimeout(6200);clips.completion=await completion.stop()
  await closePanels(page);await leaveRoom(page)

  setTrailerTime(state,'19:10')
  await page.reload();await ready()
  await quietWorld(page)
  await shot('homecoming',11,()=>travel(page,'我的家'))
  const room=await page.evaluate(()=>{const s=window.__townScene.sys.game.scene.getScenes(true).at(-1);return {x:s.player.x,y:s.player.y}})
  await shot('home-rest',7,async()=>{await clickWorld(page,{x:120,y:150});await page.waitForTimeout(1400)})
  await openPanel(page,'家的纪念墙')
  await page.locator('.keepsake-note').waitFor()
  const wall=await screenRecording(page,out,'memento')
  clips.mementoBox=await box('.keepsake-note');await page.waitForTimeout(6500);clips.memento=await wall.stop()
  await closePanels(page)
  await shot('home-ending',6,()=>clickWorld(page,{x:120,y:172}))

  await writeFile(resolve(out,'edit-source.json'),JSON.stringify({clips,dataSource:'产品运行画面 · 演示数据与 AI 示例',state,errors},null,2))
  console.log(JSON.stringify({out,clips,errors},null,2))
}catch(error){
  await page.screenshot({path:resolve(out,'recording-notice.png')}).catch(()=>{})
  await writeFile(resolve(out,'recording-notice.json'),JSON.stringify({message:error.message,errors,state,body:await page.locator('body').innerText()},null,2))
  throw error
}finally{await context.close();await browser.close()}
