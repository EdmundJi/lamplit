import { describe, expect, it } from 'vitest'
import { canStand, type CollisionWorld, type Point } from './collision'
import { companionClearSegment, companionSpawn, createCompanionMotion, COMPANION_RUN_SPEED } from './companion-motion'
import { RUN_SPEED } from './walkers'
const park = { x: 340, y: 180, width: 140, height: 140 }
const open: CollisionWorld = { walkable: [{ x: 0, y: 0, width: 1000, height: 500 }], obstacles: [] }
describe('companion navigation', () => {
  it('detects even thin walls and disconnected walkable gaps', () => {
    expect(companionClearSegment({ x: 0, y: 10 }, { x: 20, y: 10 }, { ...open, obstacles: [{ x: 2.1, y: 0, width: .1, height: 20 }] })).toBe(false)
    expect(companionClearSegment({ x: 0, y: 0 }, { x: 20, y: 0 }, { walkable: [{ x: 0, y: 0, width: 10, height: 20 }, { x: 10.01, y: 0, width: 10, height: 20 }], obstacles: [] })).toBe(false)
  })
  it('follows around a building without cutting corners or teleporting', () => {
    const world = { ...open, obstacles: [{ x: 140, y: 80, width: 100, height: 180 }] }
    const motion = createCompanionMotion({ x: 80, y: 140 })
    for (let frame = 0; frame < 300; frame++) {
      const before = motion.snapshot().position
      const step = motion.update(16, { x: 310, y: 140 }, world, park)
      expect(canStand(step.position, world)).toBe(true)
      expect(companionClearSegment(before, step.position, world)).toBe(true)
      expect(Math.hypot(step.dx, step.dy)).toBeLessThanOrEqual(COMPANION_RUN_SPEED * .016 + 1e-6)
    }
    expect(Math.hypot(motion.snapshot().position.x - 310, motion.snapshot().position.y - 140)).toBeCloseTo(30)
  })

  it('routes around a thin fence instead of accepting a sampled shortcut', () => {
    const world = { ...open, obstacles: [{ x: 146.1, y: 60, width: .1, height: 120 }] }
    const motion = createCompanionMotion({ x: 100, y: 100 })
    for (let frame = 0; frame < 240; frame++) {
      const before = motion.snapshot().position, next = motion.update(16, { x: 240, y: 100 }, world, park)
      expect(companionClearSegment(before, next.position, world)).toBe(true)
    }
    expect(Math.hypot(motion.snapshot().position.x - 240, motion.snapshot().position.y - 100)).toBeCloseTo(30)
  })
  it('keeps up with running, then settles beside the player', () => {
    const motion = createCompanionMotion({ x: 40, y: 100 })
    let player: Point = { x: 70, y: 100 }
    for (let frame = 0; frame < 180; frame++) {
      player = { x: player.x + RUN_SPEED * .016, y: 100 }
      motion.update(16, player, open, park)
    }
    expect(player.x - motion.snapshot().position.x).toBeLessThan(80)
    for (let frame = 0; frame < 120; frame++) motion.update(16, player, open, park)
    expect(player.x - motion.snapshot().position.x).toBeCloseTo(30)
  })

  it('keeps every frame legal as a running player rounds two building corners', () => {
    const world = { ...open, obstacles: [{ x: 140, y: 80, width: 160, height: 180 }] }
    const motion = createCompanionMotion({ x: 70, y: 100 })
    let player = { x: 100, y: 100 }
    for (const target of [{ x: 100, y: 290 }, { x: 340, y: 290 }, { x: 340, y: 100 }]) {
      while (Math.hypot(target.x - player.x, target.y - player.y) > .01) {
        const distance = Math.hypot(target.x - player.x, target.y - player.y), step = Math.min(distance, RUN_SPEED * .016)
        player = { x: player.x + (target.x - player.x) * step / distance, y: player.y + (target.y - player.y) * step / distance }
        const before = motion.snapshot().position, next = motion.update(16, player, world, park)
        expect(companionClearSegment(before, next.position, world)).toBe(true)
        expect(Math.hypot(next.dx, next.dy)).toBeLessThanOrEqual(COMPANION_RUN_SPEED * .016 + 1e-6)
      }
    }
    for (let frame = 0; frame < 120; frame++) motion.update(16, player, world, park)
    expect(Math.hypot(player.x - motion.snapshot().position.x, player.y - motion.snapshot().position.y)).toBeCloseTo(30)
  })
  it('constrains every roaming segment to park and recalls when owner leaves', () => {
    const motion = createCompanionMotion({ x: 370, y: 220 }, () => .8); motion.setMode('roaming')
    const world = { ...open, obstacles: [{ x: 400, y: 230, width: 24, height: 25 }] }
    for (let frame = 0; frame < 900; frame++) {
      const result = motion.update(16, { x: 350, y: 190 }, world, park)
      expect(result.position.x).toBeGreaterThanOrEqual(park.x)
      expect(result.position.x).toBeLessThanOrEqual(park.x + park.width)
      expect(result.position.y).toBeGreaterThanOrEqual(park.y)
      expect(result.position.y).toBeLessThanOrEqual(park.y + park.height)
      expect(canStand(result.position, world)).toBe(true)
    }
    const recalled = motion.update(16, { x: 300, y: 170 }, world, park)
    expect(recalled.mode).toBe('following'); expect(recalled.modeChanged).toBe(true)
  })
  it('stays put across unreachable targets and home mode; does not jump after suspension', () => {
    const world = { ...open, obstacles: [{ x: 200, y: 0, width: 20, height: 500 }] }
    const motion = createCompanionMotion({ x: 100, y: 100 })
    motion.update(16, { x: 300, y: 100 }, world, park)
    expect(motion.snapshot().position).toEqual({ x: 100, y: 100 })
    motion.setMode('home'); motion.update(16, { x: 120, y: 150 }, open, park)
    expect(motion.snapshot().position).toEqual({ x: 100, y: 100 })
    motion.setMode('following')
    const step = motion.update(50000, { x: 500, y: 100 }, open, park)
    expect(step.dx).toBeLessThanOrEqual(COMPANION_RUN_SPEED * .1 + 1e-6)
    expect(companionSpawn({ x: 10, y: 10 }, { walkable: [], obstacles: [] })).toBeNull()
  })
})
