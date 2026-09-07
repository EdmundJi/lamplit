import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TownStories from './TownStories.vue'
import type { TownStoryView } from './town-stories'
const requests = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api: requests }))
const initial: TownStoryView = {
  npcCode: 'GUIDE', displayName: '小助', title: '窗边的一小格书架', stage: 0, revision: 0,
  paused: false, participation: 'UNDECIDED', heading: '一个小念头', body: '小助想整理书架。',
  actions: [{ code: 'BEGIN', label: '听听这个小计划' }], completedAt: null,
}
function started(): TownStoryView {
  return { ...initial, stage: 1, revision: 1, body: '小助开始挑书。',
    actions: [{ code: 'CONTINUE', label: '听听接下来的打算' }, { code: 'PAUSE', label: '今天先到这里' }] }
}
async function open() {
  const wrapper = mount(TownStories, { props: { npcCode: 'GUIDE' } })
  await wrapper.get('.story-toggle').trigger('click')
  await flushPromises()
  return wrapper
}
beforeEach(() => { vi.resetAllMocks(); requests.get.mockResolvedValue(initial); requests.post.mockResolvedValue(started()) })

describe('TownStories', () => {
  it('waits for explicit interest, then advances only with the server stage and revision', async () => {
    const wrapper = mount(TownStories, { props: { npcCode: 'GUIDE' } })
    expect(requests.get).not.toHaveBeenCalled()
    await wrapper.get('.story-toggle').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('小助想整理书架')
    expect(requests.post).not.toHaveBeenCalled()
    await wrapper.get('.story-actions button').trigger('click')
    await flushPromises()
    expect(requests.post).toHaveBeenCalledWith('/town/stories/GUIDE/advance', { action: 'BEGIN', expectedStage: 0, expectedRevision: 0 })
    expect(wrapper.text()).toContain('小助开始挑书')
  })
  it('keeps the same revision after an uncertain save so retry cannot skip a passage', async () => {
    requests.post.mockRejectedValueOnce(new Error('connection lost'))
    const wrapper = await open()
    await wrapper.get('.story-actions button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('不会跳过故事')
    expect(wrapper.text()).toContain('小助想整理书架')
    await wrapper.get('.story-actions button').trigger('click')
    await flushPromises()
    expect(requests.post.mock.calls[1]).toEqual(requests.post.mock.calls[0])
  })
  it('restores a paused story and lets the player resume without advancing', async () => {
    requests.get.mockResolvedValue({ ...started(), paused: true, revision: 2,
      actions: [{ code: 'RESUME', label: '接着上次这一段' }] })
    requests.post.mockResolvedValue({ ...started(), revision: 3 })
    const wrapper = await open()
    expect(wrapper.text()).toContain('暂时停在这里')
    await wrapper.get('.story-actions button').trigger('click')
    await flushPromises()
    expect(requests.post).toHaveBeenCalledWith('/town/stories/GUIDE/advance', { action: 'RESUME', expectedStage: 1, expectedRevision: 2 })
    expect(wrapper.text()).toContain('小助开始挑书')
  })
  it('ignores a previous resident response after switching dialogue', async () => {
    let resolve!: (story: TownStoryView) => void
    requests.get.mockReturnValue(new Promise<TownStoryView>(done => { resolve = done }))
    const wrapper = mount(TownStories, { props: { npcCode: 'GUIDE' } })
    await wrapper.get('.story-toggle').trigger('click')
    await wrapper.setProps({ npcCode: 'POSTMAN' })
    resolve(initial)
    await flushPromises()
    expect(wrapper.text()).not.toContain('小助想整理书架')
    expect(wrapper.find('.story-content').exists()).toBe(false)
  })
  it('shows a completed memory with no more progress controls', async () => {
    requests.get.mockResolvedValue({ ...initial, stage: 4, revision: 4, completedAt: '2026-09-07T12:00:00Z', actions: [] })
    const wrapper = await open()
    expect(wrapper.text()).toContain('这段回忆已留下')
    expect(wrapper.find('.story-actions').exists()).toBe(false)
    expect(requests.post).not.toHaveBeenCalled()
  })
  it('does not advance while the resident is leaving', async () => {
    const wrapper = await open()
    await wrapper.setProps({ disabled: true })
    expect(wrapper.get('.story-actions button').attributes('disabled')).toBeDefined()
    await wrapper.get('.story-actions button').trigger('click')
    expect(requests.post).not.toHaveBeenCalled()
  })
})
