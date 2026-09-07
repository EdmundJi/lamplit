import { describe, expect, it, vi } from 'vitest'
import { MINUTES_PER_DAY, positionAt } from './day-plan'
import { PASSING_GREETING_PAUSE_MS, shouldPauseForGreeting, shouldSeekRoadsideShelter } from './roadside-episodes'
import type { NpcDayPlan } from './town-npc.types'

describe('shouldPauseForGreeting', () => {
  it('is true when the random draw is below the chance threshold', () => {
    expect(shouldPauseForGreeting(() => 0, 0.15)).toBe(true)
  })

  it('is false when the random draw is at or above the chance threshold', () => {
    expect(shouldPauseForGreeting(() => 0.99, 0.15)).toBe(false)
    expect(shouldPauseForGreeting(() => 0.15, 0.15)).toBe(false)
  })

  it('defaults to Math.random (a real runtime random source) when no random source is given', () => {
    const spy = vi.spyOn(Math, 'random').mockReturnValue(0.01)
    try {
      expect(shouldPauseForGreeting()).toBe(true) // 0.01 < default chance 0.15
    } finally {
      spy.mockRestore()
    }
  })
})

describe('shouldSeekRoadsideShelter', () => {
  it('is always false on clear or snowy weather, regardless of the random draw', () => {
    expect(shouldSeekRoadsideShelter('clear', () => 0)).toBe(false)
    expect(shouldSeekRoadsideShelter('snow', () => 0)).toBe(false)
  })

  it('does not even call the random source on non-rain weather', () => {
    const random = vi.fn(() => 0)
    shouldSeekRoadsideShelter('clear', random)
    expect(random).not.toHaveBeenCalled()
  })

  it('on rain, follows the random draw against the chance threshold', () => {
    expect(shouldSeekRoadsideShelter('rain', () => 0, 0.5)).toBe(true)
    expect(shouldSeekRoadsideShelter('rain', () => 0.9, 0.5)).toBe(false)
  })

  it('defaults to Math.random when no random source is given', () => {
    const spy = vi.spyOn(Math, 'random').mockReturnValue(0.01)
    try {
      expect(shouldSeekRoadsideShelter('rain')).toBe(true) // 0.01 < default chance 0.5
    } finally {
      spy.mockRestore()
    }
  })
})

describe('PASSING_GREETING_PAUSE_MS', () => {
  it('is a positive, finite duration', () => {
    expect(PASSING_GREETING_PAUSE_MS).toBeGreaterThan(0)
    expect(Number.isFinite(PASSING_GREETING_PAUSE_MS)).toBe(true)
  })
})

describe('isolation from positionAt / the encounter sequence (M7-9 hard constraint)', () => {
  const DAY_PLAN: NpcDayPlan = {
    date: '2026-09-06',
    errands: [
      { place: 'home', activity: 'idle', startMinute: 0, endMinute: 405, priority: 1, origin: 'RHYTHM' },
      { place: 'gym', activity: 'idle', startMinute: 420, endMinute: 480, priority: 2, origin: 'RHYTHM' },
      { place: 'home', activity: 'idle', startMinute: 495, endMinute: 1440, priority: 1, origin: 'RHYTHM' },
    ],
    legs: [{ fromPlace: 'home', toPlace: 'gym', departMinute: 405, arriveMinute: 420 }, { fromPlace: 'gym', toPlace: 'home', departMinute: 480, arriveMinute: 495 }],
  }

  it('calling the episode judges any number of times never changes what positionAt reports', () => {
    const snapshotBefore = Array.from({ length: MINUTES_PER_DAY }, (_, m) => positionAt(DAY_PLAN, m))

    // Hammer both episode judges with real runtime randomness, exactly as the render loop would
    // every frame — this must not leak into, or be influenced by, dayPlan math in any way.
    for (let i = 0; i < 2000; i++) {
      shouldPauseForGreeting()
      shouldSeekRoadsideShelter('rain')
    }

    const snapshotAfter = Array.from({ length: MINUTES_PER_DAY }, (_, m) => positionAt(DAY_PLAN, m))
    expect(snapshotAfter).toEqual(snapshotBefore)
  })

  it('the episode judges never mutate the dayPlan object they might be called alongside', () => {
    const before = JSON.parse(JSON.stringify(DAY_PLAN))
    for (let i = 0; i < 100; i++) {
      shouldPauseForGreeting()
      shouldSeekRoadsideShelter('rain')
    }
    expect(DAY_PLAN).toEqual(before)
  })
})
