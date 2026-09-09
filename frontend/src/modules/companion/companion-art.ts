/** Action-sheet geometry from scripts/build-companion-assets.py; every action shares a foot anchor. */
export const ACTION_FRAME = { width: 96, height: 96, originX: 32 / 96, originY: 80 / 96 }
export const RESIDENT_ART = [1, 3, 6, 9, 12] as const
export type CafeSeat = { x: number; y: number; facing: 'left' | 'right' }
/** Compact two-tops with real opposing seats. All coordinates are residents' foot anchors. */
export const CAFE_TABLES = [
  { x: 480, y: 176, seats: [{ x: 438, y: 176, facing: 'right' }, { x: 522, y: 176, facing: 'left' }] },
  { x: 480, y: 282, seats: [{ x: 438, y: 282, facing: 'right' }, { x: 522, y: 282, facing: 'left' }] },
] as const
export const CAFE_WORK_SEATS = CAFE_TABLES.flatMap(table => [...table.seats])
/** Six joined window worktops. Keep the original owned position id; added positions are public. */
export const CAFE_WINDOW_SEAT: CafeSeat = { x: 964, y: 292, facing: 'right' }
export const CAFE_WINDOW_SEATS: CafeSeat[] = [CAFE_WINDOW_SEAT, ...[154, 223, 361, 430, 499].map(y => ({ x: 964, y, facing: 'right' as const }))]
export const CAFE_WINDOW_TABLES = [174, 243, 312, 381, 450, 519].map(y => ({ x: 998, y }))
/** The original public room joins a long, quiet window wing without moving the service counter. */
export const CAFE_ROOM = { x: 384, y: 12, w: 480, h: 320, doorX: 682 } as const
export const CAFE_WINDOW_ROOM = { x: 864, y: 12, w: 160, h: 536 } as const
export const CAFE_SERVICE = {
  entry: { x: 682, y: 350 }, operator: { x: 730, y: 123 }, staffEntry: { x: 598, y: 123 },
  order: { x: 682, y: 215 }, pickup: { x: 798, y: 215 },
  waiting: [{ x: 740, y: 257 }, { x: 782, y: 257 }, { x: 704, y: 295 }, { x: 746, y: 295 }],
  conversation: { x: 632, y: 276 },
} as const
export const GARDEN_OFFSET_X = 288
export const CAFE_SEATS: CafeSeat[] = [...CAFE_WORK_SEATS, ...CAFE_WINDOW_SEATS]
export function cafeSeatAt(point: { x: number; y: number }) {
  return CAFE_SEATS.find(seat => Math.abs(seat.x - point.x) < .5 && Math.abs(seat.y - point.y) < .5)
}

export const COMPANION_WORLD_SIZE = { width: 1248, height: 768 } as const
export interface HomeRoom {
  x: number; y: number; w: number; h: number
  /** An open standing point, the front doorstep, and the owned bed's foot anchor. */
  anchor: { x: number; y: number }
  door: { x: number; y: number }
  bed: { x: number; y: number }
  desk: { x: number; y: number }
}
function homeRoom(x: number, y: number, w: number, h: number): HomeRoom {
  return { x, y, w, h, anchor: { x: x + w - 38, y: y + h - 20 },
    door: { x: x + w - 38, y: y + h + 16 }, bed: { x: x + 32, y: y + 144 },
    // Side chair at the writing table. The character faces right toward the tabletop instead of
    // standing on its centre and reading toward the camera.
    desk: { x: x + w - 72, y: y + 168 } }
}
/** Five actual rooms and five entrances; keys are stable resident ids, not their current jobs. */
export const HOME_ROOMS: Record<string, HomeRoom> = {
  owner: homeRoom(80, 108, 128, 224), student: homeRoom(216, 108, 128, 224),
  artist: homeRoom(96, 492, 160, 208), gardener: homeRoom(288, 492, 160, 208),
  self: homeRoom(480, 492, 160, 208),
}
export const PLACE_FRAMES = {
  home: { x: 72, y: 96, w: 576, h: 628 }, cafe: { x: 372, y: 0, w: 664, h: 576 },
  garden: { x: 1036, y: 192, w: 184, h: 274 }, street: { x: 32, y: 344, w: 816, h: 132 },
} as const

/**
 * The single pixel truth for the backend's two-layer place model (TownPlaces.java): one entry per
 * positionId, holding one seat per unit of that position's backend `capacity`, first-come order.
 * The backend owns structure/ownership/capacity; this table owns where feet land. Every array here
 * should be at least as long as the matching backend capacity so no occupant is left without a
 * pixel. companion-scene.ts falls back to the older place+index guess (below, in residentPosition)
 * for any positionId missing here - an old save, or a position the artist hasn't placed yet.
 */
export const POSITION_SLOTS: Record<string, { x: number; y: number }[]> = {
  // Every unit of the shared worktable's capacity has a visible wooden chair.
  'cafe-worktable': CAFE_WORK_SEATS.map(({ x, y }) => ({ x, y })),
  // The owner's own counter (capacity 1, equipment): where coffee actually gets made. Added after
  // the backend grew cafe-counter; without a slot here the owner falls back to the by-index layout.
  'cafe-counter': [CAFE_SERVICE.operator],
  // The student's own window seat (capacity 1) - a distinct single spot, not one of the desks.
  'cafe-window-seat': [{ x: CAFE_WINDOW_SEAT.x, y: CAFE_WINDOW_SEAT.y }],
  'cafe-window-2': [{ x: CAFE_WINDOW_SEATS[1]!.x, y: CAFE_WINDOW_SEATS[1]!.y }],
  'cafe-window-3': [{ x: CAFE_WINDOW_SEATS[2]!.x, y: CAFE_WINDOW_SEATS[2]!.y }],
  'cafe-window-4': [{ x: CAFE_WINDOW_SEATS[3]!.x, y: CAFE_WINDOW_SEATS[3]!.y }],
  'cafe-window-5': [{ x: CAFE_WINDOW_SEATS[4]!.x, y: CAFE_WINDOW_SEATS[4]!.y }],
  'cafe-window-6': [{ x: CAFE_WINDOW_SEATS[5]!.x, y: CAFE_WINDOW_SEATS[5]!.y }],
  // Public street bench (capacity 4).
  'street-bench': [{ x: 274, y: 454 }, { x: 306, y: 454 }, { x: 484, y: 454 }, { x: 516, y: 454 }],
  // Public garden bench (capacity 3), distinct from the tended plots.
  'garden-bench': [{ x: 1109, y: 439 }, { x: 1137, y: 439 }, { x: 1165, y: 439 }],
  // The gardener's own tended plot (capacity 1) - a specific bed of soil, not the shared bench.
  'garden-plot': [{ x: 1137, y: 356 }],
  // Each of the five residents' own bed, in their own home - the fix for "everyone sleeps in the
  // same bed": every id below is now a distinct, non-overlapping spot.
  'home-owner-bed': [HOME_ROOMS.owner!.bed],
  'home-student-bed': [HOME_ROOMS.student!.bed],
  'home-artist-bed': [HOME_ROOMS.artist!.bed],
  'home-gardener-bed': [HOME_ROOMS.gardener!.bed],
  // The avatar has its own room and bed, like every other resident.
  'home-self-bed': [HOME_ROOMS.self!.bed],
  // Reserved visual contract for home work; the backend decides when a desk is occupied.
  'home-owner-desk': [HOME_ROOMS.owner!.desk],
  'home-student-desk': [HOME_ROOMS.student!.desk],
  'home-artist-desk': [HOME_ROOMS.artist!.desk],
  'home-gardener-desk': [HOME_ROOMS.gardener!.desk],
  'home-self-desk': [HOME_ROOMS.self!.desk],
}

export const RESIDENT_ACTIONS = {
  create: { frames: Array.from({ length: 14 }, (_, i) => i), frameRate: 5 },
  drink: { frames: Array.from({ length: 14 }, (_, i) => 14 + i), frameRate: 3 },
  // Lower the can after pouring; do not play the water stream backwards.
  garden: { frames: [...Array.from({ length: 14 }, (_, i) => 28 + i), 32, 31, 30, 29, 28, 28, 28], frameRate: 6 },
} as const
export function isArtAction(mode: string): mode is keyof typeof RESIDENT_ACTIONS {
  return Object.hasOwn(RESIDENT_ACTIONS, mode)
}
