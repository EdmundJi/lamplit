import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ResidentMoment from './ResidentMoment.vue'
import type { TownNpcView } from './town-npc.types'

const mocks = vi.hoisted(() => ({ post: vi.fn(), talkingPoints: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api: { post: mocks.post } }))
vi.mock('./town-npc.store', () => ({ useTownNpcStore: () => ({ talkingPoints: mocks.talkingPoints }) }))
enableAutoUnmount(afterEach)
function resident(overrides: Partial<TownNpcView> = {}): TownNpcView {
  return {
    code: 'LIN', displayName: '林舟', layer: 2, sprite: 'resident', dimension: 'KNOWLEDGE',
    interests: {}, affinityToPlayer: 0, mood: { valence: 0, energy: 0 },
    schedule: [{ startHour: 0, endHour: 24, place: 'park', activity: 'reading' }],
    talkingPoints: [], ...overrides,
  }
}
const render = (npc = resident()) => mount(ResidentMoment, { props: { npc } })
async function expand(wrapper: ReturnType<typeof render>) { await wrapper.get('[aria-expanded]').trigger('click') }
function deferred() {
  let resolve!: () => void
  let reject!: (error: Error) => void
  const promise = new Promise<void>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
beforeEach(() => {
  setActivePinia(createPinia())
  mocks.post.mockReset().mockResolvedValue(undefined)
  mocks.talkingPoints.mockReset().mockResolvedValue([])
})

describe('ResidentMoment voluntary coarse sharing', () => {
  it('starts collapsed and requires a category and explicit confirmation', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(false)
    await expand(wrapper)
    expect(wrapper.text()).toContain('这类近况可能被转述给其他居民')
    expect(wrapper.text()).toContain('不包含具体任务标题、数字或私人原文')
    expect(wrapper.findAll('input[type="radio"]')).toHaveLength(4)
    expect(wrapper.find('textarea, input:not([type="radio"])').exists()).toBe(false)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    await wrapper.get('input[value="RHYTHM"]').setValue()
    expect(mocks.post).not.toHaveBeenCalled()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledOnce()
    expect(wrapper.get('[role="status"]').text()).toContain('已告诉林舟')
  })
  it.each(['RHYTHM', 'DIMENSION_FOCUS', 'STREAK_HINT', 'LEVEL_BUCKET'])('sends only kind %s', async kind => {
    const wrapper = render()
    await expand(wrapper)
    await wrapper.get(`input[value="${kind}"]`).setValue()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledExactlyOnceWith('/town/npc/LIN/tell', { kind })
    expect(wrapper.text()).toContain('对方之后可能会转述')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(mocks.post).toHaveBeenCalledOnce()
  })
  it.each([1, 3] as const)('does not offer sharing to layer %s', async layer => {
    const wrapper = render(resident({ layer }))
    await flushPromises()
    expect(wrapper.find('.moment-tell').exists()).toBe(false)
    expect(mocks.post).not.toHaveBeenCalled()
  })
  it('blocks duplicate pending requests and permits choosing another category after success', async () => {
    const request = deferred()
    mocks.post.mockReturnValueOnce(request.promise)
    const wrapper = render()
    await expand(wrapper)
    await wrapper.get('input[value="RHYTHM"]').setValue()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('form').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[role="status"]').text()).toContain('正在分享')
    await wrapper.get('form').trigger('submit')
    expect(mocks.post).toHaveBeenCalledOnce()
    request.resolve()
    await flushPromises()
    await wrapper.get('input[value="LEVEL_BUCKET"]').setValue()
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledTimes(2)
  })
  it('shows errors and retries only after explicit confirmation', async () => {
    mocks.post.mockRejectedValueOnce(new Error('offline'))
    const wrapper = render()
    await expand(wrapper)
    await wrapper.get('input[value="STREAK_HINT"]').setValue()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('暂时没能告诉对方')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(mocks.post).toHaveBeenCalledOnce()
    expect(wrapper.get('button[type="submit"]').text()).toBe('重试告知')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('已告诉林舟')
  })
  it.each(['success', 'failure'])('ignores stale %s when changing residents', async outcome => {
    const request = deferred()
    mocks.post.mockReturnValueOnce(request.promise)
    const wrapper = render()
    await expand(wrapper)
    await wrapper.get('input[value="DIMENSION_FOCUS"]').setValue()
    await wrapper.get('form').trigger('submit')
    await wrapper.setProps({ npc: resident({ code: 'XIA', displayName: '小夏' }) })
    expect(wrapper.get('[aria-expanded]').attributes('aria-expanded')).toBe('false')
    if (outcome === 'success') request.resolve()
    else request.reject(new Error('late error'))
    await flushPromises()
    await expand(wrapper)
    expect(wrapper.find('[role="status"], [role="alert"]').exists()).toBe(false)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('input[value="RHYTHM"]').setValue()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.post).toHaveBeenLastCalledWith('/town/npc/XIA/tell', { kind: 'RHYTHM' })
  })
  it('can collapse or leave without sharing', async () => {
    const wrapper = render()
    await expand(wrapper)
    await wrapper.get('input[value="RHYTHM"]').setValue()
    await wrapper.get('[aria-expanded]').trigger('click')
    expect(wrapper.find('form').exists()).toBe(false)
    await wrapper.get('[aria-label="结束闲聊"]').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(mocks.post).not.toHaveBeenCalled()
  })
})
