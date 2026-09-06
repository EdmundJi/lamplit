import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AiPanel from './AiPanel.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const postSse = vi.hoisted(() => vi.fn())
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../shared/api/sse', async () => {
  const actual = await vi.importActual<typeof import('../../../../shared/api/sse')>('../../../../shared/api/sse')
  return { ...actual, postSse }
})
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))

describe('AiPanel', () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue([])
    api.post.mockReset()
    postSse.mockReset()
  })

  it('mounts without a bridge and shows the empty prompt', async () => {
    const wrapper = mount(AiPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('把还没理清的想法说给它听')
  })

  it('streams a reply after sending a message', async () => {
    api.post.mockResolvedValueOnce({ publicId: 'session-1' })
    postSse.mockImplementation(async (_path, _body, onEvent) => {
      onEvent({ name: 'delta', data: { text: '先做一件小事就好' } })
      onEvent({ name: 'done', data: {} })
    })
    const wrapper = mount(AiPanel)
    await flushPromises()

    await wrapper.get('textarea').setValue('今天有点累')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/ai/sessions', { scene: 'STUDY' })
    expect(postSse).toHaveBeenCalledWith('/ai/sessions/session-1/messages:stream', { message: '今天有点累' }, expect.any(Function), expect.any(AbortSignal))
    expect(wrapper.text()).toContain('先做一件小事就好')
  })

  it('keeps sending in the same session across multiple turns', async () => {
    api.post.mockResolvedValueOnce({ publicId: 'session-1' })
    postSse.mockImplementation(async (_path, _body, onEvent) => {
      onEvent({ name: 'delta', data: { text: '好的' } })
    })
    const wrapper = mount(AiPanel)
    await flushPromises()

    await wrapper.get('textarea').setValue('第一句')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('textarea').setValue('第二句')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    // 只在没有会话时才建一次；第二轮沿用同一个 session,不是每条消息都开新对话。
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(postSse).toHaveBeenNthCalledWith(2, '/ai/sessions/session-1/messages:stream', { message: '第二句' }, expect.any(Function), expect.any(AbortSignal))
  })

  it('can interrupt an in-flight reply without losing the session', async () => {
    api.post.mockResolvedValueOnce({ publicId: 'session-1' })
    postSse.mockImplementation((_path, _body, _onEvent, signal: AbortSignal) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('已中断', 'AbortError')))
    }))
    const wrapper = mount(AiPanel)
    await flushPromises()

    await wrapper.get('textarea').setValue('慢慢说')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const stopButton = wrapper.get('[aria-label="停止生成"]')
    await stopButton.trigger('click')
    await flushPromises()

    expect(wrapper.find('[aria-label="停止生成"]').exists()).toBe(false)
    expect(wrapper.find('.error').exists()).toBe(false)
  })

  it('shows a readable message when the stream is interrupted by a quota error', async () => {
    api.post.mockResolvedValueOnce({ publicId: 'session-1' })
    const { SseRequestError } = await import('../../../../shared/api/sse')
    postSse.mockRejectedValueOnce(new SseRequestError(429, 'RATE_LIMIT', 'too many'))
    const wrapper = mount(AiPanel)
    await flushPromises()

    await wrapper.get('textarea').setValue('再聊聊')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.error').text()).toContain('陪聊得有点累')
  })
})
