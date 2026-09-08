import type { CollisionWorld, Point, Rect } from '../../shared/scene/collision'
import { canStand, nearestStandable } from '../../shared/scene/collision'
import { findPath } from '../../shared/scene/pathfinding'
/** Feet navigation mirrors the cutaway floors, open doorways and actual furniture footprint. */
export const COMPANION_COLLISION: CollisionWorld = {
  walkable: [
    { x: 88, y: 180, width: 240, height: 149 }, { x: 188, y: 319, width: 32, height: 83 },
    { x: 392, y: 183, width: 336, height: 146 }, { x: 519, y: 319, width: 32, height: 83 },
    { x: 40, y: 364, width: 884, height: 98 }, { x: 746, y: 230, width: 184, height: 240 },
  ],
  obstacles: [
    ...[108, 148].map(x => ({ x: x - 14, y: 138, width: 28, height: 84 })),
    ...[252, 296].map(x => ({ x: x - 14, y: 124, width: 28, height: 84 })),
    { x: 184, y: 142, width: 7, height: 78 },
    { x: 95, y: 255, width: 66, height: 36 }, { x: 172, y: 269, width: 35, height: 17 },
    { x: 245, y: 245, width: 59, height: 43 }, { x: 258, y: 300, width: 25, height: 20 },
    { x: 411, y: 185, width: 58, height: 69 }, { x: 499, y: 185, width: 58, height: 69 }, { x: 587, y: 185, width: 58, height: 69 },
    { x: 428, y: 262, width: 24, height: 18 }, { x: 516, y: 262, width: 24, height: 18 }, { x: 604, y: 262, width: 24, height: 18 },
    { x: 798, y: 248, width: 49, height: 40 }, { x: 859, y: 248, width: 49, height: 40 },
    { x: 798, y: 328, width: 49, height: 40 }, { x: 859, y: 328, width: 49, height: 40 },
  ],
}
export function companionPath(from: Point, to: Point) { return findPath(from, to, COMPANION_COLLISION) ?? [] }

/**
 * "能站的地方都能去" (docs/04-decisions.md). Once the backend leaves `positionId` null for a
 * resident who is merely standing, chatting or passing through - the normal case now that a
 * position is reserved for real occupancy (a bed, the coffee machine, a window seat) - the
 * frontend picks their pixel itself, inside the room/street/garden they are actually in. Insets
 * (vs. the raw COMPANION_COLLISION walkable rects above) keep the search away from the outer
 * walls even where no obstacle rect happens to cover the last few pixels of floor.
 */
const PLACE_STANDING_AREA: Record<'home' | 'cafe' | 'garden' | 'street', Rect> = {
  home: { x: 102, y: 194, width: 212, height: 121 },
  cafe: { x: 406, y: 197, width: 308, height: 118 },
  garden: { x: 760, y: 244, width: 156, height: 212 },
  street: { x: 54, y: 378, width: 856, height: 70 },
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
  const area = PLACE_STANDING_AREA[place as keyof typeof PLACE_STANDING_AREA] ?? PLACE_STANDING_AREA.street
  for (let attempt = 0; attempt < 32; attempt++) {
    const candidate = hashedPointInRect(`${place}:${residentId}:${attempt}`, area)
    if (canStand(candidate, COMPANION_COLLISION) && occupied.every(p => Math.hypot(p.x - candidate.x, p.y - candidate.y) >= FREE_STAND_SPACING)) return candidate
  }
  // Every place has open ground; if every salted attempt above still collided (a packed room),
  // settle for the closest standable point to the resident's own primary spot, constrained to
  // this same place's area, rather than leaving them stuck on furniture or off the map.
  return nearestStandable(hashedPointInRect(`${place}:${residentId}:0`, area), { walkable: [area], obstacles: COMPANION_COLLISION.obstacles })
}
