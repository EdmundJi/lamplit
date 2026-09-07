import { describe, expect, it } from 'vitest'
import { cafeStandingPoint } from './npc-standing'

describe('cafe standing places', () => {
  it('keeps the visible resident budget apart and leaves the door threshold clear', () => {
    const codes = Array.from({ length: 8 }, (_, i) => `resident-${i}`)
    const points = codes.map(code => cafeStandingPoint(code, codes, 2400, 936))
    for (let i = 0; i < points.length; i++) {
      expect(Math.hypot(points[i]!.x - 2400, points[i]!.y - 908)).toBeGreaterThan(32)
      for (let j = i + 1; j < points.length; j++) expect(Math.hypot(points[i]!.x - points[j]!.x, points[i]!.y - points[j]!.y)).toBeGreaterThan(40)
    }
  })
  it('is unaffected by roster response order', () => {
    expect(cafeStandingPoint('b', ['a', 'b', 'c'], 20, 30)).toEqual(cafeStandingPoint('b', ['c', 'b', 'a'], 20, 30))
  })
})
