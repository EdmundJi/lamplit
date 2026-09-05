import { describe, expect, it } from 'vitest'
import { RUN_SPEED, WALK_SPEED, canGreet, clampWalkX, dominantDirection, isPassingPair, movementDelta, moveSpeed, pairKey, registerGreet, shouldGreet, stepToward, stepTowardPoint, type GreetCooldowns } from './walkers'

describe('clampWalkX', () => {
  it('passes values already inside the range through unchanged', () => {
    expect(clampWalkX(100, 0, 200)).toBe(100)
  })

  it('clamps below the minimum', () => {
    expect(clampWalkX(-50, 0, 200)).toBe(0)
  })

  it('clamps above the maximum', () => {
    expect(clampWalkX(500, 0, 200)).toBe(200)
  })

  it('falls back to min when min > max (degenerate street)', () => {
    expect(clampWalkX(50, 200, 100)).toBe(200)
  })
})

describe('pairKey', () => {
  it('is order-independent', () => {
    expect(pairKey('a', 'b')).toBe(pairKey('b', 'a'))
  })

  it('differs for different pairs', () => {
    expect(pairKey('a', 'b')).not.toBe(pairKey('a', 'c'))
  })
})

describe('isPassingPair', () => {
  it('is true when the left walker heads right and the right walker heads left', () => {
    expect(isPassingPair(100, 'right', 140, 'left')).toBe(true)
  })

  it('is false when both walkers head the same direction', () => {
    expect(isPassingPair(100, 'right', 140, 'right')).toBe(false)
  })

  it('is false when they are walking apart (diverging), not toward each other', () => {
    expect(isPassingPair(100, 'left', 140, 'right')).toBe(false)
  })

  it('works regardless of argument order (a further right than b)', () => {
    expect(isPassingPair(140, 'left', 100, 'right')).toBe(true)
  })
})

describe('greet cooldowns', () => {
  it('allows a greeting when no cooldown is on record', () => {
    const cooldowns: GreetCooldowns = new Map()
    expect(canGreet(cooldowns, 'a', 'b', 1000)).toBe(true)
  })

  it('blocks a greeting for 30s after registering one', () => {
    const cooldowns: GreetCooldowns = new Map()
    registerGreet(cooldowns, 'a', 'b', 1000)
    expect(canGreet(cooldowns, 'a', 'b', 1000 + 29_000)).toBe(false)
    expect(canGreet(cooldowns, 'a', 'b', 1000 + 30_000)).toBe(true)
  })

  it('cooldown applies regardless of argument order', () => {
    const cooldowns: GreetCooldowns = new Map()
    registerGreet(cooldowns, 'a', 'b', 1000)
    expect(canGreet(cooldowns, 'b', 'a', 1000 + 100)).toBe(false)
  })

  it('does not block an unrelated pair', () => {
    const cooldowns: GreetCooldowns = new Map()
    registerGreet(cooldowns, 'a', 'b', 1000)
    expect(canGreet(cooldowns, 'a', 'c', 1000 + 100)).toBe(true)
  })
})

describe('shouldGreet', () => {
  const now = 10_000

  it('is true for two close, approaching, not-on-cooldown walkers', () => {
    const cooldowns: GreetCooldowns = new Map()
    expect(shouldGreet({ id: 'a', x: 100, dir: 'right' }, { id: 'b', x: 118, dir: 'left' }, cooldowns, now)).toBe(true)
  })

  it('is false when they are further apart than the greeting distance', () => {
    const cooldowns: GreetCooldowns = new Map()
    expect(shouldGreet({ id: 'a', x: 100, dir: 'right' }, { id: 'b', x: 200, dir: 'left' }, cooldowns, now)).toBe(false)
  })

  it('is false when not walking toward each other', () => {
    const cooldowns: GreetCooldowns = new Map()
    expect(shouldGreet({ id: 'a', x: 100, dir: 'left' }, { id: 'b', x: 110, dir: 'right' }, cooldowns, now)).toBe(false)
  })

  it('is false for the same walker id', () => {
    const cooldowns: GreetCooldowns = new Map()
    expect(shouldGreet({ id: 'a', x: 100, dir: 'right' }, { id: 'a', x: 110, dir: 'left' }, cooldowns, now)).toBe(false)
  })

  it('is false while the pair is still on cooldown', () => {
    const cooldowns: GreetCooldowns = new Map()
    registerGreet(cooldowns, 'a', 'b', now)
    expect(shouldGreet({ id: 'a', x: 100, dir: 'right' }, { id: 'b', x: 118, dir: 'left' }, cooldowns, now + 1000)).toBe(false)
  })
})

describe('run speed', () => {
  it('runs faster than it walks', () => {
    expect(moveSpeed(false)).toBe(WALK_SPEED)
    expect(moveSpeed(true)).toBe(RUN_SPEED)
    expect(RUN_SPEED).toBeGreaterThan(WALK_SPEED)
  })

  it('covers more ground per frame while running', () => {
    const walked = stepToward(0, 1000, moveSpeed(false), 1000)
    const ran = stepToward(0, 1000, moveSpeed(true), 1000)
    expect(walked).toBe(WALK_SPEED)
    expect(ran).toBe(RUN_SPEED)
  })

  it('never overshoots the target, however fast it moves', () => {
    expect(stepToward(0, 20, RUN_SPEED, 1000)).toBe(20)
    expect(stepToward(0, -20, RUN_SPEED, 1000)).toBe(-20)
    expect(stepToward(40, 40, RUN_SPEED, 1000)).toBe(40)
  })

  it('moves toward a target on either side', () => {
    expect(stepToward(0, 100, WALK_SPEED, 500)).toBe(WALK_SPEED / 2)
    expect(stepToward(0, -100, WALK_SPEED, 500)).toBe(-WALK_SPEED / 2)
  })
})

describe('stepTowardPoint', () => {
  it('never overshoots a diagonal target', () => {
    const result = stepTowardPoint({ x: 0, y: 0 }, { x: 3, y: 4 }, WALK_SPEED, 100_000)
    expect(result).toEqual({ x: 3, y: 4 })
  })

  it('moves the full distance in one step when speed allows it, split proportionally', () => {
    // A 3-4-5 triangle: at speed 5px/s for 1000ms it should travel exactly the 5px distance.
    const result = stepTowardPoint({ x: 0, y: 0 }, { x: 3, y: 4 }, 5, 1000)
    expect(result.x).toBeCloseTo(3)
    expect(result.y).toBeCloseTo(4)
  })

  it('moves partway along the straight line toward the target', () => {
    const result = stepTowardPoint({ x: 0, y: 0 }, { x: 100, y: 0 }, WALK_SPEED, 500)
    expect(result.x).toBeCloseTo(WALK_SPEED / 2)
    expect(result.y).toBeCloseTo(0)
  })

  it('is a no-op once already at the target', () => {
    expect(stepTowardPoint({ x: 5, y: 5 }, { x: 5, y: 5 }, WALK_SPEED, 1000)).toEqual({ x: 5, y: 5 })
  })
})

describe('movementDelta', () => {
  it('is zero with no input', () => {
    expect(movementDelta(0, 0, WALK_SPEED, 1000)).toEqual({ x: 0, y: 0 })
  })

  it('moves at full speed along a single cardinal axis', () => {
    const right = movementDelta(1, 0, WALK_SPEED, 1000)
    expect(right.x).toBeCloseTo(WALK_SPEED)
    expect(right.y).toBeCloseTo(0)
  })

  it('normalizes a diagonal so it is no faster than a cardinal move', () => {
    const diagonal = movementDelta(1, -1, WALK_SPEED, 1000)
    const traveled = Math.hypot(diagonal.x, diagonal.y)
    expect(traveled).toBeCloseTo(WALK_SPEED)
    // Both axes share the diagonal's speed evenly.
    expect(diagonal.x).toBeCloseTo(diagonal.y * -1)
  })

  it('running still normalizes the same way, just faster', () => {
    const diagonal = movementDelta(-1, 1, RUN_SPEED, 1000)
    expect(Math.hypot(diagonal.x, diagonal.y)).toBeCloseTo(RUN_SPEED)
  })
})

describe('dominantDirection', () => {
  it('falls back to the given facing when there is no movement', () => {
    expect(dominantDirection(0, 0, 'down')).toBe('down')
    expect(dominantDirection(0, 0, 'left')).toBe('left')
  })

  it('picks left/right when the horizontal component dominates', () => {
    expect(dominantDirection(5, 1, 'down')).toBe('right')
    expect(dominantDirection(-5, 1, 'down')).toBe('left')
  })

  it('picks up/down when the vertical component dominates', () => {
    expect(dominantDirection(1, 5, 'right')).toBe('down')
    expect(dominantDirection(1, -5, 'right')).toBe('up')
  })

  it('breaks an exact tie in favour of the horizontal axis', () => {
    expect(dominantDirection(3, 3, 'up')).toBe('right')
  })
})
