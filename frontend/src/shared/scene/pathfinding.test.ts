import { describe, expect, it } from 'vitest'
import { clearSegment, findPath } from './pathfinding'
import type { CollisionWorld, Point } from './collision'

const world: CollisionWorld = { walkable: [{ x: 0, y: 0, width: 320, height: 200 }], obstacles: [{ x: 120, y: 40, width: 48, height: 120 }] }
describe('town navigation', () => {
  it('walks around a bench rather than triggering arrival while blocked', () => {
    const start = { x: 32, y: 96 }, goal = { x: 256, y: 96 }
    const route = findPath(start, goal, world)
    expect(route).not.toBeNull()
    expect(route!.at(-1)).toEqual(goal)
    let from: Point = start
    for (const point of route!) { expect(clearSegment(from, point, world)).toBe(true); from = point }
    expect(route!.some(point => point.y < 40 || point.y > 160)).toBe(true)
  })
  it('does not cut through a wall or cross disconnected walkable areas', () => {
    const disconnected = { ...world, obstacles: [{ x: 120, y: 0, width: 48, height: 200 }] }
    expect(findPath({ x: 32, y: 96 }, { x: 256, y: 96 }, disconnected)).toBeNull()
    expect(findPath({ x: 32, y: 96 }, { x: 130, y: 96 }, world)).toBeNull()
  })
  it('keeps free travel straight and handles a narrow entrance', () => {
    expect(findPath({ x: 5, y: 10 }, { x: 300, y: 10 }, world)).toEqual([{ x: 300, y: 10 }])
    const route = findPath({ x: 31, y: 195 }, { x: 254, y: 167 }, world)
    expect(route?.at(-1)).toEqual({ x: 254, y: 167 })
  })
})
