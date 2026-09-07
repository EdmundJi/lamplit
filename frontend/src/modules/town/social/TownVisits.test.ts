import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TownVisits from './TownVisits.vue'
const api=vi.hoisted(()=>({get:vi.fn(),put:vi.fn(),post:vi.fn(),delete:vi.fn()}))
vi.mock('../../../shared/api/client',()=>({api}))
const mine={publicId:'me',displayName:'我',enabled:false,style:'original',mementos:[]}
beforeEach(()=>{
 vi.resetAllMocks()
 api.get.mockImplementation((path:string)=>Promise.resolve(path==='/town/visits/mine'?mine:path==='/town/visits'?[{publicId:'friend',displayName:'朋友'}]:path==='/town/visits/friend'?{...mine,publicId:'friend',displayName:'朋友',enabled:true}:[]))
})
describe('TownVisits',()=>{
 it('publishes only after the owner selects and saves; starts private',async()=>{
  const w=mount(TownVisits,{global:{plugins:[createPinia()]}});await flushPromises()
  await w.findAll('nav button')[1]!.trigger('click')
  expect((w.get('input[type=checkbox]').element as HTMLInputElement).checked).toBe(false)
  expect(api.put).not.toHaveBeenCalled()
  await w.get('input[type=checkbox]').setValue(true)
  api.put.mockResolvedValue({...mine,enabled:true})
  await w.findAll('.actions button')[0]!.trigger('click');await flushPromises()
  expect(api.put).toHaveBeenCalledWith('/town/visits/mine',{enabled:true,style:'original',mementoCodes:[]})
  expect(w.text()).toContain('只有已接受的好友')
  w.unmount()
 })
 it('sends plain text with stable retry key and clearly describes asynchronous sharing',async()=>{
  const w=mount(TownVisits,{global:{plugins:[createPinia()]}});await flushPromises()
  await w.findAll('.directory button')[0]!.trigger('click');await flushPromises()
  await w.get('textarea').setValue('<img src=x onerror=alert(1)>')
  api.post.mockRejectedValueOnce({message:'连接中断'}).mockResolvedValue({publicId:'card'})
  await w.get('form').trigger('submit');await flushPromises()
  const first=api.post.mock.calls[0]![1]
  await w.get('form').trigger('submit');await flushPromises()
  expect(api.post.mock.calls[1]![1]).toEqual(first)
  expect(w.find('img').exists()).toBe(false)
  expect(w.text()).toContain('不代表实时房间或在线状态')
  expect(w.text()).toContain('明信片已送到')
  w.unmount()
 })
})
