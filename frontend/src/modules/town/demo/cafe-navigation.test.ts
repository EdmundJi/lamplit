import { describe, expect, it } from 'vitest'
import { CAFE_ANCHORS, CAFE_OBSTACLES, CAFE_WALK_BOUNDS } from './cafe-stage'
import { canStand, type CollisionWorld } from '../collision'
import { clearSegment, findPath } from '../pathfinding'

const world: CollisionWorld = { walkable: [CAFE_WALK_BOUNDS], obstacles: CAFE_OBSTACLES }
describe('cafe playable floor', () => {
  it('connects the entrance, both sides of the main table and the reading corner without crossing furniture', () => {
    for (const point of [{ x: 560, y: 353 }, { x: 375, y: 450 }, { x: 510, y: 430 }, { x: 770, y: 465 }]) {
      expect(canStand(point, world)).toBe(true)
      const path = findPath(CAFE_ANCHORS.playerSpawn, point, world)
      expect(path).not.toBeNull()
      let previous = CAFE_ANCHORS.playerSpawn
      for (const step of path!) { expect(clearSegment(previous, step, world)).toBe(true); previous = step }
    }
  })
  it('routes around the main table instead of letting a click cut through it', () => {
    const from = { x: 380, y: 410 }, to = { x: 520, y: 410 }
    expect(clearSegment(from, to, world)).toBe(false)
    const path = findPath(from, to, world)
    expect(path).not.toBeNull()
    expect(path!.length).toBeGreaterThan(1)
    let previous = from
    for (const point of path!) { expect(clearSegment(previous, point, world)).toBe(true); previous = point }
  })
})
