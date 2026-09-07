import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NpcDialogue from './NpcDialogue.vue'
import { useNpcChatStore } from './npc-chat'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const postSse = vi.hoisted(() => vi.fn())
const router = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
vi.mock('../../shared/api/sse', () => ({ postSse }))
vi.mock('../../app/router', () => ({ router }))

describe('NpcDialogue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.get.mockReset()
    api.post.mockReset()
    postSse.mockReset()
    router.push.mockReset()
  })

  it('shows the opener line when there is no history yet', async () => {
    api.get.mockResolvedValue([])
    const wrapper = mount(NpcDialogue, { props: { npc: 'GUIDE', displayName: '小助', opener: '早上好，今天想先做点什么？' } })
    await flushPromises()
    expect(wrapper.get('.npc-textbox').text()).toContain('早上好，今天想先做点什么？')
  })

  it('sends an option chip as the next message', async () => {
    api.get.mockResolvedValue([{
      publicId: 'm1',
      role: 'ASSISTANT',
      content: '今天准备做点什么？',
      options: [{ label: '继续聊聊' }],
      actions: [],
      status: 'COMPLETED',
      createdAt: '2026-09-05T00:00:00Z',
    }])
    postSse.mockImplementation(async (_path, _body, emit) => {
      emit({ name: 'delta', data: { text: '好呀，说说看' } })
      emit({ name: 'done', data: { messagePublicId: 'm2', status: 'COMPLETED', options: [], actions: [] } })
    })
    const wrapper = mount(NpcDialogue, { props: { npc: 'GUIDE', displayName: '小助' } })
    await flushPromises()

    const chip = wrapper.get('.npc-chip')
    expect(chip.text()).toBe('继续聊聊')
    await chip.trigger('click')
    await flushPromises()

    expect(postSse).toHaveBeenCalledWith('/town/npc/GUIDE/chat:stream', { message: '继续聊聊' }, expect.any(Function), expect.any(AbortSignal))
    expect(wrapper.get('.npc-textbox').text()).toContain('好呀，说说看')
  })

  it('reopens the composer once the prose has arrived, before the option tail lands', async () => {
    api.get.mockResolvedValue([])
    // A stream that delivers prose and then hangs, the way the model does while it writes chips.
    postSse.mockImplementation(async (_path, _body, emit) => {
      emit({ name: 'delta', data: { text: '那就先看五分钟。' } })
      await new Promise(() => {})
    })
    const wrapper = mount(NpcDialogue, { props: { npc: 'GUIDE', displayName: '小助' } })
    await flushPromises()

    const input = wrapper.get('input[type="text"]')
    await input.setValue('好')
    await wrapper.get('.npc-composer').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.npc-textbox').text()).toContain('那就先看五分钟。')
    expect((input.element as HTMLInputElement).disabled).toBe(false)

    await input.setValue('那我开始了')
    await wrapper.get('.npc-composer').trigger('submit')
    await flushPromises()
    expect(postSse).toHaveBeenLastCalledWith('/town/npc/GUIDE/chat:stream', { message: '那我开始了' }, expect.any(Function), expect.any(AbortSignal))
  })

  it('requires a second tap to confirm and run an action', async () => {
    api.get.mockResolvedValue([{
      publicId: 'm1',
      role: 'ASSISTANT',
      content: '要不要现在完成背单词？',
      options: [],
      actions: [{ type: 'COMPLETE_TASK', scheduleId: 'sched-1', label: '完成任务', taskTitle: '背单词' }],
      status: 'COMPLETED',
      createdAt: '2026-09-05T00:00:00Z',
    }])
    api.post.mockResolvedValue({ scheduleStatus: 'DONE' })
    const wrapper = mount(NpcDialogue, { props: { npc: 'GUIDE', displayName: '小助' } })
    await flushPromises()

    const actionButton = wrapper.get('.npc-action-button')
    expect(actionButton.text()).toBe('完成任务')

    await actionButton.trigger('click')
    expect(wrapper.get('.npc-action-button').text()).toBe('再点一次确认')
    expect(api.post).not.toHaveBeenCalled()

    await wrapper.get('.npc-action-button').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith(
      '/task-schedules/sched-1/events',
      { eventType: 'COMPLETED' },
      { 'Idempotency-Key': expect.any(String) },
    )
    expect(wrapper.get('.npc-action-feedback').text().length).toBeGreaterThan(0)
  })

  it('closes on request', async () => {
    api.get.mockResolvedValue([])
    const wrapper = mount(NpcDialogue, { props: { npc: 'POSTMAN', displayName: '邮递员' } })
    await flushPromises()
    await wrapper.get('.npc-close').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})


describe('NpcDialogue interruption', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.get.mockReset().mockResolvedValue([])
    postSse.mockReset()
  })

  it('emits once, disables continuation and preserves a draft through automatic unmount', async () => {
    let emitEvent!: (event: unknown) => void
    let finish!: () => void
    postSse.mockImplementation((_path, _body, emit) => {
      emitEvent = emit
      emit({ name: 'delta', data: { text: '好，我们下次再聊。' } })
      return new Promise<void>(resolve => { finish = resolve })
    })
    const wrapper = mount(NpcDialogue, { props: { npc: 'GUIDE', displayName: '小助' } })
    await flushPromises()
    await wrapper.get('input').setValue('先不聊了')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('input').setValue('尚未发送的草稿')
    emitEvent({ name: 'done', data: { control: { type: '/interrupt', reason: '你先忙，下次见。' }, options: [{ label: '继续' }] } })
    emitEvent({ name: 'done', data: { control: { type: '/interrupt', reason: '重复' } } })
    await flushPromises()
    expect(wrapper.emitted('interrupt')).toEqual([['你先忙，下次见。']])
    expect(wrapper.text()).toContain('已告别')
    expect(wrapper.text()).not.toContain('/interrupt')
    expect((wrapper.get('input').element as HTMLInputElement).disabled).toBe(true)
    expect(wrapper.find('.npc-chip').exists()).toBe(false)
    expect(useNpcChatStore().byNpc.GUIDE.messages).toHaveLength(2)
    // A parent may cancel the store request before its automatic close unmounts us.
    useNpcChatStore().abort('GUIDE')
    wrapper.unmount()
    finish()
    await flushPromises()
    const reopened = mount(NpcDialogue, { props: { npc: 'GUIDE', displayName: '小助' } })
    await flushPromises()
    expect((reopened.get('input').element as HTMLInputElement).value).toBe('尚未发送的草稿')
    expect(reopened.emitted('interrupt')).toBeUndefined()
    reopened.unmount()
  })

  it('disables options/actions for an external departure without emitting a live control', async () => {
    api.get.mockResolvedValue([{ publicId: 'm', role: 'ASSISTANT', content: '你好', status: 'COMPLETED', options: [{ label: '继续' }], actions: [{ type: 'OPEN_TODAY', label: '今日' }] }])
    const wrapper = mount(NpcDialogue, { props: { npc: 'GUIDE', displayName: '小助' } })
    await flushPromises()
    await wrapper.get('input').setValue('草稿')
    await wrapper.setProps({ leavingReason: '该去送信了' })
    expect(wrapper.text()).toContain('该去送信了')
    for (const selector of ['input', '.npc-chip', '.npc-action-button', '.npc-send']) {
      expect(wrapper.get(selector).attributes('disabled')).toBeDefined()
    }
    expect(wrapper.emitted('interrupt')).toBeUndefined()
    wrapper.unmount()
    expect(useNpcChatStore().byNpc.GUIDE.draft).toBe('草稿')
  })

  it('cleans a normal close immediately and isolates a switch to another NPC', async () => {
    const signals: AbortSignal[] = []
    postSse.mockImplementation((_path, _body, _emit, signal) => { signals.push(signal); return new Promise(() => {}) })
    const wrapper = mount(NpcDialogue, { props: { npc: 'GUIDE', displayName: '小助' } })
    await flushPromises()
    await wrapper.get('input').setValue('你好')
    await wrapper.get('form').trigger('submit')
    await wrapper.setProps({ npc: 'POSTMAN', displayName: '邮递员' })
    await flushPromises()
    expect(signals[0].aborted).toBe(true)
    expect(api.get).toHaveBeenLastCalledWith('/town/npc/POSTMAN/messages')
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('')
    await wrapper.get('input').setValue('普通草稿')
    await wrapper.get('.npc-close').trigger('click')
    expect(useNpcChatStore().byNpc.POSTMAN.draft).toBe('')
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
