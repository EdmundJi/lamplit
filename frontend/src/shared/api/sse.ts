export type SseEvent = { name: 'meta'|'delta'|'safety'|'done'|'error'; data: unknown }
const eventNames = new Set<SseEvent['name']>(['meta', 'delta', 'safety', 'done', 'error'])

export function parseSseBlock(block: string): SseEvent | null {
  let name = ''
  const lines: string[] = []
  for (const line of block.replaceAll('\r\n', '\n').split('\n')) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    if (line.startsWith('data:')) lines.push(line.slice(5).trimStart())
  }
  if (!eventNames.has(name as SseEvent['name'])) return null
  const raw = lines.join('\n')
  let data: unknown = raw
  try { data = JSON.parse(raw) } catch { /* Plain text is a valid SSE payload. */ }
  return { name: name as SseEvent['name'], data }
}

async function stream(path:string, body:unknown, onEvent:(event:SseEvent)=>void, signal:AbortSignal|undefined, retried:boolean){
  const csrf=document.cookie.split(';').map(v=>v.trim()).find(v=>v.startsWith('csrf_token='))?.split('=')[1]
  const headers={'Content-Type':'application/json',Accept:'text/event-stream',...(csrf?{'X-CSRF-Token':decodeURIComponent(csrf)}:{})}
  const response=await fetch(`/api/v1${path}`,{method:'POST',credentials:'include',headers,body:JSON.stringify(body),signal})
  if(response.status===401&&!retried){
    const refreshed=await fetch('/api/v1/auth/refresh',{method:'POST',credentials:'include',headers:{Accept:'application/json'},signal})
    if(refreshed.ok)return stream(path,body,onEvent,signal,true)
  }
  if(!response.ok||!response.body)throw new Error('AI_TEMPORARILY_UNAVAILABLE')
  const reader=response.body.getReader();const decoder=new TextDecoder();let buffer=''
  while(true){
    const {value,done}=await reader.read()
    buffer+=decoder.decode(value,{stream:!done}).replaceAll('\r\n','\n')
    let split
    while((split=buffer.indexOf('\n\n'))>=0){
      const event=parseSseBlock(buffer.slice(0,split));buffer=buffer.slice(split+2)
      if(event)onEvent(event)
    }
    if(done){const event=parseSseBlock(buffer);if(event)onEvent(event);break}
  }
}

export async function postSse(path:string, body:unknown, onEvent:(event:SseEvent)=>void, signal?:AbortSignal){
  return stream(path,body,onEvent,signal,false)
}
