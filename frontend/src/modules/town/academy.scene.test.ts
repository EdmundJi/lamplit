import { describe, expect, it } from 'vitest'
import { academyResidents } from './academy.scene'
import type { TownResident } from './town.types'

// Phaser cannot run in jsdom, so this only covers the pure academyResidents() mapping;
// createAcademyScene() is exercised visually (see the task 7 report / screenshot).

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

describe('academyResidents', () => {
  it('puts residents who finished something today in the reading state', () => {
    const list = academyResidents({ residents: [resident({ publicId: 'a', todayDone: 1 })] })
    expect(list).toEqual([{ publicId: 'a', displayName: '小明', isSelf: false, characterSheet: expect.any(Number), state: 'reading' }])
  })

  it('puts residents mid-task (started but not done) in the phone state', () => {
    const list = academyResidents({ residents: [resident({ publicId: 'a', todayStarted: 1 })] })
    expect(list[0].state).toBe('phone')
  })

  it('prefers reading over phone when a resident has both done and started tasks today', () => {
    const list = academyResidents({ residents: [resident({ publicId: 'a', todayDone: 1, todayStarted: 1 })] })
    expect(list[0].state).toBe('reading')
  })

  it('excludes neighbours with nothing going on today', () => {
    const list = academyResidents({ residents: [resident({ publicId: 'a' })] })
    expect(list).toEqual([])
  })

  it('always includes self, idle when there is nothing to show today', () => {
    const list = academyResidents({ residents: [resident({ publicId: 'me', isSelf: true })] })
    expect(list).toEqual([{ publicId: 'me', displayName: '小明', isSelf: true, characterSheet: expect.any(Number), state: 'idle' }])
  })

  it('includes self as reading rather than idle when self also has progress today', () => {
    const list = academyResidents({ residents: [resident({ publicId: 'me', isSelf: true, todayDone: 2 })] })
    expect(list[0].state).toBe('reading')
  })

  it('derives characterSheet deterministically from publicId, same formula as town.engine.ts', () => {
    const a = academyResidents({ residents: [resident({ publicId: 'stable-id', todayDone: 1 })] })
    const b = academyResidents({ residents: [resident({ publicId: 'stable-id', todayDone: 1 })] })
    expect(a[0].characterSheet).toBe(b[0].characterSheet)
    expect(a[0].characterSheet).toBeGreaterThanOrEqual(1)
    expect(a[0].characterSheet).toBeLessThanOrEqual(20)
  })

  it('maps a mixed roster in order: done, started, resting (excluded), self (idle)', () => {
    const list = academyResidents({
      residents: [
        resident({ publicId: 'reader', displayName: '小红', todayDone: 1 }),
        resident({ publicId: 'phoner', displayName: '小刚', todayStarted: 1 }),
        resident({ publicId: 'resting', displayName: '小李' }),
        resident({ publicId: 'me', displayName: '我', isSelf: true }),
      ],
    })
    expect(list.map(item => item.publicId)).toEqual(['reader', 'phoner', 'me'])
    expect(list.map(item => item.state)).toEqual(['reading', 'phone', 'idle'])
  })
})
