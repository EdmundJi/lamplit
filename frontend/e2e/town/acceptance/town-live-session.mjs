/** Run only after explicit authorization to authenticate and save a temporary test session. */
import { request } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
const email = process.env.TOWN_EMAIL
const password = process.env.TOWN_PASSWORD
if (!email || !password) throw new Error('Provide explicitly authorized local test credentials through TOWN_EMAIL and TOWN_PASSWORD')
const api = await request.newContext({baseURL: process.env.TOWN_API_URL || 'http://127.0.0.1:8082'})
try {
 const r = await api.post('/api/v1/auth/login', {data:{email,password}})
 console.log('local-test-login-status', r.status())
 if(r.status()!==200) process.exitCode=1
 else {
  await writeFile('/tmp/town-live-acceptance-state.json', JSON.stringify(await api.storageState()), {mode:0o600})
  for(const url of ['/api/v1/me','/api/v1/town','/api/v1/town/npcs']) {
   const res=await api.get(url)
   const body=await res.json()
   const data=body.data??body
   console.log(url,res.status(),Array.isArray(data)?{count:data.length}:{keys:Object.keys(data)})
  }
 }
} finally {await api.dispose()}
