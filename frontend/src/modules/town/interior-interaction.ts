import { canStand, type CollisionWorld, type Point } from './collision'
import { clearSegment, findPath } from './pathfinding'
import { defaultInteractionHit, type RoomFurniture, type RoomMapData, type RoomRect } from './map-loader'

/** The same foot clearance is used for walking and choosing where to use furniture. */
export function roomNavigation(room: RoomMapData): CollisionWorld {
  return {
    walkable: [{ x: 7, y: 10, width: room.cols * room.tileSize - 14, height: room.rows * room.tileSize - 20 }],
    obstacles: room.collisions.map(r => ({ x: r.x - 7, y: r.y, width: r.w + 14, height: r.h + 10 })),
  }
}

export function distanceToBox(point: Point, box: RoomRect): number {
  return Math.hypot(Math.max(box.x - point.x, 0, point.x - box.x - box.w), Math.max(box.y - point.y, 0, point.y - box.y - box.h))
}

/** Find a reachable point just outside a prop, never treating an unreachable path as arrival.
 * Search both the front and sides: wall decorations and tables have very different footprints. */
export function furnitureApproach(room: RoomMapData, from: Point, piece: RoomFurniture, frontOnly = false): Point | null {
  const world = roomNavigation(room)
  const box = piece.interactive?.hit ?? defaultInteractionHit(piece, room.tileSize)
  for (let padding = 16; padding <= 48; padding += 16) {
    const candidates: Point[] = [{ x: piece.x, y: piece.y + padding }]
    for (const fraction of frontOnly ? [] : [0.5, 0, 1]) {
      candidates.push(
        { x: box.x + box.w * fraction, y: box.y + box.h + padding },
        { x: box.x - padding, y: box.y + box.h * fraction },
        { x: box.x + box.w + padding, y: box.y + box.h * fraction },
        { x: box.x + box.w * fraction, y: box.y - padding },
      )
    }
    candidates.sort((a, b) => Math.hypot(a.x - from.x, a.y - from.y) - Math.hypot(b.x - from.x, b.y - from.y))
    for (const candidate of candidates) {
      if (canStand(candidate, world) && findPath(from, candidate, world)) return candidate
    }
  }
  return null
}

/** Rest only on an authored, unoccupied front-facing chair. No invented furniture or rewards. */
export function restingFurniture(room: RoomMapData, occupiedSeats: Point[]): RoomFurniture[] {
  return room.furniture.filter(piece => !piece.interactive
    && ['loveseat_wood', 'armchair_blue', 'armchair_wood', 'cafe_chair', 'chair_2'].includes(piece.frame)
    && !occupiedSeats.some(seat => Math.hypot(seat.x - piece.x, seat.y - piece.y) < 24))
}

export function canUseFromHere(room: RoomMapData, from: Point, target: Point): boolean {
  return Math.hypot(from.x - target.x, from.y - target.y) <= 8 && clearSegment(from, target, roomNavigation(room))
}


/** Clicking the visible body of a tall exercise machine means approaching its usable front,
 * not choosing the geometrically closest point hidden behind its console. Undefined means
 * ordinary floor; null means equipment was hit but no legal front approach exists. */
export function equipmentApproach(
  room: RoomMapData,
  from: Point,
  clicked: Point,
  frameSize: (frame: string) => { width: number; height: number } | null,
): Point | null | undefined {
  const frames = new Set(['treadmill_1', 'gym_elliptical_1', 'gymbike_1', 'gym_strength_machine_1'])
  const piece = [...room.furniture].reverse().find(item => {
    if (!frames.has(item.frame) || item.interactive) return false
    const size = frameSize(item.frame)
    if (!size) return false
    const width = item.displayWidth ?? size.width, height = item.displayHeight ?? size.height
    const left = item.x - width * (item.originX ?? .5), top = item.y - height * (item.originY ?? 1)
    return clicked.x >= left && clicked.x <= left + width && clicked.y >= top && clicked.y <= top + height
  })
  return piece ? furnitureApproach(room, from, piece, true) : undefined
}
