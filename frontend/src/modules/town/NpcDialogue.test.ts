import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NpcDialogue from './NpcDialogue.vue'

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
