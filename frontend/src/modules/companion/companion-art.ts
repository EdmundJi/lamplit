/** Action-sheet geometry from scripts/build-companion-assets.py; every action shares a foot anchor. */
export const ACTION_FRAME = { width: 96, height: 96, originX: 32 / 96, originY: 80 / 96 }
// [1, 3, 6, 9, 12] have bespoke create/drink/garden action sheets (c0N-actions.png); 4 and 7 do
// not - they only have the generic walk/idle/sleep/sit/read sheet that exists for all 20 base
// characters. That is fine: sync() already guards every action-sheet animation behind
// `this.textures.exists(`${sheet}-actions`)`, so fixer/weaver (whichever index lands on 4 or 7)
// simply fall back to the native idle/read/sit poses instead of a missing/floating action frame.
// Length must match the total actor count (avatar + residents) so every one of the seven gets a
// distinct sprite by position, the same guarantee the original five-actor roster had.
export const RESIDENT_ART = [1, 3, 6, 9, 12, 4, 7] as const
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

// Widened (never moved) to fit board/academy/gym - see tmp/town-stage-extension.md section 3.
// Every rectangle that already existed at 1248x768 (HOME_ROOMS, the original four PLACE_FRAMES,
// CAFE_SEATS, POSITION_SLOTS, RAIN_SHELTERS) keeps its exact old coordinates; the three new
// buildings below live entirely past the old x=1248 right edge, in the newly added strip, so
// nothing already drawn had to shift.
export const COMPANION_WORLD_SIZE = { width: 1548, height: 768 } as const
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
/** Six actual rooms and six entrances; keys are stable resident ids, not their current jobs.
 * `fixer` (周野) is the one new house this round: the only gap left in the 1248x768 canvas that
 * fits a full room + walls + doorstep path (about 176x264, measured by rasterising every
 * occupied rect) sits at x=1072,y=466 with 176x302 usable, below the garden - so this room and
 * its wall/door bleed are sized to land inside that box with a couple of px of margin on every
 * side, never past the canvas edge. `weaver` (阿满) is deliberately absent here: she shares
 * 知夏's `artist` room as a flat-mate rather than getting a new building - see her bed/desk in
 * POSITION_SLOTS below, placed inside this same room but clear of its furniture. */
export const HOME_ROOMS: Record<string, HomeRoom> = {
  owner: homeRoom(80, 108, 128, 224), student: homeRoom(216, 108, 128, 224),
  artist: homeRoom(96, 492, 160, 208), gardener: homeRoom(288, 492, 160, 208),
  self: homeRoom(480, 492, 160, 208), fixer: homeRoom(1080, 480, 160, 224),
}
/**
 * Three new buildings east of the original 1248-wide canvas (the new strip runs x=1248..1548).
 * Academy and gym are real rooms drawn with the same `room()` helper as the homes/cafe; the board
 * is a small open-air plaza (a bulletin board needs a paved patch, not four walls), so it has no
 * `doorX`. Placed with the same ~28px alley width used elsewhere (e.g. student's home to the
 * cafe) between them and fixer's house/garden, and stacked with 40px gaps between each other so a
 * single vertical path can connect academy's doorway, through the board's plaza, into the gym's
 * doorway, down to the street's main bottom path - see the connecting `path()` calls in
 * companion-stage.ts.
 */
export const ACADEMY_ROOM = { x: 1268, y: 40, w: 260, h: 220, doorX: 1398 } as const
export const GYM_ROOM = { x: 1268, y: 480, w: 260, h: 220, doorX: 1398 } as const
export const BOARD_AREA = { x: 1268, y: 300, w: 200, h: 140 } as const
export const PLACE_FRAMES = {
  home: { x: 72, y: 96, w: 576, h: 628 }, cafe: { x: 372, y: 0, w: 664, h: 576 },
  garden: { x: 1036, y: 192, w: 184, h: 274 }, street: { x: 32, y: 344, w: 816, h: 132 },
  academy: { x: ACADEMY_ROOM.x, y: ACADEMY_ROOM.y, w: ACADEMY_ROOM.w, h: ACADEMY_ROOM.h },
  gym: { x: GYM_ROOM.x, y: GYM_ROOM.y, w: GYM_ROOM.w, h: GYM_ROOM.h },
  board: { x: BOARD_AREA.x, y: BOARD_AREA.y, w: BOARD_AREA.w, h: BOARD_AREA.h },
} as const

/**
 * The single registry every "place" concept in the shell reads from: the docked street strip's
 * camera targets, its place labels, and (later) the sidebar nav's place bindings. Adding a real
 * building later means adding one entry here (and, once art exists, a `frame`) - nothing that
 * reads STAGE_PLACES by id needs to change. `status: 'placeholder'` marks an id that has no real
 * geometry yet; callers fall back to `street`'s camera target through resolveStagePlace() below
 * but keep the placeholder's own label (with a "筹备中" suffix - see TownStage.vue) so the nav
 * item still reads correctly ahead of the art.
 */
export type StagePlace = {
  id: string
  label: string
  /** World-pixel camera target for the docked strip (see CompanionScene's cameraMode='docked'). */
  target: { x: number; y: number }
  frame?: { x: number; y: number; w: number; h: number }
  scenePlace: 'home' | 'cafe' | 'garden' | 'street'
  status: 'ready' | 'placeholder'
}
export const STAGE_PLACES: Record<string, StagePlace> = {
  // Real places: camera targets point at the actual room/seat, not each frame's geometric centre,
  // so the docked strip's short, wide window reads as a different corner of town per destination.
  street: { id: 'street', label: '门前小街', target: { x: 440, y: 410 }, frame: PLACE_FRAMES.street, scenePlace: 'street', status: 'ready' },
  home: { id: 'home', label: '归家小屋', target: { x: 560, y: 596 }, frame: PLACE_FRAMES.home, scenePlace: 'home', status: 'ready' },
  cafe: { id: 'cafe', label: '慢慢咖啡', target: { x: 640, y: 190 }, frame: PLACE_FRAMES.cafe, scenePlace: 'cafe', status: 'ready' },
  garden: { id: 'garden', label: '门前花园', target: { x: 1128, y: 329 }, frame: PLACE_FRAMES.garden, scenePlace: 'garden', status: 'ready' },
  // The board is an open-air plaza (BOARD_AREA), not a room - target points at the two mounted
  // boards themselves, not the plaza's geometric centre (which would frame mostly empty paving).
  board: { id: 'board', label: '公告板', target: { x: 1373, y: 360 }, frame: PLACE_FRAMES.board, scenePlace: 'street', status: 'ready' },
  // Target points at the study desk/blackboard corner (ACADEMY_ROOM), the part of the room that
  // actually has content, rather than the room's own geometric centre.
  academy: { id: 'academy', label: '学院', target: { x: 1398, y: 180 }, frame: PLACE_FRAMES.academy, scenePlace: 'street', status: 'ready' },
  // Target points at the mirror/rack equipment cluster (GYM_ROOM), same reasoning as academy.
  gym: { id: 'gym', label: '健身房', target: { x: 1358, y: 620 }, frame: PLACE_FRAMES.gym, scenePlace: 'street', status: 'ready' },
  // A virtual place, not a building: "wherever the avatar currently is". /today binds here instead
  // of a fixed 'street' target, because the street itself is usually empty (everyone is inside a
  // home or the cafe) - TownStage.vue overrides both `target` and `label` every render from the
  // live world (world.avatar's resolved position/place, via companion-scene.ts's residentTarget()
  // and scenePlace()); the values below are only the static fallback used before a world loads.
  avatar: { id: 'avatar', label: '门前小街', target: { x: 440, y: 410 }, scenePlace: 'street', status: 'ready' },
}
const warnedStagePlaceIds = new Set<string>()
/**
 * Resolve a nav/place id to a docked-camera target. Unknown ids and known placeholders both fall
 * back to `street`'s own target (there is nowhere real yet to point the camera) while keeping the
 * requested place's own label/status, so callers can still show "公告板 · 筹备中" instead of
 * silently relabelling it "门前小街". Warns once per id in dev only - this is expected during
 * incremental map growth, not a bug to spam production consoles with.
 */
export function resolveStagePlace(id: string | undefined): StagePlace {
  const key = id ?? 'street'
  const place = STAGE_PLACES[key]
  if (!place || place.status === 'placeholder') {
    if (import.meta.env.DEV && !warnedStagePlaceIds.has(key)) {
      warnedStagePlaceIds.add(key)
      console.warn(`[town] 地点 "${key}" 还没有真实落点，暂时用门前小街代替`)
    }
    const fallback = STAGE_PLACES.street!
    return place ? { ...place, target: fallback.target, scenePlace: fallback.scenePlace } : fallback
  }
  return place
}

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
  // Each resident's own bed, in their own home - the fix for "everyone sleeps in the same bed":
  // every id below is now a distinct, non-overlapping spot.
  'home-owner-bed': [HOME_ROOMS.owner!.bed],
  'home-student-bed': [HOME_ROOMS.student!.bed],
  'home-artist-bed': [HOME_ROOMS.artist!.bed],
  'home-gardener-bed': [HOME_ROOMS.gardener!.bed],
  // The avatar has its own room and bed, like every other resident.
  'home-self-bed': [HOME_ROOMS.self!.bed],
  // 周野 (fixer) gets his own new house.
  'home-fixer-bed': [HOME_ROOMS.fixer!.bed],
  // 阿满 (weaver) shares 知夏's (artist) room as a flat-mate. Her bed sits in the same room but at
  // a hand-picked spot clear of both the artist's own bed/desk collision rects and this room's
  // interior walls, so two people sharing reads as a character choice, not the old "everyone
  // sleeps in one bed" bug: companion-navigation.test.ts asserts every one of these bed ids is a
  // distinct, standable, reachable pixel.
  'home-weaver-bed': [{ x: HOME_ROOMS.artist!.x + 104, y: HOME_ROOMS.artist!.y + 133 }],
  // Reserved visual contract for home work; the backend decides when a desk is occupied.
  'home-owner-desk': [HOME_ROOMS.owner!.desk],
  'home-student-desk': [HOME_ROOMS.student!.desk],
  'home-artist-desk': [HOME_ROOMS.artist!.desk],
  'home-gardener-desk': [HOME_ROOMS.gardener!.desk],
  'home-self-desk': [HOME_ROOMS.self!.desk],
  'home-fixer-desk': [HOME_ROOMS.fixer!.desk],
  // Weaver's own desk, likewise inside the shared artist room, clear of her own bed above and of
  // the artist's furniture (matched to the chair pixel drawn in companion-stage.ts).
  'home-weaver-desk': [{ x: HOME_ROOMS.artist!.x + 22, y: HOME_ROOMS.artist!.y + 178 }],
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
