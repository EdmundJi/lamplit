import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SseEvent } from '../../../../shared/api/sse'
import AiPanel from './AiPanel.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const postSse = vi.hoisted(() => vi.fn())
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../shared/api/sse', async () => {
  const actual = await vi.importActual<typeof import('../../../../shared/api/sse')>('../../../../shared/api/sse')
  return { ...actual, postSse }
})
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))
enableAutoUnmount(afterEach)

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const sessions = ['a', 'b'].map(publicId => ({ publicId, lastMessage: publicId, scene: 'STUDY', messageCount: 1, updatedAt: '' }))
async function submit(wrapper: ReturnType<typeof mount>, text: string) {
  await wrapper.get('textarea').setValue(text)
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

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
    expect(wrapper.text()).not.toContain('完整页面')
  })

  it('offers the guide story in immersive without reading it until the player opens it', async () => {
    const wrapper = mount(AiPanel)
    await flushPromises()
    expect(wrapper.get('.story-toggle').text()).toContain('小助的小故事')
    expect(api.get).not.toHaveBeenCalledWith('/town/stories/GUIDE')
    api.get.mockResolvedValueOnce({ npcCode: 'GUIDE', title: '窗边的一小格书架', heading: '一个小念头',
      body: '小助想整理书架。', stage: 0, revision: 0, actions: [], completedAt: null })
    await wrapper.get('.story-toggle').trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/town/stories/GUIDE')
    expect(wrapper.text()).toContain('小助想整理书架')
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
    expect(wrapper.get('textarea').element.value).toBe('再聊聊')
  })

  it('keeps the committed session and messages when another history load fails, and blocks sending while loading', async () => {
    const pending = deferred<unknown[]>()
    api.get.mockImplementation((path: string) => path === '/ai/sessions' ? Promise.resolve(sessions)
      : path.includes('/a/') ? Promise.resolve([{ role: 'USER', content: 'A 的历史' }]) : pending.promise)
    const wrapper = mount(AiPanel)
    await flushPromises()
    const buttons = wrapper.findAll('.history-pick button')
    await buttons[0].trigger('click')
    await flushPromises()
    await wrapper.get('textarea').setValue('留在 A')
    await buttons[1].trigger('click')
    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('正在打开对话')
    await wrapper.get('form').trigger('submit')
    expect(postSse).not.toHaveBeenCalled()
    pending.reject(new Error('network'))
    await flushPromises()
    expect(wrapper.text()).toContain('A 的历史')
    expect(buttons[0].attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('textarea').element.value).toBe('留在 A')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(postSse).toHaveBeenCalledWith('/ai/sessions/a/messages:stream', { message: '留在 A' }, expect.any(Function), expect.any(AbortSignal))
    expect(api.post).not.toHaveBeenCalled()
  })

  it('commits only the latest history selection when responses arrive out of order', async () => {
    const a = deferred<unknown[]>()
    const b = deferred<unknown[]>()
    api.get.mockImplementation((path: string) => path === '/ai/sessions' ? Promise.resolve(sessions) : path.includes('/a/') ? a.promise : b.promise)
    const wrapper = mount(AiPanel)
    await flushPromises()
    await wrapper.findAll('.history-pick button')[0].trigger('click')
    await wrapper.findAll('.history-pick button')[1].trigger('click')
    b.resolve([{ role: 'ASSISTANT', content: 'B 的历史' }])
    await flushPromises()
    a.resolve([{ role: 'ASSISTANT', content: 'A 的迟到历史' }])
    await flushPromises()
    expect(wrapper.text()).toContain('B 的历史')
    expect(wrapper.text()).not.toContain('A 的迟到历史')
    await submit(wrapper, '发给 B')
    expect(postSse.mock.calls[0][0]).toBe('/ai/sessions/b/messages:stream')
  })

  it('isolates a pending session creation from reset and a newer send', async () => {
    const oldCreation = deferred<{ publicId: string }>()
    const newStream = deferred<void>()
    api.post.mockReturnValueOnce(oldCreation.promise).mockResolvedValueOnce({ publicId: 'new' })
    postSse.mockReturnValue(newStream.promise)
    const wrapper = mount(AiPanel)
    await flushPromises()
    await submit(wrapper, '旧消息')
    await wrapper.get('[aria-label="新会话"]').trigger('click')
    await submit(wrapper, '新消息')
    oldCreation.resolve({ publicId: 'old' })
    await flushPromises()
    expect(postSse).toHaveBeenCalledTimes(1)
    expect(postSse.mock.calls[0][0]).toBe('/ai/sessions/new/messages:stream')
    expect(wrapper.text()).not.toContain('旧消息')
    expect(wrapper.find('[aria-label="停止生成"]').exists()).toBe(true)
    newStream.resolve()
    await flushPromises()
  })

  it('ignores old SSE callbacks and finally after reset while the new stream remains busy', async () => {
    const old = deferred<void>()
    const fresh = deferred<void>()
    const callbacks: Array<(event: SseEvent) => void> = []
    const signals: AbortSignal[] = []
    api.post.mockResolvedValueOnce({ publicId: 'old' }).mockResolvedValueOnce({ publicId: 'new' })
    postSse.mockImplementation((_path, _body, callback, signal) => {
      callbacks.push(callback); signals.push(signal)
      return callbacks.length === 1 ? old.promise : fresh.promise
    })
    const wrapper = mount(AiPanel)
    await flushPromises()
    await submit(wrapper, '旧请求')
    await wrapper.get('[aria-label="新会话"]').trigger('click')
    await submit(wrapper, '新请求')
    expect(signals[0].aborted).toBe(true)
    callbacks[0]({ name: 'delta', data: { text: '旧流污染' } })
    callbacks[0]({ name: 'error', data: { message: '旧错误' } })
    old.reject(new Error('old transport failure'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧流污染')
    expect(wrapper.text()).not.toContain('旧错误')
    expect(wrapper.find('[aria-label="停止生成"]').exists()).toBe(true)
    callbacks[1]({ name: 'delta', data: { text: '新流回复' } })
    fresh.resolve()
    await flushPromises()
    expect(wrapper.text()).toContain('新流回复')
  })

  it('stops immediately, rejects late events, and continues in the same session', async () => {
    const old = deferred<void>()
    let callback!: (event: SseEvent) => void
    api.post.mockResolvedValue({ publicId: 'same' })
    postSse.mockImplementationOnce((_path, _body, onEvent) => { callback = onEvent; return old.promise })
    const wrapper = mount(AiPanel)
    await flushPromises()
    await submit(wrapper, '第一句')
    callback({ name: 'delta', data: { text: '保留的半句' } })
    await wrapper.get('[aria-label="停止生成"]').trigger('click')
    callback({ name: 'delta', data: { text: '不应追加' } })
    await submit(wrapper, '继续')
    old.resolve()
    await flushPromises()
    expect(wrapper.text()).toContain('保留的半句')
    expect(wrapper.text()).not.toContain('不应追加')
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(postSse.mock.calls[1][0]).toBe('/ai/sessions/same/messages:stream')
  })

  it('switches away from a streaming conversation without late events contaminating the loaded history', async () => {
    api.get.mockImplementation((path: string) => Promise.resolve(path === '/ai/sessions' ? sessions : [{ role: 'ASSISTANT', content: 'B 会话' }]))
    api.post.mockResolvedValue({ publicId: 'old' })
    const stream = deferred<void>()
    let callback!: (event: SseEvent) => void
    postSse.mockImplementationOnce((_path, _body, onEvent) => { callback = onEvent; return stream.promise })
    const wrapper = mount(AiPanel)
    await flushPromises()
    await submit(wrapper, '旧会话提问')
    await wrapper.findAll('.history-pick button')[1].trigger('click')
    await flushPromises()
    callback({ name: 'delta', data: { text: '旧回答' } })
    stream.resolve()
    await flushPromises()
    expect(wrapper.text()).toContain('B 会话')
    expect(wrapper.text()).not.toContain('旧回答')
    await submit(wrapper, '继续 B')
    expect(postSse.mock.calls[1][0]).toBe('/ai/sessions/b/messages:stream')
  })

  it('does not let pending creation requests act after unmount', async () => {
    const creation = deferred<{ publicId: string }>()
    api.post.mockReturnValue(creation.promise)
    const wrapper = mount(AiPanel)
    await flushPromises()
    await submit(wrapper, '离开之前')
    wrapper.unmount()
    creation.resolve({ publicId: 'late' })
    await flushPromises()
    expect(postSse).not.toHaveBeenCalled()
  })

  it('ignores a pending history response after reset, including its finally handler', async () => {
    const pending = deferred<unknown[]>()
    const stream = deferred<void>()
    api.get.mockImplementation((path: string) => path === '/ai/sessions' ? Promise.resolve(sessions) : pending.promise)
    api.post.mockResolvedValueOnce({ publicId: 'new' })
    postSse.mockReturnValueOnce(stream.promise)
    const wrapper = mount(AiPanel)
    await flushPromises()
    await wrapper.findAll('.history-pick button')[0].trigger('click')
    await wrapper.get('[aria-label="新会话"]').trigger('click')
    await submit(wrapper, '新对话')
    pending.reject(new Error('old history failed'))
    await flushPromises()
    expect(wrapper.find('.error').exists()).toBe(false)
    expect(wrapper.find('[aria-label="停止生成"]').exists()).toBe(true)
    stream.resolve()
    await flushPromises()
  })

  it('stops pending creation without sending, restores its unsent draft, and ignores the late response', async () => {
    const pending = deferred<{ publicId: string }>()
    api.post.mockReturnValueOnce(pending.promise).mockResolvedValueOnce({ publicId: 'fresh' })
    const wrapper = mount(AiPanel)
    await flushPromises()
    await submit(wrapper, '尚未发出')
    await wrapper.get('[aria-label="停止生成"]').trigger('click')
    expect(wrapper.get('textarea').element.value).toBe('尚未发出')
    expect(wrapper.find('.ai-message').exists()).toBe(false)
    pending.resolve({ publicId: 'abandoned' })
    await flushPromises()
    expect(postSse).not.toHaveBeenCalled()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(postSse.mock.calls[0][0]).toBe('/ai/sessions/fresh/messages:stream')
  })

  it('aborts on unmount and ignores late events without refreshing history', async () => {
    const pending = deferred<void>()
    let callback!: (event: SseEvent) => void
    let signal!: AbortSignal
    api.post.mockResolvedValue({ publicId: 'a' })
    postSse.mockImplementation((_path, _body, handler, abortSignal) => { callback = handler; signal = abortSignal; return pending.promise })
    const wrapper = mount(AiPanel)
    await flushPromises()
    await submit(wrapper, '离开小镇面板')
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
    callback({ name: 'delta', data: { text: '迟到' } })
    callback({ name: 'error', data: { message: '迟到错误' } })
    pending.resolve()
    await flushPromises()
    expect(api.get).toHaveBeenCalledTimes(1)
  })

  it('shows loading and retry for the history list without losing a draft', async () => {
    const pending = deferred<unknown[]>()
    api.get.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(sessions)
    const wrapper = mount(AiPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('正在加载历史对话')
    await wrapper.get('textarea').setValue('保留草稿')
    pending.reject(new Error('network'))
    await flushPromises()
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.history-pick button')).toHaveLength(2)
    expect(wrapper.get('textarea').element.value).toBe('保留草稿')
  })

  it('restores an unanswered failed draft and does not append events after done', async () => {
    api.post.mockRejectedValueOnce(new Error('create failed')).mockResolvedValueOnce({ publicId: 'retry' })
    const wrapper = mount(AiPanel)
    await flushPromises()
    await submit(wrapper, '重试的文字')
    expect(wrapper.get('textarea').element.value).toBe('重试的文字')
    expect(wrapper.find('.ai-message').exists()).toBe(false)
    postSse.mockImplementationOnce(async (_path, _body, callback) => {
      callback({ name: 'delta', data: { text: '完整回复' } })
      callback({ name: 'done', data: {} })
      callback({ name: 'delta', data: { text: '不应追加' } })
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('完整回复')
    expect(wrapper.text()).not.toContain('不应追加')
  })
})
