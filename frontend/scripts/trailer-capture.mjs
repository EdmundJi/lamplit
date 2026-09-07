import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'

export const FF='/opt/homebrew/bin/ffmpeg'
export async function clickWorld(page, p) {
  const pixel=await page.evaluate(p=>{
    const s=window.__townScene.sys.game.scene.getScenes(true).at(-1),c=s.cameras.main,o=c.getWorldPoint(0,0),r=document.querySelector('canvas').getBoundingClientRect(),z=s.scale.gameSize
    return{x:r.left+(p.x-o.x)*c.zoom*r.width/z.width,y:r.top+(p.y-o.y)*c.zoom*r.height/z.height}
  },p)
  await page.mouse.click(pixel.x,pixel.y)
  await page.mouse.move(1,1)
}
export async function camera(page,{x,y,zoom,duration=0}) {
  await page.evaluate(({x,y,zoom,duration})=>{
    const town=window.__townScene,s=town.sys.game.scene.getScenes(true).at(-1),c=s.cameras.main
    town.releaseCameraFollow(600000);c.panEffect.reset();c.zoomEffect.reset()
    if(duration){c.pan(x,y,duration,'Sine.easeInOut');c.zoomTo(zoom,duration,'Sine.easeInOut')}else c.setZoom(zoom).centerOn(x,y)
  },{x,y,zoom,duration})
}
export async function quietWorld(page) {
  await page.evaluate(()=>{
    const s=window.__townScene
    s.runtime.scenicMode=true;s.releaseCameraFollow(600000)
    if(s.__trailerPresentation)return
    s.__trailerPresentation=true
    s.events.on('postupdate',()=>{
      for(const w of s.walkers){w.label.setVisible(false);w.emote?.setVisible(false);w.travelEmote?.setVisible(false);if(!window.__trailerSpeech?.includes(w.id))w.speech?.setVisible(false);else w.speech?.setVisible(true)}
      for(const obj of s.children.list)if(obj.getData?.('town-hud'))obj.setVisible(false)
    })
  })
}
export async function travel(page,label) {
  const restore=page.getByRole('button',{name:'显示导航与提示 · Esc',exact:true})
  if(await restore.isVisible())await restore.click()
  await page.getByRole('button',{name:label,exact:true}).click()
  if(['我的家','学院','健身房','咖啡馆'].includes(label)){
    try{await page.waitForFunction(()=>window.__townScene.runtime.activeRoomKey,null,{timeout:30000})}
    catch(error){console.log('Travel filming notice',await page.evaluate(()=>{const s=window.__townScene;return{travel:s.travel,player:window.__town.snapshot().player,active:s.runtime.activeRoomKey,busy:s.runtime.roomBusy,errors:window.__town.snapshot().errors,requests:window.__town.snapshot().failedRequests}}));throw error}
  }
  else await page.waitForFunction(()=>window.__townScene.travel?.phase==='arrived',null,{timeout:60000})
  await page.waitForTimeout(650)
}
export async function leaveRoom(page) {
  const leave=page.getByRole('button',{name:'回到街上',exact:true})
  if(await leave.isVisible()){
    await leave.click()
    try{await page.waitForFunction(()=>!window.__townScene.runtime.activeRoomKey)}
    catch(error){console.log('Room filming notice',await page.evaluate(()=>{const s=window.__townScene,r=s.runtime,a=s.sys.game.scene.getScenes(true).at(-1);return{active:r.activeRoomKey,busy:r.roomBusy,key:a?.sys.settings.key,paused:a?.sys.isPaused(),fade:a?.cameras.main.fadeEffect.isRunning,errors:window.__town.snapshot().errors}}));throw error}
    await page.waitForTimeout(400)
  }
}
export async function openPanel(page,label) {
  const button=page.getByRole('button',{name:label,exact:true})
  if(!(await button.isVisible())){
    const handle=page.getByTitle('展开功能栏',{exact:true})
    if(await handle.isVisible())await handle.click()
  }
  await page.getByRole('button',{name:label,exact:true}).click()
  await page.waitForTimeout(500)
}
export async function closePanels(page) {
  const buttons=page.locator('.world-window').getByRole('button',{name:'关闭',exact:true})
  while(await buttons.count())await buttons.last().click()
}
export async function canvasShot(page,out,name,seconds,action) {
  await mkdir(out,{recursive:true})
  await page.evaluate(()=>{
    const stream=document.querySelector('canvas').captureStream(30),chunks=[]
    const recorder=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp9',videoBitsPerSecond:10000000})
    recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data)}
    window.__trailerCapture={stream,chunks,recorder};recorder.start(200)
  })
  const begun=Date.now(),operation=action?Promise.resolve().then(action):Promise.resolve()
  await page.waitForTimeout(seconds*1000)
  const data=await page.evaluate(async()=>{
    const {recorder,stream,chunks}=window.__trailerCapture
    await new Promise(r=>{recorder.onstop=r;recorder.stop()});stream.getTracks().forEach(t=>t.stop())
    const bytes=new Uint8Array(await new Blob(chunks).arrayBuffer());let text=''
    for(let i=0;i<bytes.length;i+=32768)text+=String.fromCharCode(...bytes.subarray(i,i+32768))
    delete window.__trailerCapture;return btoa(text)
  })
  await writeFile(resolve(out,name+'.webm'),Buffer.from(data,'base64'))
  await page.screenshot({path:resolve(out,name+'.png')})
  await operation
  console.log(`Captured ${name}: ${((Date.now()-begun)/1000).toFixed(1)}s`)
}

/** Browser screencast preserves real Vue UI; timestamps become variable-frame-rate source footage. */
export async function screenRecording(page,out,name) {
  const folder=resolve(out,'frames-'+name+'-'+Date.now());await mkdir(folder,{recursive:true})
  const session=await page.context().newCDPSession(page),frames=[],writes=[]
  let first,notify
  const ready=new Promise(r=>{notify=r})
  session.on('Page.screencastFrame',event=>{
    const index=frames.length,file=resolve(folder,String(index).padStart(5,'0')+'.jpg')
    frames.push({file,time:event.metadata.timestamp});writes.push(writeFile(file,Buffer.from(event.data,'base64')))
    void session.send('Page.screencastFrameAck',{sessionId:event.sessionId})
    if(first===undefined){first=Date.now();notify()}
  })
  await session.send('Page.startScreencast',{format:'jpeg',quality:95,maxWidth:1920,maxHeight:1080,everyNthFrame:1})
  await ready
  const marks={}
  return {
    mark:label=>{marks[label]=(Date.now()-first)/1000},
    async stop(){
      const duration=(Date.now()-first)/1000
      await session.send('Page.stopScreencast');await session.detach();await Promise.all(writes)
      const start=frames[0].time
      const lines=[]
      for(let i=0;i<frames.length;i++){
        const dt=i+1<frames.length?frames[i+1].time-frames[i].time:Math.max(.04,duration-(frames[i].time-start))
        lines.push(`file '${frames[i].file.replaceAll("'","'\\''")}'`,`duration ${Math.max(.001,dt)}`)
      }
      lines.push(`file '${frames.at(-1).file.replaceAll("'","'\\''")}'`)
      const concat=resolve(folder,'frames.txt');await writeFile(concat,lines.join('\n'))
      const output=resolve(out,name+'.mp4')
      const result=spawnSync(FF,['-y','-loglevel','error','-f','concat','-safe','0','-i',concat,'-t',String(duration),'-vf','fps=30,scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2','-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p',output],{encoding:'utf8'})
      if(result.status!==0)throw new Error(result.stderr)
      await writeFile(resolve(out,name+'-marks.json'),JSON.stringify({duration,marks},null,2))
      return {duration,marks,output}
    },
  }
}
