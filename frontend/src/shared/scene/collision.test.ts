import { describe, expect, it } from 'vitest'
import { buildTownCollisionWorld, canStand, nearestStandable, resolveMove, type CollisionWorld } from './collision'

/** A simple street with one square building sitting in the middle of it, used by most cases below. */
function worldWithBuilding(): CollisionWorld {
  return {
    walkable: [{ x: 0, y: 0, width: 400, height: 200 }],
    obstacles: [{ x: 150, y: 50, width: 100, height: 100 }],
  }
}

describe('canStand', () => {
  it('is true inside the walkable area and clear of obstacles', () => {
    expect(canStand({ x: 10, y: 10 }, worldWithBuilding())).toBe(true)
  })

  it('is false outside every walkable rectangle', () => {
    expect(canStand({ x: -5, y: 10 }, worldWithBuilding())).toBe(false)
    expect(canStand({ x: 10, y: 500 }, worldWithBuilding())).toBe(false)
  })

  it('is false inside an obstacle even though it sits inside walkable ground', () => {
    expect(canStand({ x: 200, y: 100 }, worldWithBuilding())).toBe(false)
  })

  it('treats a rectangle edge as inside (inclusive bounds)', () => {
    expect(canStand({ x: 0, y: 0 }, worldWithBuilding())).toBe(true)
    expect(canStand({ x: 150, y: 100 }, worldWithBuilding())).toBe(false)
  })
})

describe('resolveMove', () => {
  it('returns the destination unchanged when nothing blocks it', () => {
    const world = worldWithBuilding()
    expect(resolveMove({ x: 10, y: 10 }, { x: 20, y: 20 }, world)).toEqual({ x: 20, y: 20 })
  })

  it('slides along the wall, keeping the x move, when only the y-only slide is still blocked', () => {
    const world = worldWithBuilding()
    // Directly under the building (same x-range), stepping up and slightly sideways: the y-only
    // slide keeps x=200 (still over the building) and stays blocked, but the x-only slide clears
    // the building's y-range (still at from.y=160, below it) so it stands.
    const from = { x: 200, y: 160 }
    const to = { x: 210, y: 120 }
    const result = resolveMove(from, to, world)
    expect(canStand(result, world)).toBe(true)
    expect(result).toEqual({ x: 210, y: 160 })
  })

  it('slides along the wall, keeping the y move, when only the x-only slide is still blocked', () => {
    const world = worldWithBuilding()
    // Directly beside the building (same y-range), stepping sideways and slightly up: the x-only
    // slide keeps y=100 (still beside the building) and stays blocked, but the y-only slide clears
    // the building's x-range (still at from.x=140, left of it) so it stands.
    const from = { x: 140, y: 100 }
    const to = { x: 170, y: 90 }
    const result = resolveMove(from, to, world)
    expect(canStand(result, world)).toBe(true)
    expect(result).toEqual({ x: 140, y: 90 })
  })

  it('never gets stuck: a move that runs off both edges of the map falls back to the starting point', () => {
    const world = worldWithBuilding()
    const from = { x: 2, y: 2 }
    const to = { x: -5, y: -5 } // off the walkable rectangle on both axes
    const result = resolveMove(from, to, world)
    expect(result).toEqual(from)
    expect(canStand(result, world)).toBe(true)
  })

  it('does not tunnel through a corner: repeated diagonal steps trace around it, not through it', () => {
    const world = worldWithBuilding()
    let position = { x: 100, y: 40 }
    // Step diagonally toward (and past) the building's top-left corner (150, 50) many times; every
    // single step must resolve to a standable point, i.e. the walker is deflected around the
    // corner instead of ever being placed inside the obstacle.
    for (let i = 0; i < 40; i += 1) {
      const attempt = { x: position.x + 3, y: position.y + 3 }
      position = resolveMove(position, attempt, world)
      expect(canStand(position, world)).toBe(true)
    }
  })
})

describe('nearestStandable', () => {
  it('returns the point itself when it already stands', () => {
    const world = worldWithBuilding()
    expect(nearestStandable({ x: 10, y: 10 }, world)).toEqual({ x: 10, y: 10 })
  })

  it('pulls a point outside the walkable area back onto its nearest edge', () => {
    const world = worldWithBuilding()
    const result = nearestStandable({ x: -40, y: 10 }, world)
    expect(result).toEqual({ x: 0, y: 10 })
    expect(canStand(result, world)).toBe(true)
  })

  it('pushes a point that lands inside a building out to the closest wall', () => {
    const world = worldWithBuilding()
    // Inside the 150-250 x 50-150 obstacle, much closer to its top edge (10px) than any other side.
    const result = nearestStandable({ x: 200, y: 60 }, world)
    expect(canStand(result, world)).toBe(true)
    expect(result.x).toBe(200)
    expect(result.y).toBeLessThan(50) // pushed out through the top edge, not left/right/bottom
    expect(result.y).toBeGreaterThan(40) // and only just clear of it
  })

  it('clamps a far-off destination to the nearest point on the walkable rectangle', () => {
    const world = worldWithBuilding()
    const result = nearestStandable({ x: 5000, y: -5000 }, world)
    expect(result).toEqual({ x: 400, y: 0 })
    expect(canStand(result, world)).toBe(true)
  })
})

describe('buildTownCollisionWorld', () => {
  const world = buildTownCollisionWorld({
    groundMinX: 100,
    groundMaxX: 2000,
    baselineY: 900,
    apron: 60,
    bottomY: 1150,
    // Plaza sits near the academy (x 700-1100); the ordinary shop at x 300-500 is outside it,
    // so it only gets the shallow apron, not the plaza's deeper reach.
    plaza: { minX: 600, maxX: 1200, topY: 550 },
    buildings: [
      { x: 300, width: 200, topY: 700 }, // a two-storey shop
      { x: 700, width: 400, topY: 200 }, // the academy, much taller
    ],
  })

  it('lets the avatar stand on the ordinary street just south of the baseline', () => {
    expect(canStand({ x: 150, y: 920 }, world)).toBe(true)
  })

  it('blocks the avatar from walking into a building footprint', () => {
    expect(canStand({ x: 350, y: 850 }, world)).toBe(false) // inside the shop
    expect(canStand({ x: 750, y: 400 }, world)).toBe(false) // inside the academy
  })

  it('lets the avatar wander into the plaza in front of the academy, outside any footprint', () => {
    expect(canStand({ x: 1150, y: 600 }, world)).toBe(true)
  })

  it('keeps the ordinary sidewalk apron shallow: far north of an ordinary block is not walkable', () => {
    expect(canStand({ x: 350, y: 500 }, world)).toBe(false)
  })

  it('slides a walker along a building wall it is heading straight for', () => {
    const from = { x: 280, y: 920 }
    const to = { x: 320, y: 890 } // heading north-east straight at the shop's corner
    const result = resolveMove(from, to, world)
    expect(canStand(result, world)).toBe(true)
  })

  it('包含家具碰撞障碍物', () => {
    const worldWithFurniture = buildTownCollisionWorld({
      groundMinX: 100,
      groundMaxX: 2000,
      baselineY: 900,
      apron: 60,
      bottomY: 1150,
      plaza: { minX: 600, maxX: 1200, topY: 550 },
      buildings: [{ x: 700, width: 400, topY: 200 }],
      furniture: [
        { x: 500, y: 920, width: 48, height: 24 }, // 长椅
        { x: 600, y: 930, width: 32, height: 20 }, // 邮筒
      ],
    })

    // 长椅位置不可站立
    expect(canStand({ x: 520, y: 930 }, worldWithFurniture)).toBe(false)
    // 邮筒位置不可站立
    expect(canStand({ x: 615, y: 940 }, worldWithFurniture)).toBe(false)
    // 家具旁边可以站立
    expect(canStand({ x: 560, y: 930 }, worldWithFurniture)).toBe(true)
  })

  it('家具碰撞可以和建筑碰撞共存', () => {
    const world = buildTownCollisionWorld({
      groundMinX: 100,
      groundMaxX: 2000,
      baselineY: 900,
      apron: 60,
      bottomY: 1150,
      plaza: { minX: 600, maxX: 1200, topY: 550 },
      buildings: [{ x: 300, width: 200, topY: 700 }],
      furniture: [{ x: 550, y: 920, width: 64, height: 32 }],
    })

    // 建筑内不可站立
    expect(canStand({ x: 350, y: 850 }, world)).toBe(false)
    // 家具上不可站立
    expect(canStand({ x: 570, y: 930 }, world)).toBe(false)
    // 空地可以站立
    expect(canStand({ x: 700, y: 920 }, world)).toBe(true)
  })
})
