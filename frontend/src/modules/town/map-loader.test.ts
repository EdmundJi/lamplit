import { describe, expect, it } from 'vitest'
import {
  assignSeats,
  clampToRoom,
  collidesAt,
  doorAt,
  entrySpawnFor,
  evaluateSlots,
  findDoor,
  interactableAt,
  parseRoomMap,
  RoomMapValidationError,
  stepAxis,
  type RoomMapData,
  type RoomResident,
} from './map-loader'

function minimalRoom(overrides: Record<string, unknown> = {}): unknown {
  return {
    id: 'home-living-room',
    title: '自己家 · 客厅',
    tileSize: 32,
    cols: 4,
    rows: 3,
    backgroundColor: '#e7d9bd',
    spawn: { x: 64, y: 64 },
    layers: {
      floor: [
        ['floor_1', 'floor_1', 'floor_1', 'floor_1'],
        ['floor_1', 'floor_1', 'floor_1', 'floor_1'],
        ['floor_1', 'floor_1', 'floor_1', 'floor_1'],
      ],
      walls: [
        ['wall_1', 'wall_1', 'wall_1', 'wall_1'],
        [null, null, null, null],
        [null, null, null, null],
      ],
    },
    collisions: [{ x: 0, y: 0, w: 128, h: 32 }],
    doors: [{ id: 'front-door', rect: { x: 48, y: 80, w: 32, h: 16 }, target: 'town' }],
    furniture: [{ id: 'sofa', frame: 'sofa_1', x: 100, y: 100 }],
    slots: [{ id: 'frames', frames: ['frame_1', 'frame_2'], metric: 'knowledgeDone', max: 4, anchor: { x: 20, y: 20 }, step: { x: 24, y: 0 } }],
    seats: [{ id: 'seat-1', x: 60, y: 60 }],
    ...overrides,
  }
}

describe('parseRoomMap', () => {
  it('parses a well-formed room map into a normalized structure', () => {
    const room = parseRoomMap(minimalRoom())
    expect(room.id).toBe('home-living-room')
    expect(room.layers.floor).toHaveLength(3)
    expect(room.layers.floor[0]).toHaveLength(4)
    expect(room.doors).toEqual([{ id: 'front-door', rect: { x: 48, y: 80, w: 32, h: 16 }, target: 'town', label: undefined, spawn: undefined }])
    expect(room.furniture[0]).toMatchObject({ id: 'sofa', frame: 'sofa_1', x: 100, y: 100 })
    expect(room.slots[0].frames).toEqual(['frame_1', 'frame_2'])
    expect(room.seats).toEqual([{ id: 'seat-1', x: 60, y: 60 }])
  })

  it('rejects a non-object payload', () => {
    expect(() => parseRoomMap(null)).toThrow(RoomMapValidationError)
    expect(() => parseRoomMap('nope')).toThrow(RoomMapValidationError)
  })

  it('reports every problem at once instead of stopping at the first', () => {
    try {
      parseRoomMap(minimalRoom({ id: '', tileSize: -1, cols: 0 }))
      expect.unreachable()
    } catch (error) {
      expect(error).toBeInstanceOf(RoomMapValidationError)
      const issues = (error as RoomMapValidationError).issues
      expect(issues.some(i => i.includes('"id"'))).toBe(true)
      expect(issues.some(i => i.includes('"tileSize"'))).toBe(true)
      expect(issues.some(i => i.includes('"cols"'))).toBe(true)
    }
  })

  it('rejects a floor layer whose row count does not match "rows"', () => {
    const bad = minimalRoom()
    ;(bad as Record<string, unknown>).layers = { floor: [['floor_1']], walls: (bad as Record<string, unknown>).layers as unknown as unknown[] }
    expect(() => parseRoomMap(bad)).toThrow(RoomMapValidationError)
  })

  it('rejects a tile that is neither a string nor null', () => {
    const bad = minimalRoom()
    const layers = (bad as { layers: { floor: unknown[][] } }).layers
    layers.floor[0][0] = 42
    expect(() => parseRoomMap(bad)).toThrow(/must be a frame name or null/)
  })

  it('rejects a door missing its target', () => {
    expect(() => parseRoomMap(minimalRoom({ doors: [{ id: 'd', rect: { x: 0, y: 0, w: 1, h: 1 } }] }))).toThrow(/target/)
  })

  it('rejects a collision rect with non-positive width', () => {
    expect(() => parseRoomMap(minimalRoom({ collisions: [{ x: 0, y: 0, w: 0, h: 10 }] }))).toThrow(RoomMapValidationError)
  })

  it('rejects a slot with an empty frames array', () => {
    expect(() => parseRoomMap(minimalRoom({ slots: [{ id: 's', frames: [], metric: 'x', max: 1, anchor: { x: 0, y: 0 }, step: { x: 0, y: 0 } }] }))).toThrow(/frames/)
  })

  it('rejects duplicate ids within the same list', () => {
    expect(() => parseRoomMap(minimalRoom({
      furniture: [{ id: 'a', frame: 'x', x: 0, y: 0 }, { id: 'a', frame: 'y', x: 1, y: 1 }],
    }))).toThrow(/duplicate id/)
  })

  it('accepts empty optional arrays (a bare room with no doors/furniture/slots/seats)', () => {
    const room = parseRoomMap(minimalRoom({ collisions: [], doors: [], furniture: [], slots: [], seats: [] }))
    expect(room.doors).toEqual([])
    expect(room.furniture).toEqual([])
  })

  it('leaves furniture.interactive undefined when not present (today\'s rooms keep working unchanged)', () => {
    const room = parseRoomMap(minimalRoom())
    expect(room.furniture[0].interactive).toBeUndefined()
  })

  it('parses a furniture piece\'s interactive field (M3-3)', () => {
    const room = parseRoomMap(minimalRoom({
      furniture: [{ id: 'desk', frame: 'desk_1', x: 200, y: 200, interactive: { actionId: 'home.open-desk', label: '打开书桌' } }],
    }))
    expect(room.furniture[0].interactive).toEqual({ actionId: 'home.open-desk', hit: undefined, label: '打开书桌' })
  })

  it('accepts an explicit hit-box on interactive furniture', () => {
    const room = parseRoomMap(minimalRoom({
      furniture: [{ id: 'desk', frame: 'desk_1', x: 200, y: 200, interactive: { actionId: 'a', hit: { x: 1, y: 2, w: 3, h: 4 } } }],
    }))
    expect(room.furniture[0].interactive?.hit).toEqual({ x: 1, y: 2, w: 3, h: 4 })
  })

  it('rejects interactive furniture missing actionId', () => {
    expect(() => parseRoomMap(minimalRoom({
      furniture: [{ id: 'desk', frame: 'desk_1', x: 200, y: 200, interactive: {} }],
    }))).toThrow(/interactive\.actionId/)
  })

  it('rejects an interactive hit-box that is not a valid rect', () => {
    expect(() => parseRoomMap(minimalRoom({
      furniture: [{ id: 'desk', frame: 'desk_1', x: 200, y: 200, interactive: { actionId: 'a', hit: { x: 0, y: 0, w: 0, h: 0 } } }],
    }))).toThrow(/interactive\.hit/)
  })
})

describe('evaluateSlots', () => {
  const room: RoomMapData = parseRoomMap(minimalRoom())

  it('shows zero instances when the metric is absent', () => {
    expect(evaluateSlots(room, {})).toEqual([])
  })

  it('shows floor(value / perItem) instances, clamped to max, cycling through frames', () => {
    const instances = evaluateSlots(room, { knowledgeDone: 3 })
    expect(instances).toHaveLength(3)
    expect(instances.map(i => i.frame)).toEqual(['frame_1', 'frame_2', 'frame_1'])
    expect(instances[2]).toMatchObject({ x: 20 + 24 * 2, y: 20, originX: 0.5, originY: 1, depth: 20 })
  })

  it('clamps at max even when the metric is far larger', () => {
    const instances = evaluateSlots(room, { knowledgeDone: 99 })
    expect(instances).toHaveLength(4)
  })

  it('respects perItem when scaling a metric down to a count', () => {
    const scaled = parseRoomMap(minimalRoom({
      slots: [{ id: 's', frames: ['x'], metric: 'm', max: 10, perItem: 5, anchor: { x: 0, y: 0 }, step: { x: 1, y: 0 } }],
    }))
    expect(evaluateSlots(scaled, { m: 12 })).toHaveLength(2)
  })

  it('never returns a negative count for a negative metric', () => {
    expect(evaluateSlots(room, { knowledgeDone: -5 })).toEqual([])
  })
})

describe('assignSeats', () => {
  const room: RoomMapData = parseRoomMap(minimalRoom({
    seats: [{ id: 's1', x: 10, y: 10 }, { id: 's2', x: 20, y: 20 }],
  }))
  const resident = (publicId: string): RoomResident => ({ publicId, displayName: publicId, isSelf: false, characterSheet: 1, state: 'idle' })

  it('fills seats in order, one resident per seat', () => {
    const result = assignSeats(room, [resident('a'), resident('b')])
    expect(result).toEqual([{ seat: room.seats[0], resident: resident('a') }, { seat: room.seats[1], resident: resident('b') }])
  })

  it('drops residents beyond the number of seats instead of throwing', () => {
    const result = assignSeats(room, [resident('a'), resident('b'), resident('c')])
    expect(result).toHaveLength(2)
  })

  it('leaves extra seats empty when there are fewer residents than seats', () => {
    const result = assignSeats(room, [resident('a')])
    expect(result).toHaveLength(1)
  })
})

describe('doors: findDoor / doorAt / entrySpawnFor', () => {
  const room: RoomMapData = parseRoomMap(minimalRoom())

  it('finds a door by id', () => {
    expect(findDoor(room, 'front-door')?.target).toBe('town')
    expect(findDoor(room, 'missing')).toBeUndefined()
  })

  it('hit-tests a point against door rects', () => {
    expect(doorAt(room, 60, 85)?.id).toBe('front-door')
    expect(doorAt(room, 0, 0)).toBeUndefined()
  })

  it('falls back to the room spawn when no door id is given', () => {
    expect(entrySpawnFor(room)).toEqual({ x: 64, y: 64 })
  })

  it('falls back to the room spawn when the door has no spawn override', () => {
    expect(entrySpawnFor(room, 'front-door')).toEqual({ x: 64, y: 64 })
  })

  it('uses a door-specific spawn when present', () => {
    const withSpawn = parseRoomMap(minimalRoom({
      doors: [{ id: 'front-door', rect: { x: 48, y: 80, w: 32, h: 16 }, target: 'town', spawn: { x: 5, y: 5 } }],
    }))
    expect(entrySpawnFor(withSpawn, 'front-door')).toEqual({ x: 5, y: 5 })
  })

  it('ignores an unknown door id and falls back to the room spawn', () => {
    expect(entrySpawnFor(room, 'nope')).toEqual({ x: 64, y: 64 })
  })
})

describe('interactableAt', () => {
  const room: RoomMapData = parseRoomMap(minimalRoom({
    furniture: [
      { id: 'sofa', frame: 'sofa_1', x: 100, y: 100 },
      { id: 'desk', frame: 'desk_1', x: 60, y: 60, interactive: { actionId: 'home.open-desk', label: '打开书桌' } },
    ],
  }))

  it('finds the interactive furniture whose default (tileSize) hit-box contains the point', () => {
    // tileSize=32, so the desk's box is x:[44,76] y:[28,60] (bottom-center at 60,60).
    expect(interactableAt(room, 60, 40)?.id).toBe('desk')
  })

  it('ignores furniture without an interactive field even if the point lands on it', () => {
    expect(interactableAt(room, 100, 90)).toBeUndefined()
  })

  it('misses once the point falls outside the hit-box', () => {
    expect(interactableAt(room, 0, 0)).toBeUndefined()
  })

  it('respects an explicit hit-box instead of the tileSize default', () => {
    const withHit = parseRoomMap(minimalRoom({
      furniture: [{ id: 'wall-plaque', frame: 'board_1', x: 10, y: 10, interactive: { actionId: 'home.open-achievement-wall', hit: { x: 200, y: 200, w: 10, h: 10 } } }],
    }))
    expect(interactableAt(withHit, 205, 205)?.id).toBe('wall-plaque')
    expect(interactableAt(withHit, 10, 10)).toBeUndefined() // its own (x,y) is nowhere near the overridden hit-box
  })
})

describe('collidesAt', () => {
  const room: RoomMapData = parseRoomMap(minimalRoom({ collisions: [{ x: 32, y: 32, w: 32, h: 32 }] }))

  it('reports a collision when the box overlaps a rect', () => {
    expect(collidesAt(room, 48, 48, 8, 16)).toBe(true)
  })

  it('reports no collision well outside every rect', () => {
    expect(collidesAt(room, 500, 500, 8, 16)).toBe(false)
  })

  it('treats the box as edge-exclusive so grazing the boundary does not count', () => {
    expect(collidesAt(room, 16, 32, 0, 0)).toBe(false)
  })
})

describe('stepAxis', () => {
  it('moves toward the target without overshooting', () => {
    expect(stepAxis(0, 10, 4)).toBe(4)
    expect(stepAxis(8, 10, 4)).toBe(10)
  })

  it('returns the current value unchanged once it matches the target', () => {
    expect(stepAxis(5, 5, 4)).toBe(5)
  })

  it('moves the other direction when the target is behind', () => {
    expect(stepAxis(10, 0, 4)).toBe(6)
  })

  it('treats a negative maxDelta as zero movement', () => {
    expect(stepAxis(0, 10, -4)).toBe(0)
  })
})

describe('clampToRoom', () => {
  const room: RoomMapData = parseRoomMap(minimalRoom())

  it('leaves an in-bounds point untouched', () => {
    expect(clampToRoom(room, 64, 32)).toEqual({ x: 64, y: 32 })
  })

  it('clamps a point outside the room to its pixel bounds', () => {
    expect(clampToRoom(room, -10, 9999)).toEqual({ x: 0, y: room.rows * room.tileSize })
  })
})
