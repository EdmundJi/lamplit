import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TodayPanel from './TodayPanel.vue'
import { worldBridgeKey } from '../panel.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))
enableAutoUnmount(afterEach)

function mountPanel(bridge?: Record<string, unknown>) {
  return mount(TodayPanel, {
    global: {
      provide: bridge ? { [worldBridgeKey]: bridge } : {},
      stubs: { Teleport: true },
    },
  })
}

describe('TodayPanel', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset()
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-1111-4111-8111-111111111111')
  })

  it('mounts without a bridge and shows an empty state when there are no tasks', async () => {
    api.get.mockResolvedValue([])
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('今天还没有安排任务')
    expect(wrapper.text()).not.toContain('完整页面')
  })

  it('lists today tasks and records a planned task as done with an idempotent request', async () => {
    api.get.mockResolvedValue([{ publicId: 'schedule-1', taskTitle: '复习一章', plannedStartAt: new Date().toISOString(), status: 'PLANNED' }])
    api.post.mockResolvedValueOnce({ scheduleStatus: 'DONE' })
    const emit = vi.fn()
    const bridge = { emit, runMode: false, setRunMode: vi.fn() }
    const wrapper = mountPanel(bridge)
    await flushPromises()
    expect(wrapper.text()).toContain('复习一章')

    await wrapper.get('.task-more summary').trigger('click')
    const complete = wrapper.findAll('.task-more button').find(button => button.text().includes('已经做完，记下来'))!
    await complete.trigger('click')
    await complete.trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledTimes(1)
    expect(api.post).toHaveBeenCalledWith('/task-schedules/schedule-1/events', { eventType: 'COMPLETED' }, { 'Idempotency-Key': '11111111-1111-4111-8111-111111111111' })
    // Celebrations now follow the authoritative town-model refresh, rather than a schedule ID bridge event.
    expect(emit).not.toHaveBeenCalledWith(expect.objectContaining({ type: 'celebrate' }))
    expect(wrapper.get('.feedback-banner').text().length).toBeGreaterThan(0)
  })

  it('shows a readable message when the daily completion quota is reached', async () => {
    api.get.mockResolvedValue([{ publicId: 'schedule-1', taskTitle: '任务', plannedStartAt: new Date().toISOString(), status: 'PLANNED' }])
    api.post.mockRejectedValueOnce({ status: 409, code: 'DAILY_TASK_COMPLETION_LIMIT_REACHED', message: '' })
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.get('.task-more summary').trigger('click')
    await wrapper.findAll('.task-more button').find(button => button.text().includes('已经做完，记下来'))!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('今天已完成 4 个任务')
  })

  it('shows a readable message when loading fails', async () => {
    api.get.mockRejectedValue(new Error('network'))
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('暂时无法加载')
  })

  it('records a partial completion with the chosen ratio and offers to undo it', async () => {
    api.get.mockImplementation((path: string) => Promise.resolve(path.startsWith('/task-schedules')
      ? [{ publicId: 'schedule-1', taskTitle: '复习一章', plannedStartAt: new Date().toISOString(), status: 'PLANNED' }]
      : []))
    api.post.mockResolvedValueOnce({ scheduleStatus: 'PARTIAL', eventPublicId: 'event-1' })
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('.task-more summary').trigger('click')
    await wrapper.findAll('.task-more button').find(button => button.text().includes('部分完成'))!.trigger('click')
    await wrapper.get('#panel-completion').setValue(70)
    await wrapper.get('[role=dialog] .primary').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/task-schedules/schedule-1/events', { eventType: 'PARTIAL', completionRatio: 0.7 }, expect.any(Object))

    api.post.mockResolvedValueOnce({ scheduleStatus: 'PLANNED' })
    await wrapper.findAll('button').find(button => button.text().includes('撤销'))!.trigger('click')
    expect(api.post).toHaveBeenLastCalledWith('/task-schedules/schedule-1/events/event-1/reverse', undefined, expect.any(Object))
  })

  it('runs a focus session through to completion', async () => {
    api.get.mockResolvedValue([{ publicId: 'schedule-1', taskTitle: '复习一章', plannedStartAt: new Date().toISOString(), status: 'PLANNED', estimatedMinutes: 25 }])
    api.post.mockResolvedValueOnce({ scheduleStatus: 'DONE', eventPublicId: 'event-1' })
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('.task-more summary').trigger('click')
    await wrapper.findAll('.task-more button').find(button => button.text().includes('专注执行'))!.trigger('click')
    expect(wrapper.get('.focus-clock').text()).toBe('25:00')
    await wrapper.get('.focus-actions .primary').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/task-schedules/schedule-1/events', { eventType: 'COMPLETED' }, expect.any(Object))
    expect(wrapper.find('.focus-panel').exists()).toBe(false)
  })

  it('adjusts the daily rhythm and saves the suggested advice', async () => {
    api.get.mockImplementation((path: string) => {
      if (path.startsWith('/task-schedules')) return Promise.resolve([])
      if (path === '/daily-status') return Promise.resolve(null)
      return Promise.resolve([])
    })
    api.post.mockResolvedValue({ publicId: 'status-1', localDate: '2026-08-03', energy: 'LOW', availableMinutes: 15, advice: 'SHRINK', updatedAt: new Date().toISOString() })
    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('.rhythm-toggle').trigger('click')
    await wrapper.findAll('.mood-control button')[0].trigger('click')
    await wrapper.get('.check-result .secondary').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/daily-status', { energy: 'LOW', availableMinutes: 30 })
    expect(wrapper.get('.check-result .secondary').text()).toBe('更新建议')
  })

  it('starts a task and defers it with confirmation entirely inside the panel', async () => {
    api.get.mockImplementation((path: string) => Promise.resolve(path.startsWith('/task-schedules')
      ? [{ publicId: 'schedule-1', taskTitle: '复习', plannedStartAt: new Date().toISOString(), status: 'PLANNED' }] : []))
    api.post.mockResolvedValue({ scheduleStatus: 'STARTED' })
    const emit = vi.fn()
    const wrapper = mountPanel({ emit })
    await flushPromises()
    await wrapper.get('[aria-label="开始"]').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/task-schedules/schedule-1/events', { eventType: 'STARTED' }, expect.any(Object))
    await wrapper.findAll('.task-more button').find(button => button.text().includes('延期'))!.trigger('click')
    await wrapper.get('#panel-deferred').setValue('2026-09-09T09:00')
    await wrapper.get('[role="dialog"] .primary').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenLastCalledWith('/task-schedules/schedule-1/events', { eventType: 'DEFERRED', deferredStartAt: new Date('2026-09-09T09:00').toISOString() }, expect.any(Object))
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(emit).not.toHaveBeenCalledWith(expect.objectContaining({ type: 'close' }))
  })
})
