import { describe, expect, it } from 'vitest'
import { canStand } from './collision'
import { safeTownPoint } from './town-recovery'
const world = { walkable: [{ x: 100, y: 800, width: 400, height: 200 }], obstacles: [{ x: 200, y: 800, width: 80, height: 80 }] }
describe('safe recovery positions', () => {
  it('rejects room coordinates, non-finite values and blocked points', () => {
    const fallback = { x: 350, y: 920 }
    for (const bad of [{ x: 320, y: 384 }, { x: 0, y: 0 }, { x: NaN, y: Infinity }, { x: 220, y: 820 }]) {
      expect(safeTownPoint(world, bad, fallback)).toEqual(fallback)
    }
  })
  it('restores a valid exterior checkpoint exactly', () => {
    expect(safeTownPoint(world, { x: 170, y: 910 }, { x: 350, y: 920 })).toEqual({ x: 170, y: 910 })
  })
  it('finds a standable fallback even when the preferred anchor is obstructed', () => {
    const p = safeTownPoint(world, null, { x: 220, y: 820 })
    expect(p).not.toBeNull()
    expect(canStand(p!, world)).toBe(true)
    expect(safeTownPoint({ walkable: [], obstacles: [] }, null, { x: 0, y: 0 })).toBeNull()
  })
})
