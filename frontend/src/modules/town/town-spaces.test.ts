import { describe, expect, it } from 'vitest'
import { gardenDistrict, pointAlongRoute } from './town-spaces'
import { canStand, type CollisionWorld } from './collision'
import { findPath, clearSegment } from './pathfinding'

describe('L-shaped garden district', () => {
  it('connects street to garden around public buildings', () => {
    const garden = gardenDistrict(3600)
    const world: CollisionWorld = {
      walkable: [{ x: 100, y: 832, width: 3400, height: 320 }, ...garden.walkable],
      obstacles: [{ x: 2800, y: 608, width: 224, height: 288 }],
    }
    const start = { x: 2800, y: 940 }
    const route = findPath(start, garden.park, world)
    expect(route).not.toBeNull()
    expect(route!.at(-1)).toEqual(garden.park)
    let previous = start
    for (const point of route!) {
      expect(canStand(point, world)).toBe(true)
      expect(clearSegment(previous, point, world)).toBe(true)
      previous = point
    }
  })
  it('follows each leg of a corner rather than cutting diagonally through the block', () => {
    const points = [{ x: 0, y: 100 }, { x: 100, y: 100 }, { x: 100, y: 0 }]
    expect(pointAlongRoute(points, .25)).toEqual({ x: 50, y: 100 })
    expect(pointAlongRoute(points, .75)).toEqual({ x: 100, y: 50 })
    expect(pointAlongRoute(points, 1)).toEqual(points[2])
  })
})
