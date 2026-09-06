import { describe, expect, it } from 'vitest'
import { MINUTES_PER_DAY, dayPlanFallback, floorMod, positionAt } from './day-plan'
import type { NpcDayPlan, NpcScheduleSlot } from './town-npc.types'

// A full-day plan with no holes: home -> (walk) -> gym -> (walk) -> academy -> (walk) -> home,
// mirroring CONTRACT-M7.md §1's sample shape (errands ∪ legs covers 0-1440).
const DAY_PLAN: NpcDayPlan = {
  date: '2026-09-06',
  errands: [
    { place: 'home', activity: 'idle', startMinute: 0, endMinute: 405, priority: 1, origin: 'RHYTHM' },
    { place: 'gym', activity: 'idle', startMinute: 420, endMinute: 480, priority: 2, origin: 'RHYTHM' },
    { place: 'academy', activity: 'reading', startMinute: 495, endMinute: 1080, priority: 1, origin: 'RHYTHM' },
    { place: 'home', activity: 'idle', startMinute: 1095, endMinute: 1440, priority: 1, origin: 'RHYTHM' },
  ],
  legs: [
    { fromPlace: 'home', toPlace: 'gym', departMinute: 405, arriveMinute: 420 },
    { fromPlace: 'gym', toPlace: 'academy', departMinute: 480, arriveMinute: 495 },
    { fromPlace: 'academy', toPlace: 'home', departMinute: 1080, arriveMinute: 1095 },
  ],
}

describe('floorMod', () => {
  it('passes in-range minutes through unchanged', () => {
    expect(floorMod(500, MINUTES_PER_DAY)).toBe(500)
  })

  it('wraps a minute >= 1440 back into range', () => {
    expect(floorMod(1440, MINUTES_PER_DAY)).toBe(0)
    expect(floorMod(1470, MINUTES_PER_DAY)).toBe(30)
    expect(floorMod(1440 * 2 + 10, MINUTES_PER_DAY)).toBe(10)
  })

  it('wraps a negative minute into range', () => {
    expect(floorMod(-30, MINUTES_PER_DAY)).toBe(1410)
    expect(floorMod(-1, MINUTES_PER_DAY)).toBe(1439)
  })
})

describe('positionAt', () => {
  it('returns AT for a minute inside an errand', () => {
    expect(positionAt(DAY_PLAN, 450)).toEqual({ kind: 'AT', place: 'gym', activity: 'idle' })
    expect(positionAt(DAY_PLAN, 0)).toEqual({ kind: 'AT', place: 'home', activity: 'idle' })
  })

  it('returns WALKING for a minute inside a leg, with progress in between', () => {
    // leg home->gym spans 405..420; halfway is minute 412.5
    const result = positionAt(DAY_PLAN, 412.5)
    expect(result).toEqual({ kind: 'WALKING', fromPlace: 'home', toPlace: 'gym', progress: 0.5, activity: 'walking' })
  })

  it('both AT and WALKING appear across a full day sweep', () => {
    const kinds = new Set<string>()
    for (let m = 0; m < MINUTES_PER_DAY; m += 5) {
      kinds.add(positionAt(DAY_PLAN, m).kind)
    }
    expect(kinds.has('AT')).toBe(true)
    expect(kinds.has('WALKING')).toBe(true)
  })

  it('computes a result for every minute of the day without ever returning undefined', () => {
    for (let m = 0; m < MINUTES_PER_DAY; m++) {
      const result = positionAt(DAY_PLAN, m)
      expect(result).toBeDefined()
      expect(['AT', 'WALKING']).toContain(result.kind)
    }
  })

  it('progress starts at (near) 0 right after departure and approaches 1 near arrival', () => {
    const justLeft = positionAt(DAY_PLAN, 405) as { kind: 'WALKING'; progress: number }
    expect(justLeft.kind).toBe('WALKING')
    expect(justLeft.progress).toBeGreaterThanOrEqual(0)
    expect(justLeft.progress).toBeLessThanOrEqual(1)

    const almostThere = positionAt(DAY_PLAN, 419.999) as { kind: 'WALKING'; progress: number }
    expect(almostThere.progress).toBeGreaterThan(0.9)
    expect(almostThere.progress).toBeLessThanOrEqual(1)
  })

  it('normalizes an out-of-range minute the same way as floorMod (a full day later is identical)', () => {
    expect(positionAt(DAY_PLAN, 450)).toEqual(positionAt(DAY_PLAN, 450 + MINUTES_PER_DAY))
    expect(positionAt(DAY_PLAN, 450)).toEqual(positionAt(DAY_PLAN, 450 - MINUTES_PER_DAY))
  })

  it('is stable: repeated calls on the same dayPlan/minute give the same result', () => {
    const first = positionAt(DAY_PLAN, 700)
    const second = positionAt(DAY_PLAN, 700)
    expect(second).toEqual(first)
    // Also never mutates the input.
    expect(DAY_PLAN.errands).toHaveLength(4)
  })

  it('falls back to the last errand rather than undefined when the plan has a genuine hole', () => {
    const holey: NpcDayPlan = {
      date: '2026-09-06',
      errands: [{ place: 'cafe', activity: 'sit', startMinute: 0, endMinute: 100, priority: 1, origin: 'RHYTHM' }],
      legs: [],
    }
    expect(positionAt(holey, 500)).toEqual({ kind: 'AT', place: 'cafe', activity: 'sit' })
  })

  it('a zero-length leg (depart === arrive) matches no minute and falls back rather than NaN', () => {
    // Half-open interval semantics mean depart===arrive is an empty window — no minute is ever
    // "inside" it. That's fine: it just means this minute falls through to the AT fallback
    // instead of crashing or producing a NaN/Infinity progress.
    const zeroLegHole: NpcDayPlan = {
      date: '2026-09-06',
      errands: [{ place: 'home', activity: 'idle', startMinute: 0, endMinute: 99, priority: 1, origin: 'RHYTHM' }],
      legs: [{ fromPlace: 'home', toPlace: 'gym', departMinute: 99, arriveMinute: 99 }],
    }
    const result = positionAt(zeroLegHole, 99)
    expect(result.kind).toBe('AT')
    expect(Number.isNaN((result as { progress?: number }).progress)).toBe(false)
  })

  it('falls back to home/idle for a completely empty plan', () => {
    const empty: NpcDayPlan = { date: '2026-09-06', errands: [], legs: [] }
    expect(positionAt(empty, 500)).toEqual({ kind: 'AT', place: 'home', activity: 'idle' })
  })
})

describe('dayPlanFallback', () => {
  const SCHEDULE: NpcScheduleSlot[] = [
    { startHour: 0, endHour: 9, place: 'home', activity: 'idle' },
    { startHour: 9, endHour: 12, place: 'academy', activity: 'reading' },
    { startHour: 12, endHour: 24, place: 'home', activity: 'idle' },
  ]

  it('converts every slot into an equivalent errand (hours -> minutes) with empty legs', () => {
    const plan = dayPlanFallback(SCHEDULE, '2026-09-06')
    expect(plan.date).toBe('2026-09-06')
    expect(plan.legs).toEqual([])
    expect(plan.errands).toEqual([
      { place: 'home', activity: 'idle', startMinute: 0, endMinute: 540, priority: 1, origin: 'RHYTHM' },
      { place: 'academy', activity: 'reading', startMinute: 540, endMinute: 720, priority: 1, origin: 'RHYTHM' },
      { place: 'home', activity: 'idle', startMinute: 720, endMinute: 1440, priority: 1, origin: 'RHYTHM' },
    ])
  })

  it('drops zero-length slots, matching npc-placement.ts activeSlot semantics', () => {
    const withZero: NpcScheduleSlot[] = [...SCHEDULE, { startHour: 5, endHour: 5, place: 'gym', activity: 'idle' }]
    expect(dayPlanFallback(withZero).errands).toHaveLength(3)
  })

  it('produces a dayPlan that positionAt can consume for the whole day with no crash', () => {
    const plan = dayPlanFallback(SCHEDULE)
    for (let m = 0; m < MINUTES_PER_DAY; m += 30) {
      expect(positionAt(plan, m).kind).toBe('AT') // no legs, so it's always AT somewhere
    }
  })
})
