import { effectScope } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { isDailyLimitError, useTodayLogic } from './today.logic'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

/**
 * useTodayLogic calls onMounted/onBeforeUnmount, which are no-ops outside a component instance.
 * A bare effect scope still hosts the reactive state and the onDataChanged subscription, so we
 * only need to trigger the initial load ourselves.
 */
async function setup(options?: Parameters<typeof useTodayLogic>[0]) {
  const scope = effectScope()
  const logic = scope.run(() => useTodayLogic(options))!
  await logic.load()
  return { logic, dispose: () => scope.stop() }
}

describe('useTodayLogic', () => {
  beforeEach(() => {
    api.get.mockReset().mockImplementation((path: string) => Promise.resolve(path.startsWith('/task-schedules')
      ? [{ publicId: 'schedule-1', taskTitle: '复习一章', plannedStartAt: new Date().toISOString(), status: 'PLANNED' }]
      : []))
    api.post.mockReset()
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('11111111-1111-4111-8111-111111111111')
  })

  it('reuses the same idempotency key for repeated calls on the same task/event pair', async () => {
    const { logic, dispose } = await setup()
    await flushPromises()
    let resolve!: (value: unknown) => void
    api.post.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const task = logic.tasks.value[0]

    void logic.act(task, 'COMPLETED')
    void logic.act(task, 'COMPLETED')
    await flushPromises()

    expect(api.post).toHaveBeenCalledTimes(1)
    expect(api.post).toHaveBeenCalledWith(
      '/task-schedules/schedule-1/events',
      { eventType: 'COMPLETED' },
      { 'Idempotency-Key': '11111111-1111-4111-8111-111111111111' },
    )
    resolve({ scheduleStatus: 'DONE', eventPublicId: 'event-1' })
    await flushPromises()

    // a later, distinct call for the same task/event mints a fresh key instead of reusing a stale one
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('22222222-2222-4222-8222-222222222222')
    api.post.mockResolvedValueOnce({ scheduleStatus: 'DONE', eventPublicId: 'event-2' })
    await logic.act(task, 'COMPLETED')
    expect(api.post).toHaveBeenLastCalledWith(
      '/task-schedules/schedule-1/events',
      { eventType: 'COMPLETED' },
      { 'Idempotency-Key': '22222222-2222-4222-8222-222222222222' },
    )
    dispose()
  })

  it('allows completion beyond four tasks', async () => {
    api.get.mockImplementation((path: string) => Promise.resolve(path.startsWith('/task-schedules')
      ? [
          ...Array.from({ length: 4 }, (_, index) => ({
            publicId: `done-${index}`, taskTitle: `任务 ${index}`, plannedStartAt: new Date().toISOString(), status: 'DONE',
          })),
          { publicId: 'schedule-extra', taskTitle: '额外任务', plannedStartAt: new Date().toISOString(), status: 'PLANNED' },
        ]
      : []))
    const { logic, dispose } = await setup()
    await flushPromises()

    api.post.mockResolvedValueOnce({ scheduleStatus: 'DONE', eventPublicId: 'extra-event' })
    const extra = logic.tasks.value.find(task => task.publicId === 'schedule-extra')!
    await logic.act(extra, 'COMPLETED')
    expect(api.post).toHaveBeenCalled()
    expect(extra.status).toBe('DONE')
    dispose()
  })

  it('recognizes the backend quota error code for a friendly message', () => {
    expect(isDailyLimitError({ code: 'DAILY_TASK_COMPLETION_LIMIT_REACHED' })).toBe(true)
    expect(isDailyLimitError({ code: 'OTHER' })).toBe(false)
    expect(isDailyLimitError(undefined)).toBe(false)
  })

  it('invokes onCelebrate only when a task is completed', async () => {
    const onCelebrate = vi.fn()
    const { logic, dispose } = await setup({ onCelebrate })
    await flushPromises()
    api.post.mockResolvedValueOnce({ scheduleStatus: 'STARTED', eventPublicId: 'event-started' })
    await logic.act(logic.tasks.value[0], 'STARTED')
    expect(onCelebrate).not.toHaveBeenCalled()

    api.post.mockResolvedValueOnce({ scheduleStatus: 'DONE', eventPublicId: 'event-done' })
    await logic.act(logic.tasks.value[0], 'COMPLETED')
    expect(onCelebrate).toHaveBeenCalledWith('schedule-1')
    dispose()
  })
})
