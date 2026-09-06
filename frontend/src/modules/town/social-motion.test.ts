import { describe, expect, it } from 'vitest'
import { canStand, type CollisionWorld } from './collision'
import { createSocialTimeline, socialApproach, socialPhase, socialGestureFrame } from './social-motion'

const open: CollisionWorld = { walkable: [{ x: 0, y: 0, width: 500, height: 500 }], obstacles: [] }
describe('social approach', () => {
  it('stops at conversation distance on the approaching side, never on the NPC', () => {
    const person = { x: 250, y: 250 }
    const point = socialApproach({ x: 50, y: 250 }, person, open)!
    expect(point.x).toBeLessThan(person.x)
    expect(Math.hypot(point.x - person.x, point.y - person.y)).toBeCloseTo(44)
  })
  it('chooses a reachable alternative when furniture blocks the nearest standing point', () => {
    const world = { ...open, obstacles: [{ x: 196, y: 235, width: 28, height: 30 }] }
    const person = { x: 250, y: 250 }
    const point = socialApproach({ x: 50, y: 250 }, person, world)!
    expect(canStand(point, world)).toBe(true)
    expect(Math.hypot(point.x - person.x, point.y - person.y)).toBeCloseTo(44)
    expect(point.y).not.toBe(250)
  })
  it('refuses an unreachable ring instead of forcing overlapping placement', () => {
    const world: CollisionWorld = { walkable: [{ x: 0, y: 0, width: 50, height: 50 }, { x: 300, y: 300, width: 50, height: 50 }], obstacles: [] }
    expect(socialApproach({ x: 20, y: 20 }, { x: 320, y: 320 }, world)).toBeNull()
  })
})
describe('staggered social motion', () => {
  it('settles before the first greeting, then replies, then leaves', () => {
    const sequence = createSocialTimeline(1000)
    expect(sequence.advance(1000)).toBe('settle')
    expect(sequence.advance(1200)).toBeNull()
    expect(sequence.advance(1350)).toBe('greet')
    expect(sequence.advance(2500)).toBeNull()
    expect(sequence.advance(2650)).toBe('reply')
    expect(sequence.advance(4150)).toBe('leave')
    expect(sequence.advance(4550)).toBe('done')
  })
  it('cancels pending replies when a participant walks away or another interaction takes over', () => {
    const sequence = createSocialTimeline(0)
    sequence.advance(400)
    sequence.cancel()
    expect(sequence.advance(1800)).toBeNull()
    expect(sequence.advance(10000)).toBeNull()
  })
  it('keeps speech turns exclusive at every boundary', () => {
    expect(socialPhase(349)).toBe('settle')
    expect(socialPhase(350)).toBe('greet')
    expect(socialPhase(1649)).toBe('greet')
    expect(socialPhase(1650)).toBe('reply')
    expect(socialPhase(3150)).toBe('leave')
    expect(socialPhase(3550)).toBe('done')
  })
})

describe('restrained native greeting pose', () => {
  it('uses only lift preparation steps and accounts for wider postman sheets', () => {
    expect([0, 120, 240, 360, 480].map(t => socialGestureFrame(1854, 1280, 'left', t))).toEqual([0, 2, 4, 2, 0].map(step => 11 * 57 + 28 + step))
    expect(socialGestureFrame(1792, 1280, 'down', 240)).toBe(11 * 56 + 42 + 4)
    expect(socialGestureFrame(1792, 1280, 'right', 600)).toBeNull()
  })
  it('falls back to facing when native gesture frames are absent', () => {
    expect(socialGestureFrame(768, 192, 'right', 0)).toBeNull()
    expect(socialGestureFrame(32 * 20, 1280, 'down', 0)).toBeNull()
  })
})
