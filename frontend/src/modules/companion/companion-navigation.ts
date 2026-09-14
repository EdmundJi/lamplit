import type { CollisionWorld, Point, Rect } from '../../shared/scene/collision'
import { canStand, nearestStandable } from '../../shared/scene/collision'
import { findPath } from '../../shared/scene/pathfinding'
import { CAFE_TABLES, CAFE_WINDOW_SEATS, CAFE_ROOM, CAFE_WINDOW_ROOM, HOME_ROOMS, HOUSEHOLD_MEMBERS, GARDEN_OFFSET_X, ACADEMY_ROOM, GYM_ROOM, BOARD_AREA, SHOP_ROOM, type ScenePlaceId } from './companion-art'
/** Feet navigation mirrors the cutaway floors, open doorways and actual furniture footprint. */
const HOME_INTERIORS = Object.values(HOME_ROOMS).map(room => ({ x: room.x + 8, y: room.y + 32, width: room.w - 16, height: room.h - 32 }))
const HOME_DOORS = Object.values(HOME_ROOMS).map(room => ({ x: room.door.x - 16, y: room.y + room.h, width: 32, height: 40 }))
const SHARED_HOME_WALLS: Rect[] = Object.entries(HOUSEHOLD_MEMBERS).flatMap(([homeId, members]) => {
  const room = HOME_ROOMS[homeId]!, bedroomWidth = (room.w - 16) / members.length
  const walls: Rect[] = []
  for (let split = 1; split < members.length; split++) walls.push({ x: room.x + 8 + bedroomWidth * split - 2, y: room.y + 38, width: 4, height: 136 })
  members.forEach((_, index) => {
    const left = room.x + 8 + bedroomWidth * index, center = left + bedroomWidth / 2
    walls.push({ x: left, y: room.y + 174, width: Math.max(0, center - 16 - left), height: 4 })
    walls.push({ x: center + 16, y: room.y + 174, width: Math.max(0, left + bedroomWidth - center - 16), height: 4 })
  })
  for (const x of [room.x + 106, room.x + 194]) {
    walls.push({ x, y: room.y + 178, width: 3, height: 14 })
    walls.push({ x, y: room.y + 211, width: 3, height: 9 })
  }
  return walls.filter(wall => wall.width > 0)
})
export const COMPANION_COLLISION: CollisionWorld = {
  walkable: [
    ...HOME_INTERIORS, ...HOME_DOORS,
    { x: CAFE_ROOM.x + 8, y: CAFE_ROOM.y + 32, width: CAFE_ROOM.w - 16, height: CAFE_ROOM.h - 32 },
    { x: CAFE_WINDOW_ROOM.x + 8, y: CAFE_WINDOW_ROOM.y + 32, width: CAFE_WINDOW_ROOM.w - 16, height: CAFE_WINDOW_ROOM.h - 32 },
    { x: 848, y: 44, width: 40, height: 284 }, // open connection between the main room and window wing
    { x: CAFE_ROOM.doorX - 16, y: 328, width: 32, height: 40 },
    { x: ACADEMY_ROOM.x + 8, y: ACADEMY_ROOM.y + 32, width: ACADEMY_ROOM.w - 16, height: ACADEMY_ROOM.h - 32 },
    { x: GYM_ROOM.x + 8, y: GYM_ROOM.y + 32, width: GYM_ROOM.w - 16, height: GYM_ROOM.h - 32 },
    { x: SHOP_ROOM.x + 8, y: SHOP_ROOM.y + 32, width: SHOP_ROOM.w - 16, height: SHOP_ROOM.h - 32 },
    { x: ACADEMY_ROOM.doorX - 16, y: ACADEMY_ROOM.y + ACADEMY_ROOM.h - 4, width: 32, height: 48 },
    { x: GYM_ROOM.doorX - 16, y: GYM_ROOM.y + GYM_ROOM.h - 4, width: 32, height: 48 },
    { x: SHOP_ROOM.doorX - 16, y: SHOP_ROOM.y + SHOP_ROOM.h - 4, width: 32, height: 48 },
    { x: BOARD_AREA.x, y: BOARD_AREA.y, width: BOARD_AREA.w, height: BOARD_AREA.h },
    { x: 32, y: 364, width: 816, height: 96 }, { x: 746 + GARDEN_OFFSET_X, y: 230, width: 184, height: 240 },
    { x: 816, y: 424, width: 32, height: 168 }, { x: 816, y: 560, width: 256, height: 32 }, { x: 1040, y: 424, width: 32, height: 168 },
    // The lower houses are reachable through a modest side lane rather than teleporting through
    // their walls. These three paths are deliberately plain pavement; room identity stays in the
    // backend location id, not in a fake job-specific destination.
    // The alley's horizontal leg is widened (640 -> 1170px) to also pass under fixer's new house
    // in the bottom-right corner (doorstep at x:1186-1218) instead of stopping at the old x=688 -
    // matched pixel-for-pixel in companion-stage.ts's drawn path so the walkway and the collision
    // rect never disagree (the known "invisible wall" hazard).
    { x: 48, y: 416, width: 32, height: 320 }, { x: 48, y: 708, width: 1170, height: 32 }, { x: 656, y: 416, width: 32, height: 320 },
    // Second-version town spine: the old lower lane continues through the shop, then turns south
    // between the two five-house rows. Every new front door meets one of the two cross streets.
    { x: 1218, y: 708, width: 782, height: 32 },
    { x: 952, y: 708, width: 32, height: 672 },
    { x: 32, y: 1052, width: 1556, height: 32 },
    { x: 32, y: 1348, width: 1556, height: 32 },
    { x: ACADEMY_ROOM.doorX - 16, y: 256, width: 32, height: 228 },
    { x: GYM_ROOM.doorX - 16, y: 436, width: 32, height: 276 },
  ],
  obstacles: [
    ...SHARED_HOME_WALLS,
    ...Object.entries(HOME_ROOMS).filter(([id]) => !HOUSEHOLD_MEMBERS[id]).flatMap(([, room]) => [
      { x: room.x + 18, y: room.y + 46, width: 28, height: 83 },
      // Keep the tabletop solid while leaving the side chair as a destination rather than a wall;
      // otherwise bed + chair + table form an impassable strip across the narrow upper homes.
      { x: room.x + room.w - 50, y: room.y + 144, width: 38, height: 24 },
    ]),
    ...CAFE_TABLES.flatMap(table => [
      { x: table.x - 30, y: table.y - 25, width: 60, height: 25 },
      ...table.seats.map(seat => ({ x: seat.x - 12, y: seat.y - 24, width: 24, height: 18 })),
    ]),
    // The service counter is a real barrier; staff reach its rear via the left end.
    { x: 622, y: 147, width: 224, height: 32 },
    { x: 630, y: 55, width: 208, height: 25 },
    { x: 589, y: 46, width: 32, height: 32 }, { x: 394, y: 72, width: 31, height: 27 },
    // Joined window desktops are one solid edge, with a separate aisle behind the chairs.
    { x: 976, y: 105, width: 45, height: 414 },
    ...CAFE_WINDOW_SEATS.map(seat => ({ x: seat.x - 12, y: seat.y - 24, width: 24, height: 18 })),
    ...[798, 859].flatMap(x => [248, 328].map(y => ({ x: x + GARDEN_OFFSET_X, y, width: 49, height: 40 }))),
    // Public equipment clusters stay solid; their named position anchors sit next to the object.
    { x: ACADEMY_ROOM.x + 112, y: ACADEMY_ROOM.y + 130, width: 76, height: 32 },
    { x: GYM_ROOM.x + 32, y: GYM_ROOM.y + 66, width: 150, height: 34 },
    { x: SHOP_ROOM.x + 330, y: SHOP_ROOM.y + 178, width: 70, height: 30 },
  ],
}
const STAFF_AISLE: Rect = { x: 622, y: 80, width: 224, height: 67 }
const inStaffAisle = (point: Point) => point.x >= STAFF_AISLE.x && point.x <= STAFF_AISLE.x + STAFF_AISLE.width && point.y >= STAFF_AISLE.y && point.y <= STAFF_AISLE.y + STAFF_AISLE.height
export function companionPath(from: Point, to: Point) {
  // A trip to/from the machine can use its working aisle. Seat-to-seat and guest entry paths
  // take the public side of the counter, even when the newly extended wing offers a shorter cut.
  const world = inStaffAisle(from) || inStaffAisle(to) ? COMPANION_COLLISION : { ...COMPANION_COLLISION, obstacles: [...COMPANION_COLLISION.obstacles, STAFF_AISLE] }
  return findPath(from, to, world) ?? []
}

/**
 * "能站的地方都能去" (docs/04-decisions.md). Once the backend leaves `positionId` null for a
 * resident who is merely standing, chatting or passing through - the normal case now that a
 * position is reserved for real occupancy (a bed, the coffee machine, a window seat) - the
 * frontend picks their pixel itself, inside the room/street/garden they are actually in. Insets
 * (vs. the raw COMPANION_COLLISION walkable rects above) keep the search away from the outer
 * walls even where no obstacle rect happens to cover the last few pixels of floor.
 */
const PLACE_STANDING_AREA: Record<ScenePlaceId, Rect> = {
  home: { x: HOME_ROOMS.owner!.x + 18, y: HOME_ROOMS.owner!.y + HOME_ROOMS.owner!.h - 20, width: HOME_ROOMS.owner!.w - 36, height: 8 },
  cafe: { x: 554, y: 210, width: 222, height: 108 },
  garden: { x: 760 + GARDEN_OFFSET_X, y: 244, width: 156, height: 212 },
  street: { x: 54, y: 378, width: 776, height: 70 },
  academy: { x: ACADEMY_ROOM.x + 22, y: ACADEMY_ROOM.y + 62, width: ACADEMY_ROOM.w - 44, height: ACADEMY_ROOM.h - 88 },
  gym: { x: GYM_ROOM.x + 22, y: GYM_ROOM.y + 62, width: GYM_ROOM.w - 44, height: GYM_ROOM.h - 88 },
  board: { x: BOARD_AREA.x + 18, y: BOARD_AREA.y + 24, width: BOARD_AREA.w - 36, height: BOARD_AREA.h - 42 },
  shop: { x: SHOP_ROOM.x + 28, y: SHOP_ROOM.y + 64, width: SHOP_ROOM.w - 56, height: SHOP_ROOM.h - 92 },
}

function homeStandingArea(place: string): Rect | undefined {
  const room = HOME_ROOMS[place.replace(/^home[-./]?/, '')]
  return room ? { x: room.x + 18, y: room.y + room.h - 20, width: room.w - 36, height: 8 } : undefined
}

/** Minimum distance (px) kept between two free-standing residents so nobody visually overlaps -
 * roughly one character's silhouette width plus a little breathing room. */
const FREE_STAND_SPACING = 32

/**
 * Small, fast, deterministic string hash (FNV-1a). Used only to scatter residents across a
 * place's floor - never Math.random(): the whole point is that the same input always produces
 * the same pixel, so a resident does not jump around every time the scene re-renders or the page
 * is refreshed.
 */
function hash32(input: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

/** Deterministically maps `seed` to one point inside `rect`. */
function hashedPointInRect(seed: string, rect: Rect): Point {
  const h = hash32(seed)
  const hx = h & 0xffff
  const hy = (h >>> 16) & 0xffff
  return { x: rect.x + (hx / 0xffff) * rect.width, y: rect.y + (hy / 0xffff) * rect.height }
}

/**
 * Picks a stable, spread-out standing spot for `residentId` inside `place`.
 *
 * Stable: the resident's own id (never the resident's array index or position - both shift
 * whenever someone else enters or leaves) seeds a hash that always lands on the same primary
 * spot for that resident in that place. A refresh, or another resident coming and going
 * elsewhere, does not move them.
 *
 * Spread: `occupied` is the list of pixels every OTHER resident currently visible in this same
 * place has already settled on (whatever put them there - another free-standing pick, a bed, a
 * desk seat). If this resident's primary spot would land within FREE_STAND_SPACING of one of
 * those, later hash "attempts" (still pure functions of the id, just salted by attempt number)
 * are tried in a fixed order until one clears every occupied point and every obstacle - so a
 * newcomer never lands on the room's one popular hashed corner along with everyone else.
 */
export function freeStandPosition(place: string, residentId: string, occupied: Point[] = []): Point {
  const area = homeStandingArea(place) ?? PLACE_STANDING_AREA[place as keyof typeof PLACE_STANDING_AREA] ?? PLACE_STANDING_AREA.street
  for (let attempt = 0; attempt < 32; attempt++) {
    const candidate = hashedPointInRect(`${place}:${residentId}:${attempt}`, area)
    if (canStand(candidate, COMPANION_COLLISION) && occupied.every(p => Math.hypot(p.x - candidate.x, p.y - candidate.y) >= FREE_STAND_SPACING)) return candidate
  }
  // Every place has open ground; if every salted attempt above still collided (a packed room),
  // settle for the closest standable point to the resident's own primary spot, constrained to
  // this same place's area, rather than leaving them stuck on furniture or off the map.
  return nearestStandable(hashedPointInRect(`${place}:${residentId}:0`, area), { walkable: [area], obstacles: COMPANION_COLLISION.obstacles })
}
