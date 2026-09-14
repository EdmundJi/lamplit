import { TOWN_LAYOUT, shift } from './town-layout'
/**
 * The single walking speed every actor in the scene moves at (companion-scene.ts's per-frame
 * stepTowardPoint call). This number MUST equal `WALK_PIXELS_PER_SECOND` in the backend's
 * `backend/src/main/java/com/betterself/growth/town/companion/domain/TownDistances.java`, which
 * divides a leg's real on-screen pixel distance by this same speed to get the number of seconds
 * that leg's travel plan should take. If the two ever drift apart: a backend value lower than
 * this one leaves the resident parked at the door waiting out a countdown that finishes after
 * they already arrived (the original "罚站" bug this constant exists to prevent); a backend value
 * higher than this one teleports them into the room before they have actually walked there.
 * See companion-walk-parity.test.ts, which recomputes real pathfinding distances and cross-checks
 * this constant and the backend's whole distance table against each other.
 */
export const WALK_PIXELS_PER_SECOND = 32
/** Action-sheet geometry from scripts/build-companion-assets.py; every action shares a foot anchor. */
export const ACTION_FRAME = { width: 96, height: 96, originX: 32 / 96, originY: 80 / 96 }
// [1, 3, 6, 9, 12] have bespoke create/drink/garden action sheets (c0N-actions.png); every other
// number here only has the generic walk/idle/sleep/sit/read sheet that build-town-assets.py
// generates for all 20 purchased premade characters (c01.png..c20.png - see that script's own
// `for i in range(1, 21)`). That is fine: sync() already guards every action-sheet animation
// behind `this.textures.exists(`${sheet}-actions`)`, so anyone whose index lands past those five
// simply falls back to the native idle/read/sit poses instead of a missing/floating action frame.
// docs/01's plan is 20 ready-made identities + 5 synthesised from layered parts (see
// scripts/build-companion-character.py). The licensed postman add-on covers the player when the
// backend returns 25 residents plus that avatar. The first seven entries keep their original order
// so the original residents retain their established appearance.
// 25 resident identities use c01..c25; the avatar at index 25 uses the licensed postman add-on,
// so a fully populated 25-person town plus its player has no repeated silhouette.
export const RESIDENT_ART = [1, 3, 6, 9, 12, 4, 7, 2, 5, 8, 10, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 'postman'] as const
export type CafeSeat = { x: number; y: number; facing: 'left' | 'right' }
/** Compact two-tops with real opposing seats. All coordinates are residents' foot anchors. */
const cafe = (x: number, y: number) => shift('cafe', x, y)
export const CAFE_TABLES = [176, 282].map(y => ({ ...cafe(480, y), seats: [{ ...cafe(438, y), facing: 'right' as const }, { ...cafe(522, y), facing: 'left' as const }] }))
export const CAFE_WORK_SEATS = CAFE_TABLES.flatMap(table => [...table.seats])
/** Six joined window worktops. Keep the original owned position id; added positions are public. */
export const CAFE_WINDOW_SEAT: CafeSeat = { ...cafe(964, 292), facing: 'right' }
export const CAFE_WINDOW_SEATS: CafeSeat[] = [CAFE_WINDOW_SEAT, ...[154, 223, 361, 430, 499].map(y => ({ ...cafe(964, y), facing: 'right' as const }))]
export const CAFE_WINDOW_TABLES = [174, 243, 312, 381, 450, 519].map(y => cafe(998, y))
/** The original public room joins a long, quiet window wing without moving the service counter. */
const CAFE_BUILDING = TOWN_LAYOUT.buildings.cafe
export const CAFE_ROOM = { x: CAFE_BUILDING.x, y: CAFE_BUILDING.y, w: CAFE_BUILDING.w, h: CAFE_BUILDING.h, doorX: CAFE_BUILDING.x + CAFE_BUILDING.doorX! }
export const CAFE_WINDOW_ROOM = { ...cafe(864, 12), w: 160, h: 536 }
export const CAFE_SERVICE = {
  entry: cafe(682, 350), operator: cafe(730, 123), staffEntry: cafe(598, 123),
  order: cafe(682, 215), pickup: cafe(798, 215),
  waiting: [cafe(740, 257), cafe(782, 257), cafe(704, 295), cafe(746, 295)],
  conversation: cafe(632, 276),
  // The espresso machine's own top edge (companion-stage.ts draws 'cafe_espresso' at cafe(730, 55)
  // with origin (.5,1) and a 30px-tall frame, so its top sits around local y=25) - where
  // companion-position-props.ts anchors the tending steam wisp. Kept as its own named point rather
  // than reusing `operator` (the resident's own foot position at the counter, well below the
  // machine) so the wisp rises from the machine, not from the barista's feet.
  machine: cafe(730, 27),
}
/** Garden interiors were authored as `x + GARDEN_OFFSET_X, y`; the offset now also carries the
 * garden's current origin, and `gardenY` its vertical move. */
export const GARDEN_OFFSET_X = shift('garden', 288, 0).x
export const gardenY = (y: number) => shift('garden', 0, y).y
export const CAFE_SEATS: CafeSeat[] = [...CAFE_WORK_SEATS, ...CAFE_WINDOW_SEATS]
export function cafeSeatAt(point: { x: number; y: number }) {
  return CAFE_SEATS.find(seat => Math.abs(seat.x - point.x) < .5 && Math.abs(seat.y - point.y) < .5)
}

// Widened (never moved) to fit board/academy/gym - see tmp/town-stage-extension.md section 3.
// Every rectangle that already existed at 1248x768 (HOME_ROOMS, the original four PLACE_FRAMES,
// CAFE_SEATS, POSITION_SLOTS, RAIN_SHELTERS) keeps its exact old coordinates; the three new
// buildings below live entirely past the old x=1248 right edge, in the newly added strip, so
// nothing already drawn had to shift.
export const COMPANION_WORLD_SIZE = TOWN_LAYOUT.world
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
// 南巷的十户组成第二版居住区。年轻人的房子会容纳三到四个独立卧室角，年长居民
// 独居或两人同住；位置 id 仍属于后端，这里只给每张床和桌子一个稳定像素。
export const HOME_ROOMS: Record<string, HomeRoom> = Object.fromEntries(
  Object.entries(TOWN_LAYOUT.homes).map(([id, r]) => [id, homeRoom(r.x, r.y, r.w, r.h)]))
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
const room = (id: 'academy' | 'gym' | 'shop') => { const b = TOWN_LAYOUT.buildings[id]; return { x: b.x, y: b.y, w: b.w, h: b.h, doorX: b.x + b.doorX! } }
export const ACADEMY_ROOM = room('academy')
export const GYM_ROOM = room('gym')
export const BOARD_AREA = { ...TOWN_LAYOUT.buildings.board }
export const SHOP_ROOM = room('shop')
export const PLACE_FRAMES = {
  home: TOWN_LAYOUT.homeFrame, cafe: { ...cafe(372, 0), w: 664, h: 576 },
  garden: { ...TOWN_LAYOUT.buildings.garden }, street: { ...TOWN_LAYOUT.buildings.street },
  academy: { x: ACADEMY_ROOM.x, y: ACADEMY_ROOM.y, w: ACADEMY_ROOM.w, h: ACADEMY_ROOM.h },
  gym: { x: GYM_ROOM.x, y: GYM_ROOM.y, w: GYM_ROOM.w, h: GYM_ROOM.h },
  board: { x: BOARD_AREA.x, y: BOARD_AREA.y, w: BOARD_AREA.w, h: BOARD_AREA.h },
  shop: { x: SHOP_ROOM.x, y: SHOP_ROOM.y, w: SHOP_ROOM.w, h: SHOP_ROOM.h },
} as const

export type ScenePlaceId = 'home' | 'cafe' | 'garden' | 'street' | 'academy' | 'gym' | 'board' | 'shop'

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
  scenePlace: ScenePlaceId
  status: 'ready' | 'placeholder'
}
export const STAGE_PLACES: Record<string, StagePlace> = {
  // Real places: camera targets point at the actual room/seat, not each frame's geometric centre,
  // so the docked strip's short, wide window reads as a different corner of town per destination.
  street: { id: 'street', label: '门前小街', target: shift('street', 440, 410), frame: PLACE_FRAMES.street, scenePlace: 'street', status: 'ready' },
  home: { id: 'home', label: '归家小屋', target: { x: HOME_ROOMS.self!.x + 80, y: HOME_ROOMS.self!.y + 104 }, frame: PLACE_FRAMES.home, scenePlace: 'home', status: 'ready' },
  cafe: { id: 'cafe', label: '慢慢咖啡', target: cafe(640, 190), frame: PLACE_FRAMES.cafe, scenePlace: 'cafe', status: 'ready' },
  garden: { id: 'garden', label: '门前花园', target: shift('garden', 1128, 329), frame: PLACE_FRAMES.garden, scenePlace: 'garden', status: 'ready' },
  // The board is an open-air plaza (BOARD_AREA), not a room - target points at the two mounted
  // boards themselves, not the plaza's geometric centre (which would frame mostly empty paving).
  board: { id: 'board', label: '公告板广场', target: shift('board', 1373, 360), frame: PLACE_FRAMES.board, scenePlace: 'board', status: 'ready' },
  // Target points at the study desk/blackboard corner (ACADEMY_ROOM), the part of the room that
  // actually has content, rather than the room's own geometric centre.
  academy: { id: 'academy', label: '梧桐学院', target: shift('academy', 1398, 180), frame: PLACE_FRAMES.academy, scenePlace: 'academy', status: 'ready' },
  // Target points at the mirror/rack equipment cluster (GYM_ROOM), same reasoning as academy.
  gym: { id: 'gym', label: '河岸健身房', target: shift('gym', 1358, 620), frame: PLACE_FRAMES.gym, scenePlace: 'gym', status: 'ready' },
  shop: { id: 'shop', label: '南巷商店', target: shift('shop', 1790, 590), frame: PLACE_FRAMES.shop, scenePlace: 'shop', status: 'ready' },
  // A virtual place, not a building: "wherever the avatar currently is". /today binds here instead
  // of a fixed 'street' target, because the street itself is usually empty (everyone is inside a
  // home or the cafe) - TownStage.vue overrides both `target` and `label` every render from the
  // live world (world.avatar's resolved position/place, via companion-scene.ts's residentTarget()
  // and scenePlace()); the values below are only the static fallback used before a world loads.
  avatar: { id: 'avatar', label: '门前小街', target: shift('street', 440, 410), scenePlace: 'street', status: 'ready' },
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
  'street-bench': [shift('street', 274, 454), shift('street', 306, 454), shift('street', 484, 454), shift('street', 516, 454)],
  // Public garden bench (capacity 3), distinct from the tended plots.
  'garden-bench': [shift('garden', 1109, 439), shift('garden', 1137, 439), shift('garden', 1165, 439)],
  // The gardener's own tended plot (capacity 1) - a specific bed of soil, not the shared bench.
  'garden-plot': [shift('garden', 1137, 356)],
  'academy-desk-1': [shift('academy', 1330, 220)],
  'academy-desk-2': [shift('academy', 1398, 220)],
  'academy-desk-3': [shift('academy', 1466, 220)],
  'gym-bench': [shift('gym', 1320, 672), shift('gym', 1370, 672), shift('gym', 1420, 672)],
  'shop-workbench': [shift('shop', 1897, 666)],
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

/** The authored household layout mirrors ResidentPersonas.households(). It exists here only to
 * place each resident's owned bed/desk and the shared stove; the backend remains authoritative
 * for membership, capacity and occupancy. */
export const HOUSEHOLD_MEMBERS: Record<string, readonly string[]> = {
  barista: ['barista', 'waiter', 'tutor'], botanist: ['botanist', 'trainer', 'boxer', 'yogi'],
  messenger: ['messenger', 'clerk', 'librarian'], baker: ['baker', 'beekeeper'],
  florist: ['florist', 'scribe'], scholar: ['scholar'], tailor: ['tailor'], masseur: ['masseur'],
  broker: ['broker'], trader: ['trader'],
}

for (const [homeId, members] of Object.entries(HOUSEHOLD_MEMBERS)) {
  const room = HOME_ROOMS[homeId]!
  const usableWidth = room.w - 16
  const bedroomWidth = usableWidth / members.length
  members.forEach((residentId, index) => {
    const center = room.x + 8 + bedroomWidth * (index + .5)
    POSITION_SLOTS[`home-${residentId}-bed`] = [{ x: center, y: room.y + 102 }]
    POSITION_SLOTS[`home-${residentId}-desk`] = [{ x: center, y: room.y + 160 }]
  })
  POSITION_SLOTS[`home-${homeId}-table`] = [
    { x: room.x + 22, y: room.y + 214 }, { x: room.x + 86, y: room.y + 214 },
    { x: room.x + 42, y: room.y + 190 }, { x: room.x + 70, y: room.y + 190 },
  ]
  POSITION_SLOTS[`home-${homeId}-bathroom`] = [{ x: room.x + 151, y: room.y + 211 }]
  if (members.length > 1) POSITION_SLOTS[`home-${homeId}-stove`] = [{ x: room.x + 212, y: room.y + 211 }]
}

// Older compact homes still receive the room/position ids backfilled by TownPlaces. Their common
// table reuses the already drawn writing table, while the bathroom anchor occupies the open lower
// corner; larger south-lane houses above get visibly separate rooms.
for (const [homeId, room] of Object.entries(HOME_ROOMS)) {
  POSITION_SLOTS[`home-${homeId}-table`] ??= [
    { x: room.x + 20, y: room.y + room.h - 18 }, { x: room.x + room.w * .38, y: room.y + room.h - 18 },
    { x: room.x + room.w * .64, y: room.y + room.h - 18 }, { x: room.x + room.w - 20, y: room.y + room.h - 18 },
  ]
  POSITION_SLOTS[`home-${homeId}-bathroom`] ??= [{ x: room.x + room.w / 2, y: room.y + room.h - 16 }]
}

// Every resident id the shared-household POSITION_SLOTS loop above placed a bed for. Used only by
// sleepSpriteOffset() below to tell the two independently-authored bed-drawing conventions apart.
const SHARED_HOUSEHOLD_RESIDENT_IDS = new Set(Object.values(HOUSEHOLD_MEMBERS).flat())
/**
 * How far above a sleeping resident's own occupancy pixel (their `home-<id>-bed` POSITION_SLOTS
 * entry) the sleep sprite should sit, so its own foot lands on the bed sprite's foot instead of
 * floating somewhere between the bed and the ceiling.
 *
 * companion-stage.ts authored beds two different ways with two different gaps between the bed
 * image's own bottom edge and the occupancy pixel used here and in sync():
 *  - the six solo homes (`!members` branch, `image(x + 32, y + 129, ...)`) and 阿满's own bed
 *    inside 知夏's shared room (`image(x + 104, y + 118, ...)`) both leave a 15px gap - their
 *    bed image sits 15px above where the occupant actually stands (`homeRoom()`'s `y + 144`, or
 *    the weaver's own hand-placed `y + 133`);
 *  - the shared-household loop above (`image(bed.x, bed.y - 4, ...)`) leaves only a 4px gap.
 * A single sprite offset tuned for one of these (the old flat `-40`, well past either gap) put
 * every sleeping resident's head above their own room's wall - see docs/... (task: "head poking
 * out of the wall"). `-gap` instead lands the sprite's own foot exactly on the bed image's foot in
 * both conventions; the sprite is a fixed 64px tall regardless of branch, so a small shared-home
 * bed (its image is only ~52px tall at that branch's own .68 scale) still has the sleeper's head
 * overshoot its own pillow a little - the shared beds are simply shorter than a standing character
 * is tall, a furniture-scale mismatch no single offset can fully hide - but only by a few px, not
 * a whole room's height. Falls back to the solo/15px gap for anything that is not a recognised
 * `home-<id>-bed` positionId (an old save, or a fallback `room.bed` placement, which uses the same
 * `homeRoom()` geometry as the solo branch anyway).
 */
export function sleepSpriteOffset(positionId?: string | null): number {
  const residentId = positionId?.match(/^home-(.+)-bed$/)?.[1]
  return residentId && SHARED_HOUSEHOLD_RESIDENT_IDS.has(residentId) ? -4 : -15
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
