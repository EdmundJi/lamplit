import { existsSync, readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { canStand } from './collision'
import { clearSegment, findPath } from './pathfinding'
import { parseRoomMap, type RoomMapData } from './map-loader'
import { furnitureApproach, restingFurniture, roomNavigation } from './interior-interaction'

const loadRoom = (id: string): RoomMapData => parseRoomMap(JSON.parse(readFileSync(`public/assets/town/maps/${id}.json`, 'utf8')))

describe('interior furniture navigation', () => {
  for (const id of ['home-living-room', 'cafe-interior', 'public-gym', 'academy-study']) {
    it.skipIf(!existsSync(`public/assets/town/maps/${id}.json`))(`${id}: reaches all usable furniture and unoccupied seats without crossing collisions`, () => {
      const room = loadRoom(id)
      const world = roomNavigation(room)
      const props = room.furniture.filter(piece => piece.interactive)
      const seats = restingFurniture(room, [])
      for (const piece of [...props, ...seats]) {
        const target = furnitureApproach(room, room.spawn, piece, seats.includes(piece))
        expect(target, piece.id).not.toBeNull()
        expect(canStand(target!, world), piece.id).toBe(true)
        const path = findPath(room.spawn, target!, world)!
        let previous = room.spawn
        for (const point of path) {
          expect(clearSegment(previous, point, world), piece.id).toBe(true)
          previous = point
        }
      }
    })
  }

  it('does not turn a disconnected furniture destination into an immediate action', () => {
    const room = simpleRoom()
    room.collisions.push({ x: 240, y: 0, w: 32, h: 448 })
    const seat = room.furniture.find(piece => piece.id === 'loveseat')!
    expect(furnitureApproach(room, room.spawn, seat)).toBeNull()
  })

  it('excludes occupied chairs, business props and furniture with no supported seat art', () => {
    const room = simpleRoom()
    expect(restingFurniture(room, [{ x: 120, y: 186 }])).toEqual([])
    expect(restingFurniture(room, []).map(piece => piece.id)).toEqual(['loveseat'])
  })
})

function simpleRoom(): RoomMapData {
  return {
    id: 'test-room', title: 'Room', cols: 20, rows: 14, tileSize: 32, backgroundColor: '#000',
    spawn: { x: 320, y: 380 }, layers: { floor: [], walls: [] }, seats: [], doors: [], slots: [],
    collisions: [{ x: 0, y: 0, w: 640, h: 96 }, { x: 62, y: 152, w: 116, h: 26 }],
    furniture: [{ id: 'loveseat', frame: 'loveseat_wood', x: 120, y: 180 },
      { id: 'desk', frame: 'desk_1', x: 500, y: 360, interactive: { actionId: 'home.open-desk' } }],
  }
}
