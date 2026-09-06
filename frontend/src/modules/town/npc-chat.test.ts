import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useNpcChatStore } from './npc-chat'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const postSse = vi.hoisted(() => vi.fn())
const router = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
vi.mock('../../shared/api/sse', () => ({ postSse }))
vi.mock('../../app/router', () => ({ router }))

describe('npc chat store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.get.mockReset()
    api.post.mockReset()
    postSse.mockReset()
    router.push.mockReset()
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-1111-4111-8111-111111111111')
  })

  it('loads history for the given npc', async () => {
    api.get.mockResolvedValue([
      { publicId: 'm1', role: 'USER', content: '你好', status: 'COMPLETED', createdAt: '2026-09-05T00:00:00Z' },
      { publicId: 'm2', role: 'ASSISTANT', content: '嗨', options: [{ label: '继续' }], actions: [], status: 'COMPLETED', createdAt: '2026-09-05T00:00:01Z' },
    ])
    const store = useNpcChatStore()
    await store.history('GUIDE')
    expect(api.get).toHaveBeenCalledWith('/town/npc/GUIDE/messages')
    expect(store.byNpc.GUIDE.messages).toHaveLength(2)
    expect(store.byNpc.GUIDE.messages[1].options).toEqual([{ label: '继续' }])
    expect(store.byNpc.GUIDE.error).toBe('')
  })

  it('tolerates a failed history load', async () => {
    api.get.mockRejectedValue(new Error('network down'))
    const store = useNpcChatStore()
    await store.history('POSTMAN')
    expect(store.byNpc.POSTMAN.error).not.toBe('')
    expect(store.byNpc.POSTMAN.messages).toEqual([])
  })

  it('accumulates delta text onto a single pending assistant message', async () => {
    postSse.mockImplementation(async (_path, _body, emit) => {
      emit({ name: 'meta', data: { npc: 'GUIDE', messagePublicId: 'srv-1', model: 'x', riskLevel: 'L0' } })
      emit({ name: 'delta', data: { text: '你' } })
      emit({ name: 'delta', data: { text: '好' } })
      emit({ name: 'done', data: { messagePublicId: 'srv-1', status: 'COMPLETED', options: [], actions: [] } })
    })
    const store = useNpcChatStore()
    await store.send('GUIDE', '在吗')

    expect(postSse).toHaveBeenCalledWith('/town/npc/GUIDE/chat:stream', { message: '在吗' }, expect.any(Function), expect.any(AbortSignal))
    const messages = store.byNpc.GUIDE.messages
    expect(messages).toHaveLength(2)
    expect(messages[0]).toMatchObject({ role: 'USER', content: '在吗' })
    expect(messages[1]).toMatchObject({ role: 'ASSISTANT', content: '你好', publicId: 'srv-1', status: 'COMPLETED', pending: false })
    expect(store.byNpc.GUIDE.sending).toBe(false)
    expect(store.byNpc.GUIDE.streaming).toBe(false)
  })

  it('shows the safety notice as the assistant text and flags it blocked', async () => {
    postSse.mockImplementation(async (_path, _body, emit) => {
      emit({ name: 'meta', data: { messagePublicId: 'srv-2' } })
      emit({ name: 'safety', data: { riskLevel: 'L2', message: '这个话题需要更谨慎地聊' } })
      emit({ name: 'done', data: { messagePublicId: 'srv-2', status: 'BLOCKED', options: [], actions: [] } })
    })
    const store = useNpcChatStore()
    await store.send('GUIDE', '一些敏感内容')

    const assistant = store.byNpc.GUIDE.messages.at(-1)!
    expect(assistant.content).toBe('这个话题需要更谨慎地聊')
    expect(assistant.blocked).toBe(true)
    expect(assistant.status).toBe('BLOCKED')
  })

  it('attaches options and actions from the done event', async () => {
    const actions = [{ type: 'COMPLETE_TASK', scheduleId: 'sched-1', label: '完成任务', taskTitle: '背单词' }]
    const options = [{ label: '再聊聊' }, { label: '算了' }]
    postSse.mockImplementation(async (_path, _body, emit) => {
      emit({ name: 'delta', data: { text: '要不要现在完成？' } })
      emit({ name: 'done', data: { messagePublicId: 'srv-3', status: 'COMPLETED', options, actions } })
    })
    const store = useNpcChatStore()
    await store.send('POSTMAN', '有什么建议')

    const assistant = store.byNpc.POSTMAN.messages.at(-1)!
    expect(assistant.options).toEqual(options)
    expect(assistant.actions).toEqual(actions)
  })

  it('maps a quota error code to the fixed copy', async () => {
    postSse.mockImplementation(async (_path, _body, emit) => {
      emit({ name: 'error', data: { code: 'QUOTA_EXCEEDED', message: 'daily limit reached' } })
    })
    const store = useNpcChatStore()
    await store.send('GUIDE', '再聊会儿')
    expect(store.byNpc.GUIDE.error).toBe('小助今天说累了，明天再聊')
  })

  it('falls back to a generic message for a non-quota error event', async () => {
    postSse.mockImplementation(async (_path, _body, emit) => {
      emit({ name: 'error', data: { code: 'AI_PROVIDER_AUTH_FAILED', message: '服务暂时不可用' } })
    })
    const store = useNpcChatStore()
    await store.send('GUIDE', '你好')
    expect(store.byNpc.GUIDE.error).toBe('服务暂时不可用')
  })

  it('sets a generic error and clears pending when the stream throws', async () => {
    postSse.mockRejectedValue(new Error('AI_TEMPORARILY_UNAVAILABLE'))
    const store = useNpcChatStore()
    await store.send('GUIDE', '你好')
    expect(store.byNpc.GUIDE.error).toBe('对话暂时不可用，请稍后再试')
    expect(store.byNpc.GUIDE.messages.at(-1)?.pending).toBe(false)
  })

  it('ignores an intentional abort without setting an error', async () => {
    let signalRef: AbortSignal | undefined
    postSse.mockImplementation(async (_path, _body, _emit, signal: AbortSignal) => {
      signalRef = signal
      await new Promise((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })))
      })
    })
    const store = useNpcChatStore()
    const promise = store.send('GUIDE', '等一下')
    store.abort('GUIDE')
    await promise
    expect(signalRef?.aborted).toBe(true)
    expect(store.byNpc.GUIDE.error).toBe('')
    expect(store.byNpc.GUIDE.sending).toBe(false)
    expect(store.byNpc.GUIDE.streaming).toBe(false)
  })

  it('posts a task event with an Idempotency-Key header and refreshes town data', async () => {
    api.post.mockResolvedValue({ scheduleStatus: 'DONE' })
    const store = useNpcChatStore()
    const result = await store.runAction({ type: 'COMPLETE_TASK', scheduleId: 'sched-9', label: '完成任务' })
    expect(api.post).toHaveBeenCalledWith(
      '/task-schedules/sched-9/events',
      { eventType: 'COMPLETED' },
      { 'Idempotency-Key': '11111111-1111-4111-8111-111111111111' },
    )
    expect(result.ok).toBe(true)
    expect(result.scheduleStatus).toBe('DONE')
  })

  it('sends a defer payload shaped like TodayView\'s deferredStartAt', async () => {
    api.post.mockResolvedValue({ scheduleStatus: 'DEFERRED' })
    const store = useNpcChatStore()
    await store.runAction({ type: 'DEFER_TASK', scheduleId: 'sched-2', label: '延期' })
    const [, payload] = api.post.mock.calls[0]
    expect(typeof (payload as { deferredStartAt: string }).deferredStartAt).toBe('string')
    expect(() => new Date((payload as { deferredStartAt: string }).deferredStartAt).toISOString()).not.toThrow()
  })

  it('reports a failed task event without throwing', async () => {
    api.post.mockRejectedValue(new Error('boom'))
    const store = useNpcChatStore()
    const result = await store.runAction({ type: 'START_TASK', scheduleId: 'sched-3', label: '开始' })
    expect(result.ok).toBe(false)
  })

  it('runs navigation actions through the router', async () => {
    const store = useNpcChatStore()
    const result = await store.runAction({ type: 'OPEN_TODAY', label: '去今日页' })
    expect(router.push).toHaveBeenCalledWith('/today')
    expect(result.ok).toBe(true)
  })

  it('loads the latest reflection and tolerates a 404', async () => {
    api.get.mockRejectedValueOnce({ status: 404, code: 'NOT_FOUND', message: 'none yet' })
    const store = useNpcChatStore()
    const reflection = await store.loadReflection()
    expect(reflection).toBeNull()
    expect(store.reflection).toBeNull()
  })

  it('caches the reflection after the first successful load', async () => {
    api.get.mockReset().mockResolvedValue({ publicId: 'r1', localDate: '2026-09-05', greeting: '早上好', insights: ['坚持了 3 天'] })
    const store = useNpcChatStore()
    const first = await store.loadReflection()
    const second = await store.loadReflection()
    expect(first).toEqual(second)
    expect(api.get).toHaveBeenCalledTimes(1)
    expect(api.get).toHaveBeenCalledWith('/town/reflection/latest')
  })
})

describe('live interrupt isolation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.get.mockReset().mockResolvedValue([])
    postSse.mockReset()
  })

  it('consumes a structured live interrupt once and rejects every trailing event', async () => {
    postSse.mockImplementation(async (_path, _body, emit) => {
      emit({ name: 'delta', data: { text: '好，下次再聊。' } })
      emit({ name: 'done', data: { control: { type: '/interrupt', reason: '你先忙，下次再聊。' }, options: [{ label: '继续' }] } })
      emit({ name: 'delta', data: { text: '不应出现' } })
      emit({ name: 'done', data: { control: { type: '/interrupt', reason: '重复' } } })
      emit({ name: 'error', data: { message: '尾部错误' } })
      throw new Error('late transport failure')
    })
    const store = useNpcChatStore()
    await store.send('GUIDE', '停止交谈')
    expect(store.byNpc.GUIDE).toMatchObject({ leavingReason: '你先忙，下次再聊。', error: '', sending: false, streaming: false })
    expect(store.byNpc.GUIDE.messages.at(-1)).toMatchObject({ content: '好，下次再聊。', options: [], pending: false })
    expect(store.consumeInterrupt('GUIDE')).toBe('你先忙，下次再聊。')
    expect(store.consumeInterrupt('GUIDE')).toBeNull()
    await store.send('GUIDE', '再说')
    expect(postSse).toHaveBeenCalledTimes(1)
    store.abort('GUIDE')
    expect(store.byNpc.GUIDE.leavingReason).toBeNull()
    expect(store.byNpc.GUIDE.messages).toHaveLength(2)
  })

  it.each([null, { type: '/other', reason: '离开' }, { type: '/interrupt' }, { type: '/interrupt', reason: 42 }, { type: '/interrupt', reason: '  ' }, '/interrupt'])(
    'ignores non-whitelisted or malformed control %j and all text commands', async control => {
      postSse.mockImplementation(async (_path, _body, emit) => {
        emit({ name: 'delta', data: { text: '/interrupt', control: { type: '/interrupt', reason: '伪造' } } })
        emit({ name: 'done', data: { control } })
      })
      const store = useNpcChatStore()
      await store.send('GUIDE', '/interrupt')
      expect(store.byNpc.GUIDE.messages[0].content).toBe('/interrupt')
      expect(store.byNpc.GUIDE.messages[1].content).toBe('/interrupt')
      expect(store.consumeInterrupt('GUIDE')).toBeNull()
      expect(store.byNpc.GUIDE.leavingReason).toBeNull()
    },
  )

  it('never replays a history control', async () => {
    api.get.mockResolvedValue([{ publicId: 'old', role: 'ASSISTANT', content: '/interrupt', status: 'COMPLETED', control: { type: '/interrupt', reason: '旧的告别' } }])
    const store = useNpcChatStore()
    await store.history('GUIDE')
    expect(store.byNpc.GUIDE.messages[0]).not.toHaveProperty('control')
    expect(store.consumeInterrupt('GUIDE')).toBeNull()
  })

  it('prevents cancelled callbacks, errors and finally from modifying a new request', async () => {
    const streams: Array<{ emit: (event: unknown) => void; reject: (error: Error) => void; resolve: () => void; signal: AbortSignal }> = []
    postSse.mockImplementation((_path, _body, emit, signal) => new Promise<void>((resolve, reject) => streams.push({ emit, signal, resolve, reject })))
    const store = useNpcChatStore()
    const old = store.send('GUIDE', '旧请求')
    store.abort('GUIDE')
    const fresh = store.send('GUIDE', '新请求')
    streams[0].emit({ name: 'done', data: { control: { type: '/interrupt', reason: '旧控制' } } })
    streams[0].reject(new Error('old error'))
    await old
    expect(store.byNpc.GUIDE).toMatchObject({ sending: true, streaming: true, error: '', leavingReason: null })
    expect(store.byNpc.GUIDE.messages.at(-1)?.pending).toBe(true)
    store.abort('GUIDE')
    expect(streams[1].signal.aborted).toBe(true)
    streams[1].resolve()
    await fresh
  })

  it('ignores history resolving after a new live conversation', async () => {
    let resolveHistory!: (rows: unknown[]) => void
    api.get.mockImplementation(() => new Promise(resolve => { resolveHistory = resolve }))
    postSse.mockResolvedValue(undefined)
    const store = useNpcChatStore()
    const history = store.history('GUIDE')
    await store.send('GUIDE', '新的对话')
    resolveHistory([])
    await history
    expect(store.byNpc.GUIDE.messages[0].content).toBe('新的对话')
    expect(store.byNpc.GUIDE.loading).toBe(false)
  })
})
