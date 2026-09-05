import { describe, expect, it } from 'vitest'
import {
  activityFor, actionForRole, buildBlueprint, floorsForLevel, groundFloorFor, hashString,
  paletteFor, roofPropsFor, scheduleWindow, venueForRole, whereShouldBe,
} from './building-kit'
import type { ScheduleItem, TownResident } from './town.types'

const resident = (overrides: Partial<TownResident> = {}): TownResident => ({
  publicId: 'user-1',
  displayName: '小明',
  level: 1,
  totalExperience: 0,
  dominantDimension: 'KNOWLEDGE',
  longestStreak: 0,
  todayPlanned: 0,
  todayDone: 0,
  todayStarted: 0,
  isSelf: false,
  title: null,
  schedules: [],
  timezone: 'Asia/Shanghai',
  ...overrides,
})

const schedule = (overrides: Partial<ScheduleItem> = {}): ScheduleItem => ({
  publicId: 'sched-1',
  title: '背单词 20 分钟',
  status: 'PLANNED',
  roleCode: 'STUDENT',
  roleName: '学生',
  plannedStartAt: '2026-09-05T11:00:00Z',
  plannedEndAt: '2026-09-05T11:20:00Z',
  estimatedMinutes: 20,
  difficulty: 2,
  ...overrides,
})

describe('building kit', () => {
  it('hashes deterministically and spreads palettes', () => {
    expect(hashString('abc')).toBe(hashString('abc'))
    expect(hashString('abc')).not.toBe(hashString('abd'))
    expect([0, 1, 2]).toContain(paletteFor('anyone'))
  })

  it('adds one floor every two levels, four at most', () => {
    expect(floorsForLevel(1)).toBe(0)
    expect(floorsForLevel(2)).toBe(0)
    expect(floorsForLevel(3)).toBe(1)
    expect(floorsForLevel(7)).toBe(3)
    expect(floorsForLevel(40)).toBe(4)
  })

  it('derives activity from today\'s schedule', () => {
    expect(activityFor({ todayPlanned: 0, todayDone: 0, todayStarted: 0 })).toBe('resting')
    expect(activityFor({ todayPlanned: 2, todayDone: 0, todayStarted: 0 })).toBe('planned')
    expect(activityFor({ todayPlanned: 2, todayDone: 0, todayStarted: 1 })).toBe('working')
    expect(activityFor({ todayPlanned: 2, todayDone: 1, todayStarted: 0 })).toBe('done')
  })

  it('picks storefronts by dimension and closes the shutters when idle', () => {
    expect(groundFloorFor('HEALTH', 0, true)).toBe('gym_1')
    expect(groundFloorFor('HEALTH', 0, false)).toBe('gym_2')
    expect(groundFloorFor('KNOWLEDGE', 2, true)).toBe('shop_25')
    expect(groundFloorFor('RELATIONSHIP', 1, true)).toBe('icecream_3')
    expect(groundFloorFor(null, 1, true)).toBe('condo_5')
  })

  it('unlocks roof props with streaks', () => {
    expect(roofPropsFor(0)).toEqual([])
    expect(roofPropsFor(7)).toEqual(['roofprop_3', 'roofprop_8'])
    expect(roofPropsFor(30)).toHaveLength(3)
  })

  it('keeps every piece of a blueprint inside the same palette', () => {
    const blueprint = buildBlueprint(resident({ level: 6, longestStreak: 10, todayPlanned: 1, todayDone: 1 }))
    const palette = blueprint.palette
    expect(blueprint.middles).toHaveLength(2)
    for (const middle of blueprint.middles) {
      const index = Number(middle.replace('middle_', ''))
      expect(Math.floor((index - 1) / 6)).toBe(palette)
    }
    expect(blueprint.roof).toBe(`roof_${palette * 2 + 1}`)
    expect(blueprint.open).toBe(true)
  })

  describe('场地映射', () => {
    it('sends students and workers to the academy door', () => {
      expect(venueForRole('STUDENT')).toBe('academy')
      expect(venueForRole('WORKER')).toBe('academy')
      expect(actionForRole('STUDENT')).toBe('read')
      expect(actionForRole('WORKER')).toBe('phone')
    })

    it('sends fitness users to the court and emotional-support users to the park, both idle', () => {
      expect(venueForRole('FITNESS_USER')).toBe('court')
      expect(actionForRole('FITNESS_USER')).toBe('idle')
      expect(venueForRole('EMOTIONAL_SUPPORT_USER')).toBe('park')
      expect(actionForRole('EMOTIONAL_SUPPORT_USER')).toBe('idle')
    })

    it('sends unknown or missing roles home, idle', () => {
      expect(venueForRole(null)).toBe('home')
      expect(venueForRole(undefined)).toBe('home')
      expect(venueForRole('SOMETHING_ELSE')).toBe('home')
      expect(actionForRole(null)).toBe('idle')
    })
  })

  describe('scheduleWindow', () => {
    it('uses plannedStartAt/plannedEndAt when both are present', () => {
      const window = scheduleWindow(schedule())
      expect(window?.start).toBe(new Date('2026-09-05T11:00:00Z').getTime())
      expect(window?.end).toBe(new Date('2026-09-05T11:20:00Z').getTime())
    })

    it('falls back to estimatedMinutes (at least 30) when plannedEndAt is missing', () => {
      const short = scheduleWindow(schedule({ plannedEndAt: undefined, estimatedMinutes: 20 }))
      expect(short?.end).toBe(short!.start + 30 * 60_000)

      const long = scheduleWindow(schedule({ plannedEndAt: undefined, estimatedMinutes: 90 }))
      expect(long?.end).toBe(long!.start + 90 * 60_000)
    })

    it('lingers for 2 hours after the window ends', () => {
      const window = scheduleWindow(schedule())
      expect(window?.lingerEnd).toBe(window!.end + 2 * 60 * 60_000)
    })

    it('returns null without a plannedStartAt', () => {
      expect(scheduleWindow(schedule({ plannedStartAt: null }))).toBeNull()
    })

    it('shifts the window by the server/local clock offset', () => {
      const offset = 5 * 60_000 // server is 5 minutes ahead of the local clock
      const window = scheduleWindow(schedule(), offset)
      expect(window?.start).toBe(new Date('2026-09-05T11:00:00Z').getTime() - offset)
    })
  })

  describe('whereShouldBe', () => {
    it('sends a resident to the venue while a PLANNED/IN_PROGRESS task is in its window', () => {
      const item = schedule({ status: 'IN_PROGRESS', roleCode: 'STUDENT' })
      const now = new Date('2026-09-05T11:10:00Z').getTime()
      expect(whereShouldBe(resident({ schedules: [item] }), now)).toEqual({ venue: 'academy', action: 'read', schedule: item })
    })

    it('keeps them home before the window starts and after it ends (no linger yet, wrong status)', () => {
      const item = schedule({ status: 'PLANNED' })
      const before = new Date('2026-09-05T10:00:00Z').getTime()
      const after = new Date('2026-09-05T13:00:00Z').getTime()
      expect(whereShouldBe(resident({ schedules: [item] }), before)).toEqual({ venue: 'home', action: 'idle' })
      expect(whereShouldBe(resident({ schedules: [item] }), after)).toEqual({ venue: 'home', action: 'idle' })
    })

    it('lingers at the venue for 2 hours after a DONE/PARTIAL task ends, then goes home', () => {
      const item = schedule({ status: 'DONE', roleCode: 'FITNESS_USER' })
      const duringLinger = new Date('2026-09-05T12:30:00Z').getTime() // 70 min after the 11:20 end
      const afterLinger = new Date('2026-09-05T13:30:00Z').getTime() // 130 min after
      expect(whereShouldBe(resident({ schedules: [item] }), duringLinger)).toEqual({ venue: 'court', action: 'idle', schedule: item })
      expect(whereShouldBe(resident({ schedules: [item] }), afterLinger)).toEqual({ venue: 'home', action: 'idle' })
    })

    it('calibrates against serverOffsetMs', () => {
      const item = schedule({ status: 'IN_PROGRESS' }) // absolute window 11:00-11:20 UTC
      const localNow = new Date('2026-09-05T11:05:00Z').getTime()
      // With no offset, 11:05 falls inside the 11:00-11:20 window.
      expect(whereShouldBe(resident({ schedules: [item] }), localNow, 0)).toMatchObject({ venue: 'academy' })
      // A server clock 30 minutes ahead shifts the window (in local-clock terms) to 10:30-10:50,
      // which the same local 11:05 now falls after.
      const offset = 30 * 60_000
      expect(whereShouldBe(resident({ schedules: [item] }), localNow, offset)).toEqual({ venue: 'home', action: 'idle' })
    })

    it('falls back to home/idle with no schedules', () => {
      expect(whereShouldBe(resident(), Date.now())).toEqual({ venue: 'home', action: 'idle' })
    })
  })
})
