import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AiView from './AiView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const postSse = vi.hoisted(() => vi.fn())
vi.mock('../../shared/api/client', () => ({ api }))
vi.mock('../../shared/api/sse', () => ({ postSse }))

describe('AI safety UI', () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue([])
    api.post.mockReset().mockResolvedValue({ publicId: 'session-1' })
    postSse.mockReset()
  })

  it('replaces the normal composer with the reviewed crisis response', async () => {
    postSse.mockImplementation(async (_path, _body, emit) => emit({ name: 'safety', data: { message: '<strong>请立即联系紧急服务</strong>' } }))
    const wrapper = mount(AiView, { global: { stubs: { RouterLink: true } } })
    await wrapper.get('textarea').setValue('我不想活了'); await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('[role=alert]').text()).toContain('<strong>请立即联系紧急服务</strong>')
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('.crisis strong').exists()).toBe(false)
  })

  it('shows the fallback when session creation fails', async () => {
    api.post.mockRejectedValueOnce(new Error('expired session'))
    const wrapper = mount(AiView, { global: { stubs: { RouterLink: true } } })
    await wrapper.get('textarea').setValue('帮我拆解目标')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('AI 暂时不可用')
    expect(wrapper.get('button[aria-label="发送"]').attributes('disabled')).toBeUndefined()
  })

  it('loads a historical session and continues the same conversation', async () => {
    api.get.mockImplementation((path: string) => {
      if (path === '/ai/sessions') return Promise.resolve([
        { publicId: 'old-session', scene: 'STUDY', status: 'ACTIVE', createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z', messageCount: 2, lastRole: 'ASSISTANT', lastMessage: '继续昨天的计划', lastMessageAt: '2026-08-01T00:02:00Z' },
      ])
      if (path === '/ai/sessions/old-session/messages') return Promise.resolve([
        { publicId: 'm1', role: 'USER', content: '昨天聊了什么？', riskLevel: 'L0', status: 'COMPLETED', createdAt: '2026-08-01T00:01:00Z' },
        { publicId: 'm2', role: 'ASSISTANT', content: '# 昨天的计划\n- 继续复习', riskLevel: 'L0', status: 'COMPLETED', createdAt: '2026-08-01T00:02:00Z' },
      ])
      return Promise.resolve([])
    })
    postSse.mockImplementation(async (_path, _body, emit) => emit({ name: 'delta', data: { text: '新的回复' } }))
    const wrapper = mount(AiView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    await wrapper.get('.history-item').trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/ai/sessions/old-session/messages')
    expect(wrapper.get('h2.doc-heading').text()).toBe('昨天的计划')

    await wrapper.get('textarea').setValue('继续')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(postSse).toHaveBeenCalledWith('/ai/sessions/old-session/messages:stream', { message: '继续' }, expect.any(Function), expect.any(AbortSignal))
  })
})
