/** Focused product-flow pickups; genuine buttons and editable stateful demo API. */
import { chromium } from '@playwright/test'
import { readFile,writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createTrailerState,installTrailerData,setTrailerTime } from './trailer-data.mjs'
import { camera,canvasShot,clickWorld,closePanels,leaveRoom,openPanel,quietWorld,screenRecording,travel } from './trailer-capture.mjs'
const out=resolve('../artifacts/product-trailer'),base=process.env.TOWN_BASE_URL||'http://127.0.0.1:5186'
const source=JSON.parse(await readFile(resolve(out,'edit-source.json'),'utf8')),clips=source.clips,state=createTrailerState()
const browser=await chromium.launch({headless:true}),context=await browser.newContext({viewport:{width:1920,height:1080}}),page=await context.newPage()
await installTrailerData(page,state)
page.on('pageerror',e=>console.log('Recording notice: '+e.message))
async function box(selector){const r=await page.locator(selector).last().boundingBox();return r?{x:r.x,y:r.y,width:r.width,height:r.height}:null}
try{
 await page.goto(base+'/town/immersive');await page.waitForFunction(()=>window.__town?.snapshot().player?.controllable)
 await quietWorld(page);await travel(page,'学院');await openPanel(page,'AI 助手')
 const ui=await screenRecording(page,out,'workflow')
 await page.getByLabel('给 AI 助手发消息').fill('我想重新开始阅读，但总是拖着。今天只有十分钟，能从哪一步开始？')
 ui.mark('question');await page.waitForTimeout(800);await page.getByRole('button',{name:'发送',exact:true}).click()
 await page.getByRole('button',{name:'帮我拆出第一步',exact:true}).waitFor()
 ui.mark('reply');clips.aiBox=await box('.ai-message.assistant');await page.waitForTimeout(3300)
 await page.getByRole('button',{name:'帮我拆出第一步',exact:true}).click();await page.getByLabel('今天的第一步').waitFor()
 ui.mark('step');clips.stepBox=await box('.next-step');await page.waitForTimeout(3800)
 await page.getByRole('button',{name:'确认这一步',exact:true}).click();await page.getByRole('dialog',{name:'目标编辑',exact:true}).waitFor()
 await page.getByRole('button',{name:'保存目标',exact:true}).scrollIntoViewIfNeeded()
 ui.mark('goal');clips.goalBox=await box('.ai-starter-tasks');await page.waitForTimeout(600)
 await page.getByRole('button',{name:'保存目标',exact:true}).click();await page.getByRole('button',{name:'去今天开始',exact:true}).waitFor();await page.waitForTimeout(700)
 await page.getByRole('button',{name:'去今天开始',exact:true}).click();await page.getByRole('button',{name:'开始',exact:true}).waitFor()
 ui.mark('task');clips.taskBox=await box('.today-panel');await page.waitForTimeout(1200)
 await page.getByRole('button',{name:'开始',exact:true}).click();ui.mark('started');await page.waitForTimeout(3000)
 clips.workflow=await ui.stop()
 const rhythm=await screenRecording(page,out,'rhythm')
 await page.getByRole('button',{name:'调整节奏',exact:true}).click();await page.getByRole('button',{name:'偏低',exact:true}).click()
 await page.locator('#panel-daily-rhythm .check-result button').click()
 rhythm.mark('low');clips.rhythmBox=await box('#panel-daily-rhythm');await page.waitForTimeout(5500);clips.rhythm=await rhythm.stop()
 await closePanels(page);await camera(page,{x:260,y:205,zoom:3.8})
 await canvasShot(page,out,'study',6,()=>clickWorld(page,{x:320,y:160}));clips.study={kind:'canvas',seconds:6}
 await openPanel(page,'今天')
 const completion=await screenRecording(page,out,'completion')
 await page.getByRole('button',{name:'完成',exact:true}).click();await page.locator('.completed-steps').waitFor()
 completion.mark('done');clips.doneBox=await box('.today-panel');await page.waitForTimeout(6200);clips.completion=await completion.stop()
 await closePanels(page);await leaveRoom(page)
 setTrailerTime(state,'19:10');await page.reload();await page.waitForFunction(()=>window.__town?.snapshot().player?.controllable)
 await travel(page,'我的家');await openPanel(page,'家的纪念墙');await page.locator('.keepsake-note').waitFor()
 const wall=await screenRecording(page,out,'memento');clips.mementoBox=await box('.keepsake-note');await page.waitForTimeout(6500);clips.memento=await wall.stop()
 await closePanels(page)
 await page.getByRole('button',{name:'拿牵引绳',exact:true}).click()
 await page.waitForFunction(()=>!window.__townScene.runtime.activeRoomKey,null,{timeout:20000})
 await travel(page,'公园');await quietWorld(page)
 const park=await page.evaluate(()=>window.__townScene.entrances.get('park'))
 await camera(page,{x:park.x,y:park.y+30,zoom:3.0})
 await canvasShot(page,out,'park-companion',6,()=>clickWorld(page,{x:park.x-70,y:park.y+32}))
 clips['park-companion']={kind:'canvas',seconds:6}
 await writeFile(resolve(out,'edit-source.json'),JSON.stringify({...source,clips,state,workflowHandoff:'AI and Goals are closed on deliberate next-step transitions; unsent AI drafts retained.'},null,2))
 console.log('Product-flow pickups captured.')
}catch(error){await page.screenshot({path:resolve(out,'workflow-notice.png')}).catch(()=>{});console.log(await page.locator('body').innerText());throw error}
finally{await context.close();await browser.close()}
