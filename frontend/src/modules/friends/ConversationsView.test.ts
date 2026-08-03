import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ConversationsView from './ConversationsView.vue'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const conversations = [
  { peerPublicId: 'user-a', peerDisplayName: '林晓', peerLevel: 2, lastMessage: '八点见 🎉', lastMessageAt: '2026-08-03T09:05:00Z', lastMessageFromMe: false, unreadCount: 2 },
  { peerPublicId: 'user-b', peerDisplayName: '陈默', peerLevel: 1, lastMessage: '收到，明天继续', lastMessageAt: '2026-08-02T11:00:00Z', lastMessageFromMe: true, unreadCount: 0 },
]
const groups = [
  { publicId: 'group-1', name: '周末学习小组', lastMessage: '大家好 👋', lastMessageAt: '2026-08-03T10:00:00Z', unreadCount: 1, memberCount: 3 },
]

describe('ConversationsView', () => {
  beforeEach(() => {
    api.get.mockReset().mockImplementation((path: string) => {
      if (path === '/friends/conversations') return Promise.resolve(conversations)
      if (path === '/friends/groups') return Promise.resolve(groups)
      if (path === '/friends') return Promise.resolve({ friends: [], incoming: [], outgoing: [] })
      return Promise.resolve([])
    })
    api.post.mockReset().mockResolvedValue({})
  })

  it('lists single and group conversations with unread badges and links', async () => {
    const wrapper = mount(ConversationsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/friends/conversations')
    expect(api.get).toHaveBeenCalledWith('/friends/groups')
    expect(wrapper.get('.conversation-count').text()).toContain('3 个会话')
    const cards = wrapper.findAll('.conversation-card')
    expect(cards).toHaveLength(3)
    expect(cards[0].text()).toContain('周末学习小组')
    expect(cards[0].text()).toContain('3 人')
    expect(cards[0].get('.unread-badge').text()).toBe('1')
    expect(cards[0].attributes('to')).toBe('/friends/groups/group-1')
    expect(cards[1].text()).toContain('林晓')
    expect(cards[1].get('.unread-badge').text()).toBe('2')
    expect(cards[1].attributes('to')).toBe('/friends/user-a/chat')
  })

  it('creates a group by picking multiple friends', async () => {
    api.get.mockImplementation((path: string) => {
      if (path === '/friends/conversations') return Promise.resolve([])
      if (path === '/friends/groups') return Promise.resolve([])
      if (path === '/friends') return Promise.resolve({
        friends: [
          { publicId: 'user-a', displayName: '林晓', overallLevel: 2, memberSince: '2026-07-01', status: 'ACCEPTED', direction: 'OUTGOING', createdAt: '2026-08-01' },
          { publicId: 'user-b', displayName: '陈默', overallLevel: 1, memberSince: '2026-07-02', status: 'ACCEPTED', direction: 'OUTGOING', createdAt: '2026-08-01' },
        ],
        incoming: [],
        outgoing: [],
      })
      return Promise.resolve([])
    })
    api.post.mockResolvedValue({ publicId: 'group-9', name: '学习小组' })
    const wrapper = mount(ConversationsView, {
      global: {
        plugins: [{ install: () => {} }],
        stubs: { RouterLink: { template: '<a><slot /></a>' } },
      },
    })
    await flushPromises()
    await wrapper.get('.create-group-button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.member-option').exists()).toBe(true)
    await wrapper.get('#group-name').setValue('学习小组')
    const options = wrapper.findAll('.member-option')
    await options[0].get('input').setValue(true)
    await options[1].get('input').setValue(true)
    expect(wrapper.get('.actions .primary').text()).toContain('2 / 9')
    await wrapper.get('.group-create').trigger('submit')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/friends/groups', {
      name: '学习小组',
      memberPublicIds: ['user-a', 'user-b'],
    })
  })
})
