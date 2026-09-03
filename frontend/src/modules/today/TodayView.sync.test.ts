import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TodayView from './TodayView.vue'
import { DATA_CHANGED_EVENT } from '../../shared/data-sync'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const task = {
  publicId: 'schedule-1',
  taskTitle: '复习一章',
  plannedStartAt: '2026-08-04T19:00:00+08:00',
  status: 'PLANNED',
}

describe('Today data synchronization', () => {
  beforeEach(() => {
    api.get.mockReset().mockImplementation((path: string) => {
      if (path.startsWith('/task-schedules')) return Promise.resolve([{ ...task }])
      if (path === '/daily-status') return Promise.resolve(null)
      return Promise.resolve([])
    })
    api.post.mockReset()
  })

  it('reloads today when goals or tasks change elsewhere', async () => {
    const wrapper = mount(TodayView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()
    api.get.mockClear()

    window.dispatchEvent(new CustomEvent(DATA_CHANGED_EVENT, { detail: { areas: ['goals'] } }))
    await flushPromises()

    expect(api.get).toHaveBeenCalledWith(expect.stringMatching(/^\/task-schedules\?localDate=/))
    expect(api.get).toHaveBeenCalledWith('/goals?status=ACTIVE')
    wrapper.unmount()
  })
})
