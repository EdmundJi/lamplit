import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TodayView from './TodayView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

describe('Today actions', () => {
  beforeEach(() => {
    api.get.mockReset().mockImplementation((path: string) => Promise.resolve(path.startsWith('/task-schedules')
      ? [{ publicId: 'schedule-1', taskTitle: '复习一章', plannedStartAt: new Date().toISOString(), status: 'PLANNED' }]
      : []))
    api.post.mockReset()
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-1111-4111-8111-111111111111')
  })

  it('coalesces duplicate clicks into one idempotent request and supports reversal', async () => {
    let resolve!: (value: unknown) => void
    api.post.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const wrapper = mount(TodayView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    const complete = wrapper.get('button[aria-label="完成"]')
    await complete.trigger('click'); await complete.trigger('click')
    expect(api.post).toHaveBeenCalledTimes(1)
    expect(api.post).toHaveBeenCalledWith('/task-schedules/schedule-1/events', { eventType: 'COMPLETED' }, { 'Idempotency-Key': '11111111-1111-4111-8111-111111111111' })
    resolve({ scheduleStatus: 'DONE', eventPublicId: 'event-1', roleExperienceDelta: 6, roleProgress: { roleName: '学生', level: 2 } }); await flushPromises()
    expect(wrapper.get('.feedback-banner[data-tone="celebrate"]').text().length).toBeGreaterThan(12)
    expect(wrapper.get('.feedback-banner').text()).toContain('学生 +6 经验 · LV.2')
    api.post.mockResolvedValue({ scheduleStatus: 'PLANNED' })
    await wrapper.findAll('button').find(button => button.text().includes('撤销'))!.trigger('click')
    expect(api.post).toHaveBeenLastCalledWith('/task-schedules/schedule-1/events/event-1/reverse', undefined, expect.any(Object))
  })

  it('requires an explicit ratio for partial completion', async () => {
    api.post.mockResolvedValue({ scheduleStatus: 'PARTIAL', eventPublicId: 'event-2' })
    const wrapper = mount(TodayView, { global: { stubs: { RouterLink: true } } })
    await flushPromises(); await wrapper.get('button[aria-label="部分完成"]').trigger('click')
    await wrapper.get('#completion').setValue(60); await wrapper.get('[role=dialog] .primary').trigger('click'); await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/task-schedules/schedule-1/events', { eventType: 'PARTIAL', completionRatio: 0.6 }, expect.any(Object))
    expect(wrapper.get('.feedback-banner[data-tone="support"]').text().length).toBeGreaterThan(12)
  })

  it('shows the daily quota and disables completion after four completed tasks', async () => {
    api.get.mockImplementation((path: string) => Promise.resolve(path.startsWith('/task-schedules')
      ? [
          ...Array.from({ length: 4 }, (_, index) => ({
            publicId: `done-${index}`,
            taskTitle: `已完成任务 ${index + 1}`,
            plannedStartAt: new Date().toISOString(),
            status: 'DONE',
          })),
          { publicId: 'schedule-5', taskTitle: '第五个任务', plannedStartAt: new Date().toISOString(), status: 'PLANNED' },
        ]
      : []))
    const wrapper = mount(TodayView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    expect(wrapper.get('.task-quota').text()).toContain('4 / 4')
    expect(wrapper.get('.task-quota').attributes('data-limit-reached')).toBe('true')
    expect(wrapper.findAll('button[aria-label="完成"]').every(button => button.attributes('disabled') !== undefined)).toBe(true)
  })
})
