import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChecklistView from './ChecklistView.vue'
import { useAuthStore } from '../auth/auth.store'
import { dateInZone } from './checklist.logic'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
const date = dateInZone(new Date(), 'Asia/Shanghai')
const task = (id: string, status = 'PLANNED', localDate = date) => ({ publicId: id, taskPublicId: `task-${id}`, taskTitle: `事项 ${id}`, status, localDate, plannedStartAt: `${localDate}T00:00:00Z`, updatedAt: new Date().toISOString() })
async function setup() {
  const pinia = createPinia()
  useAuthStore(pinia).user = { publicId: 'checklist-user', email: 'a@example.test', displayName: 'A', timezone: 'Asia/Shanghai', role: 'USER' }
  const wrapper = mount(ChecklistView, { global: { plugins: [pinia] } })
  await flushPromises()
  return wrapper
}
describe('minimal checklist', () => {
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); api.get.mockResolvedValue([]) })
  it('adds without a goal, ignores IME confirmation, retains a failed draft and reuses its retry key', async () => {
    const wrapper = await setup()
    const input = wrapper.get('input[aria-label="添加一件事"]')
    await input.setValue('买咖啡豆')
    await input.trigger('keydown', { key: 'Enter', isComposing: true })
    expect(api.post).not.toHaveBeenCalled()
    api.post.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({})
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect((input.element as HTMLInputElement).value).toBe('买咖啡豆')
    const first = api.post.mock.calls[0]
    expect(first[0]).toBe('/tasks/quick')
    expect(first[1]).toEqual({ title: '买咖啡豆', localDate: date })
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(api.post.mock.calls[1][2]).toEqual(first[2])
    expect((input.element as HTMLInputElement).value).toBe('')
    wrapper.unmount()
  })
  it('keeps earlier unfinished tasks, hides future tasks until All, and collapses completed tasks', async () => {
    api.get.mockResolvedValue([task('old', 'PLANNED', '2020-01-01'), task('future', 'PLANNED', '2099-01-01'), task('done', 'DONE')])
    const wrapper = await setup()
    expect(wrapper.get('.checklist-items').text()).toContain('事项 old')
    expect(wrapper.get('.checklist-items').text()).not.toContain('future')
    expect(wrapper.get('.completed-list').attributes('open')).toBeUndefined()
    await wrapper.get('.list-tabs button:nth-child(2)').trigger('click')
    expect(wrapper.get('.checklist-items').text()).toContain('future')
    wrapper.unmount()
  })
  it('completes a fifth item once and reverses it without rewards or onboarding', async () => {
    const rows = [...Array.from({ length: 4 }, (_, i) => task(`${i}`, 'DONE')), task('fifth')]
    api.get.mockImplementation(() => Promise.resolve(rows.map(row => ({ ...row }))))
    let resolve!: (value: unknown) => void
    api.post.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const wrapper = await setup()
    await wrapper.get('.task-check').trigger('click')
    await wrapper.get('.task-check').trigger('click')
    expect(api.post).toHaveBeenCalledTimes(1)
    rows[4].status = 'DONE'
    resolve({ scheduleStatus: 'DONE', eventPublicId: 'done-event' })
    await flushPromises()
    expect(wrapper.get('.completed-list summary').text()).toContain('5 项')
    api.post.mockImplementationOnce(() => { rows[4].status = 'PLANNED'; return Promise.resolve({}) })
    await wrapper.get('.list-feedback button').trigger('click')
    await flushPromises()
    expect(api.post.mock.calls[1][0]).toBe('/task-schedules/fifth/events/done-event/reverse')
    expect(wrapper.find('.task-check').exists()).toBe(true)
    expect(wrapper.text()).not.toMatch(/金币|经验|精力|目标/)
    wrapper.unmount()
  })
  it('preserves an edit after failure and provides keyboard-friendly ordering and deletion', async () => {
    api.get.mockResolvedValue([task('a'), task('b')])
    api.patch.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({})
    const wrapper = await setup()
    await wrapper.findAll('.task-title')[0].trigger('click')
    await wrapper.get('.title-edit').setValue('新名称')
    await wrapper.get('.title-edit').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect((wrapper.get('.title-edit').element as HTMLInputElement).value).toBe('新名称')
    await wrapper.get('.title-edit').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(api.patch).toHaveBeenLastCalledWith('/tasks/task-a', { title: '新名称' })
    await wrapper.findAll('.task-menu-toggle')[1].trigger('click')
    await wrapper.findAll('.task-menu button')[0].trigger('click')
    expect(wrapper.findAll('.task-title')[0].text()).toBe('事项 b')
    api.post.mockResolvedValueOnce({ scheduleStatus: 'CANCELLED', eventPublicId: 'delete-event' })
    await wrapper.findAll('.task-menu-toggle')[0].trigger('click')
    await wrapper.findAll('.task-menu button')[3].trigger('click')
    await flushPromises()
    expect(api.post.mock.calls[0][1]).toEqual({ eventType: 'CANCELLED' })
    expect(wrapper.get('.list-feedback button').text()).toContain('撤销')
    wrapper.unmount()
  })
})
