import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { diffCelebrations, dominantDimension, residentFromFriend, summarizeTasks, useTownStore } from './town.store'
import type { TownModel } from './town.types'

const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const notFound = { status: 404, code: 'NOT_FOUND', message: 'not found' }

describe('town store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.get.mockReset()
  })

  it('summarizes today\'s schedule into planned / started / done counts', () => {
    expect(summarizeTasks([{ status: 'PLANNED' }, { status: 'IN_PROGRESS' }, { status: 'DONE' }, { status: 'SKIPPED' }]))
      .toEqual({ todayPlanned: 3, todayDone: 1, todayStarted: 1 })
  })

  it('picks the dimension with the most experience', () => {
    expect(dominantDimension([{ code: 'HEALTH', experience: 10 }, { code: 'KNOWLEDGE', experience: 40 }])).toBe('KNOWLEDGE')
    expect(dominantDimension([{ code: 'KNOWLEDGE', experience: 0 }])).toBeNull()
  })

  it('loads GET /town and maps residents, schedules and serverTime', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path === '/town') {
        return {
          localDate: '2026-09-05',
          serverTime: '2026-09-05T02:10:00Z',
          unread: 2,
          soloGrowth: false,
          residents: [
            {
              publicId: 'me', displayName: '育德', level: 5, totalExperience: 520, dominantDimension: 'KNOWLEDGE',
              longestStreak: 12, title: '晨型人', self: true, timezone: 'Asia/Shanghai',
              schedules: [{ publicId: 's1', title: '背单词 20 分钟', status: 'PLANNED', roleCode: 'STUDENT', roleName: '学生', plannedStartAt: '2026-09-05T11:00:00Z', plannedEndAt: '2026-09-05T11:20:00Z', estimatedMinutes: 20, difficulty: 2 }],
            },
            {
              publicId: 'f1', displayName: '阿强', level: 2, totalExperience: 60, dominantDimension: null,
              longestStreak: 0, self: false, timezone: 'Asia/Shanghai', schedules: [],
            },
          ],
        }
      }
      throw new Error(`unexpected ${path}`)
    })
    const store = useTownStore()
    await store.load()
    expect(store.error).toBe('')
    expect(store.model?.serverTime).toBe('2026-09-05T02:10:00Z')
    expect(store.model?.residents.map(item => item.publicId)).toEqual(['me', 'f1'])
    expect(store.model?.residents[0]).toMatchObject({ isSelf: true, title: '晨型人', todayPlanned: 1, todayDone: 0 })
    expect(store.model?.residents[0].schedules).toHaveLength(1)
    expect(store.model?.residents[1]).toMatchObject({ isSelf: false, title: null })
  })

  it('falls back to the legacy multi-request loader when GET /town 404s', async () => {
    api.get.mockImplementation(async (path: string) => {
      if (path === '/town') throw notFound
      if (path === '/me/profile') return { publicId: 'me', displayName: '我', overallLevel: 4, totalExperience: 320, longestStreak: 9, equippedTitle: { name: '晨型人' } }
      if (path === '/insights/attributes') return { attributes: [{ code: 'HEALTH', experience: 90 }] }
      if (path.startsWith('/task-schedules')) return [{ publicId: 't1', taskTitle: '跑步', status: 'DONE', plannedStartAt: '2026-09-05T01:00:00Z' }, { publicId: 't2', taskTitle: '读书', status: 'PLANNED', plannedStartAt: '2026-09-05T03:00:00Z' }]
      if (path === '/friends') return { friends: [{ publicId: 'f1', status: 'ACCEPTED' }, { publicId: 'f2', status: 'PENDING' }], incoming: [], outgoing: [] }
      if (path === '/friends/unread-summary') return { totalUnread: 3, kind: 'single', publicId: 'f1', displayName: '朋友' }
      if (path === '/friends/f1') return { publicId: 'f1', displayName: '朋友', overallLevel: 2, totalExperience: 40, longestStreak: 2, attributes: [], todayTasks: [] }
      throw new Error(`unexpected ${path}`)
    })
    const store = useTownStore()
    await store.load()
    expect(store.error).toBe('')
    expect(store.model?.residents.map(item => item.publicId)).toEqual(['me', 'f1'])
    expect(store.model?.residents[0]).toMatchObject({ isSelf: true, dominantDimension: 'HEALTH', todayDone: 1, todayPlanned: 2, longestStreak: 9, title: '晨型人' })
    expect(store.model?.residents[0].schedules.map(item => item.title)).toEqual(['跑步', '读书'])
    expect(store.model?.unread).toBe(3)
    expect(residentFromFriend({ publicId: 'x', displayName: 'x', overallLevel: 1, totalExperience: 0, attributes: [], todayTasks: [{ status: 'DONE' }] } as never).todayDone).toBe(1)
  })

  it('surfaces a real error message when GET /town fails for a reason other than 404', async () => {
    api.get.mockRejectedValue({ status: 500, code: 'SERVER_ERROR', message: '出错了' })
    const store = useTownStore()
    await store.load()
    expect(store.error).toBe('出错了')
    expect(store.model).toBeNull()
  })

  describe('diffCelebrations', () => {
    const model = (schedules: { publicId: string; status: string }[]): TownModel => ({
      localDate: '2026-09-05',
      serverTime: '2026-09-05T02:10:00Z',
      unread: 0,
      soloGrowth: false,
      residents: [{
        publicId: 'me', displayName: '育德', level: 1, totalExperience: 0, dominantDimension: null,
        longestStreak: 0, todayPlanned: 0, todayDone: 0, todayStarted: 0, isSelf: true, title: null, timezone: 'Asia/Shanghai',
        schedules: schedules.map(item => ({ ...item, title: '背单词', roleCode: null, plannedStartAt: null })) as never,
      }],
    })

    it('produces nothing on the first load (no previous model)', () => {
      expect(diffCelebrations(null, model([{ publicId: 's1', status: 'DONE' }]))).toEqual([])
    })

    it('celebrates a schedule that flips to DONE or PARTIAL since the previous poll', () => {
      const previous = model([{ publicId: 's1', status: 'IN_PROGRESS' }, { publicId: 's2', status: 'PLANNED' }])
      const next = model([{ publicId: 's1', status: 'DONE' }, { publicId: 's2', status: 'PARTIAL' }])
      expect(diffCelebrations(previous, next)).toEqual([
        { publicId: 'me', scheduleId: 's1', title: '背单词' },
        { publicId: 'me', scheduleId: 's2', title: '背单词' },
      ])
    })

    it('does not re-celebrate a schedule that was already DONE/PARTIAL', () => {
      const previous = model([{ publicId: 's1', status: 'DONE' }])
      const next = model([{ publicId: 's1', status: 'DONE' }])
      expect(diffCelebrations(previous, next)).toEqual([])
    })
  })

  describe('polling', () => {
    beforeEach(() => { vi.useFakeTimers() })
    afterEach(() => { vi.useRealTimers() })

    it('polls /town every 30s and skips the request while the tab is hidden', async () => {
      api.get.mockImplementation(async (path: string) => {
        if (path === '/town') return { localDate: '2026-09-05', serverTime: '2026-09-05T00:00:00Z', unread: 0, soloGrowth: false, residents: [] }
        throw new Error(`unexpected ${path}`)
      })
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
      const store = useTownStore()
      store.startPolling()
      await vi.advanceTimersByTimeAsync(30_000)
      expect(api.get).not.toHaveBeenCalled()

      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
      await vi.advanceTimersByTimeAsync(30_000)
      expect(api.get).toHaveBeenCalledWith('/town')

      store.stopPolling()
      api.get.mockClear()
      await vi.advanceTimersByTimeAsync(60_000)
      expect(api.get).not.toHaveBeenCalled()
    })
  })
})
