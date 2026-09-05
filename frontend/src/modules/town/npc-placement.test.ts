import { describe, expect, it } from 'vitest'
import { activeSlot, densityCap, placeFor, selectVisible, type PlaceableNpc, type TownLayout } from './npc-placement'
import type { NpcScheduleSlot } from './town-npc.types'

const LAYOUT: TownLayout = {
  academyDoorX: 1000,
  plotStartX: 1400,
  gymX: 1400,
  cafeX: 1736,
  parkX: 940,
  plazaMinX: 200,
  plazaMaxX: 800,
  worldWidth: 4000,
}

describe('placeFor', () => {
  it('maps every §6 place code to a finite world x', () => {
    const places = ['home', 'academy', 'gym', 'cafe', 'park', 'plaza', 'street'] as const
    for (const place of places) {
      const x = placeFor(place, LAYOUT)
      expect(Number.isFinite(x)).toBe(true)
    }
  })

  it('resolves each place using the given layout', () => {
    expect(placeFor('academy', LAYOUT)).toBe(1000)
    expect(placeFor('gym', LAYOUT)).toBe(1400)
    expect(placeFor('cafe', LAYOUT)).toBe(1736)
    expect(placeFor('park', LAYOUT)).toBe(940)
    expect(placeFor('home', LAYOUT)).toBe(1400)
    expect(placeFor('plaza', LAYOUT)).toBe(500) // midpoint of 200..800
    expect(placeFor('street', LAYOUT)).toBe(2000) // midpoint of world width
  })
})

describe('activeSlot', () => {
  it('returns null for an empty schedule', () => {
    expect(activeSlot([], 10)).toBeNull()
  })

  it('finds the slot covering a plain (non-wrapping) hour', () => {
    const schedule: NpcScheduleSlot[] = [
      { startHour: 6, endHour: 9, place: 'home', activity: 'idle' },
      { startHour: 9, endHour: 12, place: 'academy', activity: 'reading' },
    ]
    expect(activeSlot(schedule, 10)).toEqual(schedule[1])
    expect(activeSlot(schedule, 6)).toEqual(schedule[0])
  })

  it('returns null for a genuine hole in the schedule', () => {
    const schedule: NpcScheduleSlot[] = [
      { startHour: 0, endHour: 10, place: 'home', activity: 'idle' },
      { startHour: 14, endHour: 24, place: 'home', activity: 'idle' },
    ]
    expect(activeSlot(schedule, 12)).toBeNull()
  })

  it('handles a slot that wraps past midnight', () => {
    const schedule: NpcScheduleSlot[] = [
      { startHour: 22, endHour: 6, place: 'home', activity: 'idle' },
      { startHour: 6, endHour: 22, place: 'academy', activity: 'reading' },
    ]
    expect(activeSlot(schedule, 23)).toEqual(schedule[0])
    expect(activeSlot(schedule, 0)).toEqual(schedule[0])
    expect(activeSlot(schedule, 5)).toEqual(schedule[0])
    expect(activeSlot(schedule, 6)).toEqual(schedule[1])
    expect(activeSlot(schedule, 21)).toEqual(schedule[1])
  })

  it('normalizes an out-of-range hour (e.g. 25 -> 1, -1 -> 23)', () => {
    const schedule: NpcScheduleSlot[] = [{ startHour: 0, endHour: 24, place: 'home', activity: 'idle' }]
    expect(activeSlot(schedule, 25)).toEqual(schedule[0])
    expect(activeSlot(schedule, -1)).toEqual(schedule[0])
  })

  it('never matches a zero-length slot', () => {
    const schedule: NpcScheduleSlot[] = [{ startHour: 5, endHour: 5, place: 'home', activity: 'idle' }]
    expect(activeSlot(schedule, 5)).toBeNull()
  })
})

describe('densityCap', () => {
  it('never exceeds 12 at any hour of the day', () => {
    for (let hour = 0; hour < 24; hour++) {
      expect(densityCap(hour)).toBeLessThanOrEqual(12)
    }
  })

  it('is low in the deep night (2-3 per plan.md §2.4)', () => {
    expect(densityCap(2)).toBeLessThanOrEqual(3)
    expect(densityCap(3)).toBeLessThanOrEqual(3)
  })

  it('is a small crowd in the early morning (4-5 per plan.md §2.4)', () => {
    expect(densityCap(6)).toBe(4)
  })

  it('peaks at the midday café rush (10-12 per plan.md §2.4)', () => {
    expect(densityCap(10)).toBe(12)
    expect(densityCap(11)).toBe(12)
    expect(densityCap(12)).toBe(12)
  })

  it('normalizes out-of-range hours the same way as activeSlot', () => {
    expect(densityCap(26)).toBe(densityCap(2))
  })
})

function makeNpc(code: string, layer: 1 | 2 | 3, place: NpcScheduleSlot['place']): PlaceableNpc {
  return { code, layer, schedule: [{ startHour: 0, endHour: 24, place, activity: place === 'home' ? 'idle' : 'walking' }] }
}

describe('selectVisible', () => {
  it('excludes NPCs who are at home this hour', () => {
    const npcs = [makeNpc('A', 2, 'home'), makeNpc('B', 2, 'plaza')]
    const visible = selectVisible(npcs, 10, 12)
    expect(visible.map(n => n.code)).toEqual(['B'])
  })

  it('excludes an NPC with no active slot at all (a schedule hole)', () => {
    const holey: PlaceableNpc = { code: 'C', layer: 3, schedule: [{ startHour: 0, endHour: 10, place: 'plaza', activity: 'walking' }] }
    expect(selectVisible([holey], 15, 12)).toEqual([])
  })

  it('caps the result at the given cap', () => {
    const npcs = Array.from({ length: 5 }, (_, i) => makeNpc(`N${i}`, 3, 'plaza'))
    expect(selectVisible(npcs, 10, 3)).toHaveLength(3)
  })

  it('prioritises layer 1/2 over layer 3 when the cap forces a cut', () => {
    const npcs = [makeNpc('T1', 3, 'plaza'), makeNpc('T2', 3, 'plaza'), makeNpc('L2', 2, 'plaza'), makeNpc('L1', 1, 'plaza')]
    const visible = selectVisible(npcs, 10, 2)
    expect(visible.map(n => n.code).sort()).toEqual(['L1', 'L2'])
  })

  it('is deterministic and stable: repeated calls with the same input never reshuffle', () => {
    const npcs = [makeNpc('B', 3, 'plaza'), makeNpc('A', 3, 'plaza'), makeNpc('C', 2, 'plaza')]
    const first = selectVisible(npcs, 10, 12).map(n => n.code)
    const second = selectVisible(npcs, 10, 12).map(n => n.code)
    const third = selectVisible([...npcs].reverse(), 10, 12).map(n => n.code)
    expect(second).toEqual(first)
    expect(third).toEqual(first) // input order shouldn't matter either
  })

  it('returns an empty array for a cap of 0', () => {
    const npcs = [makeNpc('A', 2, 'plaza')]
    expect(selectVisible(npcs, 10, 0)).toEqual([])
  })
})
