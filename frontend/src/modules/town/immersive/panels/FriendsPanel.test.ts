import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FriendsPanel from './FriendsPanel.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))

describe('FriendsPanel', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset().mockResolvedValue({})
  })

  it('mounts without a bridge and shows an empty state with no conversations', async () => {
    api.get.mockImplementation((path: string) => Promise.resolve(path.startsWith('/friends/groups') ? [] : []))
    const wrapper = mount(FriendsPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('还没有会话')
  })

  it('lists conversations and opens a chat, sending a message', async () => {
    api.get.mockImplementation((path: string) => {
      if (path.startsWith('/friends/conversations')) {
        return Promise.resolve([{ peerPublicId: 'friend-1', peerDisplayName: '阿蓝', peerLevel: 3, lastMessage: '在吗', lastMessageAt: new Date().toISOString(), lastMessageFromMe: false, unreadCount: 2 }])
      }
      if (path.startsWith('/friends/groups')) return Promise.resolve([])
      if (path.startsWith('/friends/messages')) {
        return Promise.resolve([{ publicId: 'm1', body: '在吗', fromMe: false, createdAt: new Date().toISOString(), read: true }])
      }
      return Promise.resolve([])
    })
    const wrapper = mount(FriendsPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('阿蓝')
    expect(wrapper.text()).toContain('2 条未读')

    await wrapper.get('.conversation-row').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('在吗')
    expect(api.post).toHaveBeenCalledWith('/friends/messages/read', { peerPublicId: 'friend-1' })

    await wrapper.get('textarea').setValue('我在')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/friends/messages', { peerPublicId: 'friend-1', body: '我在' })
  })

  it('shows a readable message when the conversation list fails to load', async () => {
    api.get.mockRejectedValue(new Error('network'))
    const wrapper = mount(FriendsPanel)
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('暂时无法加载')
  })
})
