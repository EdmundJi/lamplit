import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ChatView from './ChatView.vue'
import { useAuthStore } from '../auth/auth.store'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const summary = { publicId: 'user-friend', displayName: '苏珊', overallLevel: 3 }
const history = [
  { publicId: 'msg-1', body: '今晚一起复习一章 📚', fromMe: true, createdAt: '2026-08-03T09:00:00Z', read: true },
  { publicId: 'msg-2', body: '好呀 😊', fromMe: false, createdAt: '2026-08-03T09:01:00Z', read: false },
]

let mountOptions: { global: { plugins: any[]; stubs: any } }

describe('ChatView', () => {
  beforeEach(() => {
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    auth.user = { publicId: 'me', email: 'me@example.test', displayName: '我', timezone: 'Asia/Shanghai', role: 'USER' }
    mountOptions = { global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } } }
    vi.useFakeTimers()
    api.get.mockReset()
    api.post.mockReset()
    api.get.mockImplementation((path: string) => {
      if (path.startsWith('/friends/user-friend/summary')) return Promise.resolve(summary)
      if (path.startsWith('/friends/messages')) return Promise.resolve(history)
      return Promise.resolve([])
    })
    api.post.mockResolvedValue({})
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('loads the peer summary and splits messages into left (theirs) and right (mine) columns', async () => {
    const wrapper = mount(ChatView, { props: { publicId: 'user-friend' }, ...mountOptions })
    await flushPromises()
    expect(wrapper.get('.chat-identity h1').text()).toBe('苏珊')
    expect(wrapper.text()).toContain('LV.3 成长者')

    const rows = wrapper.findAll('.msg-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].classes()).toContain('mine')
    expect(rows[0].get('.msg-name').text()).toBe('我')
    expect(rows[0].get('.msg-bubble').text()).toContain('今晚一起复习一章')
    expect(rows[0].get('.msg-bubble img').attributes('alt')).toBe('📚')
    expect(rows[0].get('.msg-time').text()).toBeTruthy()
    expect(rows[1].classes()).not.toContain('mine')
    expect(rows[1].get('.msg-name').text()).toBe('苏珊')
    expect(rows[1].get('.msg-bubble img').attributes('alt')).toBe('😊')
    expect(rows[1].get('.msg-avatar').text()).toBe('苏')
    expect(api.post).toHaveBeenCalledWith('/friends/messages/read', { peerPublicId: 'user-friend' })
  })

  it('sends a message with emoji from the picker and moves it to the mine column', async () => {
    api.get.mockImplementation((path: string) => {
      if (path.startsWith('/friends/user-friend/summary')) return Promise.resolve(summary)
      if (path.startsWith('/friends/messages')) return Promise.resolve([
        ...history,
        { publicId: 'msg-3', body: '收到 🎉', fromMe: true, createdAt: '2026-08-03T09:02:00Z', read: true },
      ])
      return Promise.resolve([])
    })
    const wrapper = mount(ChatView, { props: { publicId: 'user-friend' }, ...mountOptions })
    await flushPromises()

    await wrapper.get('.emoji-toggle').trigger('click')
    expect(wrapper.find('.emoji-picker').exists()).toBe(true)
    const emojiItems = wrapper.findAll('.emoji-item')
    expect(emojiItems.length).toBeGreaterThan(50)

    await wrapper.get('textarea[aria-label="消息内容"]').setValue('收到')
    await wrapper.get('.emoji-item').trigger('click')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toContain('😀')

    await wrapper.get('button[aria-label="发送消息"]').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/friends/messages', { peerPublicId: 'user-friend', body: '收到😀' })
    const rows = wrapper.findAll('.msg-row')
    expect(rows).toHaveLength(3)
    expect(rows.filter(row => row.classes().includes('mine'))).toHaveLength(2)
  })

  it('shows a friendly error when the peer is not a friend', async () => {
    api.get.mockRejectedValue({ status: 404, code: 'FRIENDSHIP_NOT_FOUND', message: 'not found' })
    const wrapper = mount(ChatView, { props: { publicId: 'user-stranger' }, ...mountOptions })
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('只能和好友聊天')
  })
})
