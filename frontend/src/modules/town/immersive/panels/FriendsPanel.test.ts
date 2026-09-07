import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount } from '@vue/test-utils'
import FriendsPanel from './FriendsPanel.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))
enableAutoUnmount(afterEach)

async function openChat(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('[aria-label="邮递员服务"]').findAll('button')[1].trigger('click')
  await flushPromises()
}

describe('FriendsPanel', () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue({ letters: [], unreadCount: 0 })
    api.post.mockReset().mockResolvedValue({})
  })

  it('opens the mailbox in town by default without a full-page link or chat requests', async () => {
    const wrapper = mount(FriendsPanel)
    await flushPromises()
    expect(wrapper.find('[aria-label="小镇信箱"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('完整页面')
    expect(api.get).toHaveBeenCalledTimes(1)
    expect(api.get).toHaveBeenCalledWith('/town/letters')
  })

  it('offers the postman story separately from mail and fetches it only when opened', async () => {
    const wrapper = mount(FriendsPanel)
    await flushPromises()
    expect(wrapper.get('.story-toggle').text()).toContain('邮递员的小故事')
    expect(api.get).not.toHaveBeenCalledWith('/town/stories/POSTMAN')
    api.get.mockResolvedValueOnce({ npcCode: 'POSTMAN', title: '邮包上的蓝色补丁', heading: '一个小念头',
      body: '邮递员想修好邮包。', stage: 0, revision: 0, actions: [], completedAt: null })
    await wrapper.get('.story-toggle').trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/town/stories/POSTMAN')
    expect(wrapper.text()).toContain('邮递员想修好邮包')
  })

  it('mounts without a bridge and shows an empty state with no conversations', async () => {
    api.get.mockImplementation((path: string) => Promise.resolve(path === '/town/letters' ? { letters: [], unreadCount: 0 } : []))
    const wrapper = mount(FriendsPanel)
    await openChat(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('还没有会话')
  })

  it('lists conversations and opens a chat, sending a message', async () => {
    api.get.mockImplementation((path: string) => {
      if (path === '/town/letters') return Promise.resolve({ letters: [], unreadCount: 0 })
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
    await openChat(wrapper)
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
    await openChat(wrapper)
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('暂时无法加载')
  })
})
