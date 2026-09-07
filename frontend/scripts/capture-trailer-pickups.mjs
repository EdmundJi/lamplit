/** Filming pickups after actual scene corrections. No test runner or assertions. */
import { chromium } from '@playwright/test'
import { readFile,writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createTrailerState,installTrailerData,setTrailerTime } from './trailer-data.mjs'
import { camera,canvasShot,clickWorld,closePanels,leaveRoom,quietWorld,travel } from './trailer-capture.mjs'
const out=resolve('../artifacts/product-trailer'),base=process.env.TOWN_BASE_URL||'http://127.0.0.1:5182'
const browser=await chromium.launch({headless:true})
const context=await browser.newContext({viewport:{width:1920,height:1080}})
const page=await context.newPage(),state=createTrailerState()
page.on('pageerror',error=>console.log('Scene filming notice: '+error.message))
page.on('console',message=>{if(message.type()==='error')console.log('Console filming notice: '+message.text())})
await installTrailerData(page,state)
try{
 await page.goto(base+'/town/immersive');await page.waitForFunction(()=>window.__town?.snapshot().player?.controllable)
 await quietWorld(page)
 await travel(page,'学院')
 await camera(page,{x:260,y:230,zoom:3.2})
 await canvasShot(page,out,'academy',5,()=>clickWorld(page,{x:272,y:330}))
 await camera(page,{x:260,y:205,zoom:3.8})
 await canvasShot(page,out,'study',5,()=>clickWorld(page,{x:320,y:160}))
 await leaveRoom(page);await travel(page,'健身房')
 await camera(page,{x:310,y:260,zoom:2.9})
 await canvasShot(page,out,'gym',5,()=>clickWorld(page,{x:300,y:320}))
 await leaveRoom(page)
 const original=JSON.parse(await readFile(resolve(out,'edit-source.json'),'utf8'))
 Object.assign(state,original.state);setTrailerTime(state,'19:10')
 await page.reload();await page.waitForFunction(()=>window.__town?.snapshot().player?.controllable)
 await quietWorld(page)
 await canvasShot(page,out,'homecoming',11,async()=>{await travel(page,'我的家');await camera(page,{x:300,y:295,zoom:3.2,duration:1100})})
 await camera(page,{x:210,y:215,zoom:4.3})
 await canvasShot(page,out,'home-rest',7,()=>clickWorld(page,{x:120,y:150}))
 await canvasShot(page,out,'home-ending',6,()=>camera(page,{x:205,y:215,zoom:4.6,duration:5000}))
 original.pickups={academy:'real front desks/rug and readable resident layers',gym:'visible legal front aisle',home:'pet welcome and sofa close-up',at:new Date().toISOString()}
 await writeFile(resolve(out,'edit-source.json'),JSON.stringify(original,null,2))
}finally{await context.close();await browser.close()}
