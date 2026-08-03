import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FriendsView from './FriendsView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), delete: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const emptyList = { friends: [], incoming: [], outgoing: [] }
const pendingList = {
  friends: [],
  incoming: [{ publicId: 'user-incoming', displayName: '林晓', overallLevel: 2, memberSince: '2026-07-01', status: 'PENDING', direction: 'INCOMING', createdAt: '2026-08-03' }],
  outgoing: [{ publicId: 'user-outgoing', displayName: '陈默', overallLevel: 1, memberSince: '2026-07-02', status: 'PENDING', direction: 'OUTGOING', createdAt: '2026-08-03' }],
}
const friendList = {
  friends: [{ publicId: 'user-friend', displayName: '苏珊', overallLevel: 3, memberSince: '2026-06-15', status: 'ACCEPTED', direction: 'OUTGOING', createdAt: '2026-07-20' }],
  incoming: [],
  outgoing: [],
}

describe('FriendsView', () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue(emptyList)
    api.post.mockReset().mockResolvedValue({})
    api.delete.mockReset().mockResolvedValue(undefined)
  })

  it('shows an empty state when there are no friends yet', async () => {
    const wrapper = mount(FriendsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('还没有好友')
    expect(api.get).toHaveBeenCalledWith('/friends')
  })

  it('sends a friend request by email and reports success', async () => {
    api.post.mockResolvedValue({ publicId: 'user-peer', displayName: '周舟', overallLevel: 1, memberSince: '2026-07-01', status: 'PENDING', direction: 'OUTGOING', createdAt: '2026-08-03' })
    const wrapper = mount(FriendsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    await wrapper.get('input[type="email"]').setValue('zhouzhou@example.test')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/friends/requests', { email: 'zhouzhou@example.test' })
    expect(wrapper.get('.feedback-banner').text()).toContain('申请已发送给 周舟')
  })

  it('shows a specific message when the target user does not exist', async () => {
    api.post.mockRejectedValue({ status: 404, code: 'FRIEND_USER_NOT_FOUND', message: 'not found' })
    const wrapper = mount(FriendsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    await wrapper.get('input[type="email"]').setValue('missing@example.test')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('没有找到该邮箱对应的用户')
  })

  it('renders incoming and outgoing requests with accept, reject and cancel actions', async () => {
    api.get.mockResolvedValue(pendingList)
    const wrapper = mount(FriendsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('收到的申请')
    expect(wrapper.text()).toContain('林晓')
    expect(wrapper.text()).toContain('发出的申请')
    expect(wrapper.text()).toContain('陈默')

    await wrapper.get('.incoming-card button.primary').trigger('click')
    expect(api.post).toHaveBeenCalledWith('/friends/user-incoming/accept')
    await flushPromises()
    await wrapper.get('.incoming-card button.secondary').trigger('click')
    expect(api.post).toHaveBeenCalledWith('/friends/user-incoming/reject')
    await flushPromises()
    await wrapper.get('.outgoing-card button.secondary').trigger('click')
    expect(api.delete).toHaveBeenCalledWith('/friends/user-outgoing')
  })

  it('lists accepted friends as links to their detail page', async () => {
    api.get.mockResolvedValue(friendList)
    const wrapper = mount(FriendsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('苏珊')
    const link = wrapper.get('.friend-link')
    expect(link.attributes('to')).toBe('/friends/user-friend')
    expect(wrapper.get('.friend-count').text()).toContain('1 位好友')
  })
})
