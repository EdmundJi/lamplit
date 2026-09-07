import { describe, expect, it } from 'vitest'
import { DEFAULT_HOLD_MS, DEFAULT_PAN_MS, DEFAULT_ZOOM, buildItinerary, nextLeg, type ObservationLeg } from './observation-mode'

describe('buildItinerary', () => {
  it('returns an empty itinerary for no points', () => {
    expect(buildItinerary([])).toEqual([])
  })

  it('fills in default hold/pan/zoom for every point', () => {
    const itinerary = buildItinerary([{ x: 10, y: 20 }, { x: 30, y: 40 }])
    expect(itinerary).toEqual([
      { x: 10, y: 20, zoom: DEFAULT_ZOOM, holdMs: DEFAULT_HOLD_MS, panMs: DEFAULT_PAN_MS },
      { x: 30, y: 40, zoom: DEFAULT_ZOOM, holdMs: DEFAULT_HOLD_MS, panMs: DEFAULT_PAN_MS },
    ])
  })

  it('lets a per-point zoom override the config default', () => {
    const itinerary = buildItinerary([{ x: 0, y: 0, zoom: 2 }], { defaultZoom: 1 })
    expect(itinerary[0].zoom).toBe(2)
  })

  it('honours a custom hold/pan config', () => {
    const itinerary = buildItinerary([{ x: 0, y: 0 }], { holdMs: 1000, panMs: 500 })
    expect(itinerary[0]).toMatchObject({ holdMs: 1000, panMs: 500 })
  })

  it('does not mutate the input points array', () => {
    const points = [{ x: 1, y: 2 }]
    const frozen = JSON.parse(JSON.stringify(points))
    buildItinerary(points)
    expect(points).toEqual(frozen)
  })
})

describe('nextLeg', () => {
  it('returns null for an empty itinerary', () => {
    expect(nextLeg([], 0)).toBeNull()
  })

  it('a single-point itinerary always holds at that point, never pans', () => {
    const itinerary = buildItinerary([{ x: 5, y: 5 }])
    for (const elapsed of [0, 1, 3000, 999_999]) {
      const pos = nextLeg(itinerary, elapsed)
      expect(pos?.legIndex).toBe(0)
      expect(pos?.phase).toBe('holding')
      expect(pos?.leg).toEqual(itinerary[0])
    }
  })

  it('does not divide by zero for a single point with holdMs 0', () => {
    const itinerary = buildItinerary([{ x: 0, y: 0 }], { holdMs: 0 })
    const pos = nextLeg(itinerary, 500)
    expect(pos?.progress).toBe(1)
    expect(Number.isNaN(pos?.progress)).toBe(false)
  })

  it('starts panning to leg 0 at elapsed 0', () => {
    const itinerary = buildItinerary([{ x: 0, y: 0 }, { x: 100, y: 0 }], { holdMs: 1000, panMs: 500 })
    const pos = nextLeg(itinerary, 0)
    expect(pos).toEqual({ legIndex: 0, leg: itinerary[0], phase: 'panning', progress: 0 })
  })

  it('reports pan progress partway through the glide', () => {
    const itinerary = buildItinerary([{ x: 0, y: 0 }, { x: 100, y: 0 }], { holdMs: 1000, panMs: 500 })
    const pos = nextLeg(itinerary, 250)
    expect(pos?.legIndex).toBe(0)
    expect(pos?.phase).toBe('panning')
    expect(pos?.progress).toBeCloseTo(0.5)
  })

  it('switches to holding once the pan completes', () => {
    const itinerary = buildItinerary([{ x: 0, y: 0 }, { x: 100, y: 0 }], { holdMs: 1000, panMs: 500 })
    const pos = nextLeg(itinerary, 500)
    expect(pos?.legIndex).toBe(0)
    expect(pos?.phase).toBe('holding')
    expect(pos?.progress).toBe(0)
  })

  it('advances to the next leg once the hold completes', () => {
    const itinerary = buildItinerary([{ x: 0, y: 0 }, { x: 100, y: 0 }], { holdMs: 1000, panMs: 500 })
    const pos = nextLeg(itinerary, 1500) // 500 pan + 1000 hold at leg 0
    expect(pos?.legIndex).toBe(1)
    expect(pos?.phase).toBe('panning')
    expect(pos?.progress).toBe(0)
  })

  it('loops back to leg 0 after a full cycle, with no drift', () => {
    const itinerary = buildItinerary([{ x: 0, y: 0 }, { x: 100, y: 0 }, { x: 200, y: 0 }], { holdMs: 1000, panMs: 500 })
    const cycle = itinerary.reduce((sum, leg) => sum + leg.panMs + leg.holdMs, 0) // 4500
    const early = nextLeg(itinerary, 100)
    const oneLoopLater = nextLeg(itinerary, 100 + cycle)
    const manyLoopsLater = nextLeg(itinerary, 100 + cycle * 1000)
    expect(oneLoopLater).toEqual(early)
    expect(manyLoopsLater).toEqual(early)
  })

  it('treats negative elapsed time as wrapping from the end of the cycle rather than breaking', () => {
    const itinerary = buildItinerary([{ x: 0, y: 0 }, { x: 100, y: 0 }], { holdMs: 1000, panMs: 500 })
    const cycle = itinerary.reduce((sum, leg) => sum + leg.panMs + leg.holdMs, 0)
    const pos = nextLeg(itinerary, -1)
    expect(pos).not.toBeNull()
    expect(Number.isNaN(pos!.progress)).toBe(false)
    expect(pos).toEqual(nextLeg(itinerary, cycle - 1))
  })

  it('never divides by zero for a degenerate all-zero-duration itinerary', () => {
    const legs: ObservationLeg[] = [
      { x: 0, y: 0, zoom: 1, holdMs: 0, panMs: 0 },
      { x: 10, y: 10, zoom: 1, holdMs: 0, panMs: 0 },
    ]
    const pos = nextLeg(legs, 1234)
    expect(pos).not.toBeNull()
    expect(Number.isNaN(pos!.progress)).toBe(false)
  })

  it('a typical 3-point default itinerary loops in exactly 30s, matching the demo target', () => {
    const itinerary = buildItinerary([{ x: 0, y: 0 }, { x: 100, y: 0 }, { x: 200, y: 0 }])
    const cycle = itinerary.reduce((sum, leg) => sum + leg.panMs + leg.holdMs, 0)
    expect(cycle).toBe(30_000)
  })
})
