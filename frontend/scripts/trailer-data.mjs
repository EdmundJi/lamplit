/** Explicit, stateful demo API inputs for recording the production product. No credentials or live AI calls. */
export function createTrailerState() {
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
  return { day, epoch: Date.parse(day+'T17:45:00+08:00'), clockStarted: Date.now(), goals: [], tasks: [], schedules: [], messages: [], presence: null, daily: null, completed: false, requests: [] }
}
export function setTrailerTime(state, time) { state.epoch=Date.parse(`${state.day}T${time}:00+08:00`);state.clockStarted=Date.now() }
export async function installTrailerData(page, state) {
  const id='local-demo-player'
  const now=()=>new Date(state.epoch+Date.now()-state.clockStarted).toISOString()
  const me={publicId:id,email:'trailer@local.invalid',displayName:'小满',timezone:'Asia/Shanghai',role:'USER'}
  const pet={publicId:'pet-doubao',speciesCode:'DOG',speciesName:'狗',name:'豆包',breed:'拉布拉多',level:2,affection:42,nextLevelAffection:100,selected:true,furColor:'#bd9762'}
  const dimensions=['KNOWLEDGE','HEALTH','CAREER','RELATIONSHIP','WELLBEING'].map((code,i)=>({publicId:`dim-${i}`,code,name:['知识','健康','职场','关系','心境'][i]}))
  const names=['林知','安禾','陆夏','沈牧','许宁','柯云','王芳','李明','赵晨','周雨','陈晓','苏叶','江川','吴桐','方晴','唐果']
  const roster=names.map((displayName,i)=>{
    const place=i<2?'cafe':i<4?'academy':i===4?'gym':i===5?'home':'park'
    return {code:`DEMO_RESIDENT_${i}`,displayName,layer:i<6?2:3,sprite:`c${String(i+1).padStart(2,'0')}`,dimension:dimensions[i%5].code,interests:{},affinityToPlayer:.5,mood:{valence:.5,energy:.6},schedule:[{startHour:0,endHour:24,place,activity:i%2?'idle':'reading'}],dayPlan:{date:state.day,errands:[{startMinute:0,endMinute:1440,place,activity:i%2?'idle':'reading',priority:1,origin:'RHYTHM'}],legs:[]},talkingPoints:[{factId:`demo-own-${i}`,text:i===0?'我今天想在这里读完一章。':i===1?'这盆花，明天应该也会很精神。':'我只看见你从学院出来，其他的还不知道。',hops:0,salience:.6}]}
  })
  const residents=[{...roster[0],code:'GUIDE',displayName:'小助',layer:1,sprite:'npc_scout'},{...roster[0],code:'POSTMAN',displayName:'邮递员',layer:1,sprite:'npc_postman'},...roster]
  const achievement=()=>({code:'FIRST_ACTION',name:'第一步行动',body:'迈出第一步，系统才开始有你的真实节奏。',triggerText:'完成第一个有效行动',category:'ACTION',iconKey:'Footprints',tone:'green',earned:state.completed,earnedAt:state.completed?state.completedAt:null})
  await page.addInitScript(id=>{
    localStorage.setItem(`better-self:welcome:${id}`,'dismissed')
    localStorage.setItem(`better-self:town-onboarding:${id}:completed`,'true')
  },id)
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),path=new URL(request.url()).pathname.replace('/api/v1',''),method=request.method()
    const body=()=>request.postDataJSON()??{}
    state.requests.push({path,method,at:now()})
    let data=[]
    if(path==='/me')data=me
    else if(path==='/me/profile')data={...me,overallLevel:1,totalExperience:state.completed?10:0,effectiveActions:state.completed?1:0,longestStreak:state.completed?1:0,soloGrowth:true,equippedTitle:null,wallet:{coinBalance:state.completed?2:0,lifetimeCoins:state.completed?2:0},selectedPet:pet,petCount:1}
    else if(path==='/town')data={localDate:state.day,serverTime:now(),unread:1,soloGrowth:true,residents:[{publicId:id,displayName:me.displayName,self:true,level:1,totalExperience:state.completed?10:0,dominantDimension:'KNOWLEDGE',longestStreak:state.completed?1:0,title:null,timezone:me.timezone,schedules:state.schedules.map(s=>({...s,title:s.taskTitle,roleCode:'STUDENT',roleName:'学生'})),presence:state.presence}]}
    else if(path==='/town/presence'){state.presence={...body(),updatedAt:now()};data=state.presence}
    else if(path==='/town/npcs')data={npcs:residents,initiativeBudget:{limit:3,used:3}}
    else if(path.endsWith('/talking-points'))data={points:roster.find(n=>n.code===path.split('/')[3])?.talkingPoints??[]}
    else if(path==='/dimensions')data=dimensions
    else if(path==='/goals'){
      if(method==='POST'){const f=body();data={...f,publicId:'goal-reading',status:'ACTIVE'};state.goals.push(data)}else data=state.goals
    }else if(path==='/tasks'){
      if(method==='POST'){
        const f=body();data={...f,publicId:'task-reading',weeklyPlanPublicId:'week-demo',active:true,roleCode:'STUDENT'};state.tasks.push(data)
        state.schedules.push({publicId:'schedule-reading',taskTitle:f.title,plannedStartAt:state.day+'T17:50:00+08:00',plannedEndAt:null,status:'PLANNED',roleCode:'STUDENT',roleName:'学生',estimatedMinutes:f.estimatedMinutes,difficulty:f.difficulty})
      }else data=state.tasks
    }else if(path==='/task-schedules')data=state.schedules
    else if(path==='/task-schedules/schedule-reading/events'){
      const event=body().eventType,status={STARTED:'IN_PROGRESS',COMPLETED:'DONE',PARTIAL:'PARTIAL',DEFERRED:'DEFERRED',SKIPPED:'SKIPPED'}[event]
      if(state.schedules[0])state.schedules[0].status=status
      if(event==='COMPLETED'){state.completed=true;state.completedAt=now()}
      data={scheduleStatus:status,eventPublicId:'record-'+event,coinDelta:event==='COMPLETED'?2:0,roleExperienceDelta:event==='COMPLETED'?10:0,roleProgress:{roleName:'学生',level:1}}
    }else if(path==='/daily-status'){
      if(method==='POST'){const f=body();state.daily={...f,publicId:'daily-demo',localDate:state.day,advice:f.energy==='LOW'?'SHRINK':'LIGHT',updatedAt:now()}}
      data=state.daily
    }else if(path==='/ai/sessions')data=method==='POST'?{publicId:'session-reading'}:state.messages.length?[{publicId:'session-reading',scene:'STUDY',updatedAt:now(),messageCount:state.messages.length,lastMessage:'今天，先读十分钟。'}]:[]
    else if(path==='/ai/sessions/session-reading/messages')data=state.messages
    else if(path.endsWith('/messages:stream')){
      const text='不用等到准备好。\n\n今天只做一件小事：**选一本想读的书，读 10 分钟。**\n\n读完写下一句记住的话，就可以停下。'
      state.messages.push({role:'USER',content:body().message},{role:'ASSISTANT',content:text})
      await new Promise(r=>setTimeout(r,500))
      await route.fulfill({contentType:'text/event-stream',body:`event: delta\ndata: ${JSON.stringify({text})}\n\nevent: done\ndata: {}\n\n`});return
    }else if(path==='/ai/goal-template')data={sourceSessionPublicId:'session-reading',title:'把阅读重新带回日常',description:'从每天十分钟开始，留下一句读书笔记。',dimensionCode:'KNOWLEDGE',durationDays:14,weeklyFocus:'先建立一个轻松开始的节奏',starterTasks:[{title:'读 10 分钟，记下一句话',estimatedMinutes:10,difficulty:1}],model:'DEMO',providerRequestId:'labeled-trailer-example'}
    else if(path==='/achievements')data=[achievement()]
    else if(path==='/partners/profile')data={selectedPet:pet,pets:[pet],wallet:{coinBalance:2},catalog:[]}
    else if(path.endsWith('/interact'))data={pet,affectionDelta:0,rewarded:false,interactionDate:state.day}
    else if(path==='/insights/attributes')data={overallLevel:1,totalExperience:state.completed?10:0,attributes:dimensions.map((d,i)=>({...d,experience:state.completed&&i===0?10:0,level:1}))}
    else if(path==='/friends/unread-summary')data={totalUnread:0}
    else if(path==='/town/letters/unread')data={unreadCount:1}
    else if(path==='/town/letters')data={letters:[{publicId:'letter-demo',kind:'NOTE',senderName:'安禾',subject:'路过时，来坐坐',body:'花已经浇好了。你有空的时候，来街角坐一会儿吧。',createdAt:now(),deliverAt:now(),readAt:null}],unreadCount:1}
    else if(path==='/town/reflection/latest'||path==='/town/confidant')data=null
    else if(path==='/task-presets')data={items:[],refreshesRemaining:3}
    await route.fulfill({json:{data,requestId:'trailer-demo-data',timestamp:now()}})
  })
  return {playerId:id,pet,dimensions}
}
