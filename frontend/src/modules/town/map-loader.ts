/**
 * 数据驱动的室内地图加载器 (可进入房间的地基). Turns a plain JSON room description into the
 * structures interior.scene.ts needs to render — without ever touching Phaser, so every piece of
 * logic here (parsing, validation, data-slot evaluation, door/spawn mapping, movement collision)
 * can be unit tested directly under vitest/jsdom.
 *
 * The JSON format is deliberately smaller than a full Tiled map, but its two tile layers are
 * plain `rows x cols` grids of atlas frame names — exactly the shape you get by mapping a Tiled
 * layer's per-cell gid through its tileset, so a Tiled export can be converted into this format
 * with a small script rather than a rewrite.
 */

// ---------- JSON schema (see docs in the report; kept here as the single source of truth) ----------

export type RoomTile = string | null
export type RoomGrid = RoomTile[][]

export type RoomPoint = { x: number; y: number }
export type RoomRect = { x: number; y: number; w: number; h: number }

/** A doorway: an interactive rectangle plus where it leads. `target` is either another room's
 * `id` (if the orchestrator keeps several RoomMapData loaded) or an opaque string such as
 * `'town'` that the orchestrator maps back to the outdoor scene itself. */
export type RoomDoor = {
  id: string
  rect: RoomRect
  target: string
  label?: string
  /** Where a resident/player should spawn in *this* room when arriving back through this same
   * door (e.g. re-entering after having left through it). Falls back to the room's own `spawn`. */
  spawn?: RoomPoint
}

/**
 * A furniture piece that reacts to clicks (M3-3). `actionId` is a world-actions.ts registry id —
 * the map data only says "clicking this means that capability", it never runs the capability
 * itself, so a room JSON never needs to know what "打开书桌" actually does.
 */
export type RoomInteraction = {
  actionId: string
  /** Click hit-box; omit to use a `tileSize x tileSize` box anchored the same way the furniture
   * sprite is (bottom-center at x,y) — the common case, since most interactive props are one tile. */
  hit?: RoomRect
  /** Hover/label text, e.g. "打开书桌". Omit for furniture that should stay silent until clicked. */
  label?: string
}

/** Static decoration. Anchored like Phaser's `setOrigin` (default 0.5,1 — bottom-center, so `y`
 * is the character's/object's "feet" line) and depth-sorted by `y` unless `depth` is given. */
export type RoomFurniture = {
  id: string
  frame: string
  x: number
  y: number
  originX?: number
  originY?: number
  depth?: number
  displayWidth?: number
  displayHeight?: number
  /** Present only for furniture the player can click (desk / achievement wall / pet house, ...). */
  interactive?: RoomInteraction
}

/**
 * A data-bound decoration slot: up to `max` copies of (cycling through) `frames` are drawn,
 * starting at `anchor` and stepping by `step` per extra copy. How many copies actually show is
 * `clamp(floor(metrics[metric] / perItem), 0, max)` — e.g. a bookshelf whose visible book count
 * is driven by "knowledge tasks completed", or a gym rack whose weight plates track a workout streak.
 */
export type RoomSlot = {
  id: string
  frames: string[]
  metric: string
  max: number
  perItem?: number
  anchor: RoomPoint
  step: RoomPoint
  originX?: number
  originY?: number
  depth?: number
}

/** Where a resident sprite sits. The matching desk/chair art is authored as ordinary `furniture`
 * at the same coordinates — a seat is only the anchor a resident is placed at. */
export type RoomSeat = {
  id: string
  x: number
  y: number
}

export type RoomMapData = {
  id: string
  title: string
  tileSize: number
  cols: number
  rows: number
  backgroundColor: string
  spawn: RoomPoint
  layers: { floor: RoomGrid; walls: RoomGrid }
  collisions: RoomRect[]
  doors: RoomDoor[]
  furniture: RoomFurniture[]
  slots: RoomSlot[]
  seats: RoomSeat[]
}

export const DEFAULT_ORIGIN = { x: 0.5, y: 1 } as const

/** User-supplied numeric facts a room's slots can read (e.g. `{ knowledgeDone: 5 }`). Unknown or
 * missing keys evaluate as 0 rather than throwing — a room should never crash because the caller
 * forgot to pass a metric. */
export type RoomMetrics = Record<string, number>

/** One resident rendered in a seat — same shape as academy.scene.ts's AcademyResident so a room's
 * residents can be produced by the same kind of mapping function. */
export type RoomResident = {
  publicId: string
  displayName: string
  isSelf: boolean
  characterSheet: number
  state: 'reading' | 'phone' | 'idle'
}

export type SeatAssignment = { seat: RoomSeat; resident: RoomResident }

/** One resolved slot instance, ready for the scene to `add.image` directly. */
export type SlotInstance = {
  slotId: string
  index: number
  frame: string
  x: number
  y: number
  originX: number
  originY: number
  depth: number
}

// ---------- validation ----------

export class RoomMapValidationError extends Error {
  issues: string[]
  constructor(issues: string[]) {
    super(`invalid room map:\n- ${issues.join('\n- ')}`)
    this.name = 'RoomMapValidationError'
    this.issues = issues
  }
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

function isPoint(value: unknown): value is RoomPoint {
  return typeof value === 'object' && value !== null && isFiniteNumber((value as RoomPoint).x) && isFiniteNumber((value as RoomPoint).y)
}

function isRect(value: unknown): value is RoomRect {
  if (typeof value !== 'object' || value === null) return false
  const rect = value as RoomRect
  return isFiniteNumber(rect.x) && isFiniteNumber(rect.y) && isFiniteNumber(rect.w) && isFiniteNumber(rect.h) && rect.w > 0 && rect.h > 0
}

function pushUnlessSeen(seen: Set<string>, issues: string[], where: string, id: string) {
  if (seen.has(id)) issues.push(`${where} has a duplicate id "${id}"`)
  seen.add(id)
}

function validateGrid(grid: unknown, rows: number, cols: number, name: string, issues: string[]): RoomGrid {
  if (!Array.isArray(grid) || grid.length !== rows) {
    issues.push(`layers.${name} must be an array of ${rows} rows (got ${Array.isArray(grid) ? grid.length : typeof grid})`)
    return []
  }
  const out: RoomGrid = []
  grid.forEach((row, rowIndex) => {
    if (!Array.isArray(row) || row.length !== cols) {
      issues.push(`layers.${name}[${rowIndex}] must be an array of ${cols} tiles (got ${Array.isArray(row) ? row.length : typeof row})`)
      out.push(Array.from({ length: cols }, () => null))
      return
    }
    out.push(row.map((cell, cellIndex) => {
      if (cell === null || typeof cell === 'string') return cell
      issues.push(`layers.${name}[${rowIndex}][${cellIndex}] must be a frame name or null (got ${typeof cell})`)
      return null
    }))
  })
  return out
}

/**
 * Parse and validate a room map from raw (already `JSON.parse`d) data. Throws
 * `RoomMapValidationError` — with every problem found, not just the first — instead of letting a
 * malformed map crash the scene deep inside Phaser with a cryptic TypeError.
 */
export function parseRoomMap(data: unknown): RoomMapData {
  const issues: string[] = []
  if (typeof data !== 'object' || data === null) {
    throw new RoomMapValidationError(['room map must be a JSON object'])
  }
  const raw = data as Record<string, unknown>

  if (!isNonEmptyString(raw.id)) issues.push('"id" must be a non-empty string')
  if (!isNonEmptyString(raw.title)) issues.push('"title" must be a non-empty string')
  if (!isFiniteNumber(raw.tileSize) || raw.tileSize <= 0) issues.push('"tileSize" must be a positive number')
  if (!Number.isInteger(raw.cols) || (raw.cols as number) <= 0) issues.push('"cols" must be a positive integer')
  if (!Number.isInteger(raw.rows) || (raw.rows as number) <= 0) issues.push('"rows" must be a positive integer')
  if (!isNonEmptyString(raw.backgroundColor)) issues.push('"backgroundColor" must be a non-empty CSS color string')
  if (!isPoint(raw.spawn)) issues.push('"spawn" must be a {x,y} point')

  const cols = Number.isInteger(raw.cols) ? (raw.cols as number) : 0
  const rows = Number.isInteger(raw.rows) ? (raw.rows as number) : 0

  const layersRaw = (typeof raw.layers === 'object' && raw.layers !== null ? raw.layers : {}) as Record<string, unknown>
  if (typeof raw.layers !== 'object' || raw.layers === null) issues.push('"layers" must be an object with "floor" and "walls" grids')
  const floor = cols > 0 && rows > 0 ? validateGrid(layersRaw.floor, rows, cols, 'floor', issues) : []
  const walls = cols > 0 && rows > 0 ? validateGrid(layersRaw.walls, rows, cols, 'walls', issues) : []

  const collisions: RoomRect[] = []
  if (raw.collisions !== undefined) {
    if (!Array.isArray(raw.collisions)) issues.push('"collisions" must be an array of rects')
    else raw.collisions.forEach((item, index) => {
      if (isRect(item)) collisions.push(item)
      else issues.push(`collisions[${index}] must be a {x,y,w,h} rect with positive w/h`)
    })
  }

  const doors: RoomDoor[] = []
  const doorIds = new Set<string>()
  if (raw.doors !== undefined) {
    if (!Array.isArray(raw.doors)) issues.push('"doors" must be an array')
    else raw.doors.forEach((item, index) => {
      if (typeof item !== 'object' || item === null) { issues.push(`doors[${index}] must be an object`); return }
      const door = item as Record<string, unknown>
      if (!isNonEmptyString(door.id)) { issues.push(`doors[${index}].id must be a non-empty string`); return }
      if (!isRect(door.rect)) { issues.push(`doors[${index}] ("${door.id}").rect must be a {x,y,w,h} rect`); return }
      if (!isNonEmptyString(door.target)) { issues.push(`doors[${index}] ("${door.id}").target must be a non-empty string`); return }
      if (door.spawn !== undefined && !isPoint(door.spawn)) { issues.push(`doors[${index}] ("${door.id}").spawn must be a {x,y} point when present`); return }
      pushUnlessSeen(doorIds, issues, 'doors', door.id)
      doors.push({
        id: door.id, rect: door.rect, target: door.target,
        label: typeof door.label === 'string' ? door.label : undefined,
        spawn: door.spawn as RoomPoint | undefined,
      })
    })
  }

  const furniture: RoomFurniture[] = []
  const furnitureIds = new Set<string>()
  if (raw.furniture !== undefined) {
    if (!Array.isArray(raw.furniture)) issues.push('"furniture" must be an array')
    else raw.furniture.forEach((item, index) => {
      if (typeof item !== 'object' || item === null) { issues.push(`furniture[${index}] must be an object`); return }
      const piece = item as Record<string, unknown>
      if (!isNonEmptyString(piece.id)) { issues.push(`furniture[${index}].id must be a non-empty string`); return }
      if (!isNonEmptyString(piece.frame)) { issues.push(`furniture[${index}] ("${piece.id}").frame must be a non-empty string`); return }
      if (!isFiniteNumber(piece.x) || !isFiniteNumber(piece.y)) { issues.push(`furniture[${index}] ("${piece.id}") must have finite numeric x/y`); return }
      let interactive: RoomInteraction | undefined
      if (piece.interactive !== undefined) {
        if (typeof piece.interactive !== 'object' || piece.interactive === null) {
          issues.push(`furniture[${index}] ("${piece.id}").interactive must be an object when present`)
        } else {
          const interactiveRaw = piece.interactive as Record<string, unknown>
          if (!isNonEmptyString(interactiveRaw.actionId)) {
            issues.push(`furniture[${index}] ("${piece.id}").interactive.actionId must be a non-empty string`)
          } else if (interactiveRaw.hit !== undefined && !isRect(interactiveRaw.hit)) {
            issues.push(`furniture[${index}] ("${piece.id}").interactive.hit must be a {x,y,w,h} rect when present`)
          } else {
            interactive = {
              actionId: interactiveRaw.actionId as string,
              hit: interactiveRaw.hit as RoomRect | undefined,
              label: typeof interactiveRaw.label === 'string' ? interactiveRaw.label : undefined,
            }
          }
        }
      }
      pushUnlessSeen(furnitureIds, issues, 'furniture', piece.id)
      furniture.push({
        id: piece.id, frame: piece.frame, x: piece.x, y: piece.y,
        originX: isFiniteNumber(piece.originX) ? piece.originX : undefined,
        originY: isFiniteNumber(piece.originY) ? piece.originY : undefined,
        depth: isFiniteNumber(piece.depth) ? piece.depth : undefined,
        displayWidth: isFiniteNumber(piece.displayWidth) ? piece.displayWidth : undefined,
        displayHeight: isFiniteNumber(piece.displayHeight) ? piece.displayHeight : undefined,
        interactive,
      })
    })
  }

  const slots: RoomSlot[] = []
  const slotIds = new Set<string>()
  if (raw.slots !== undefined) {
    if (!Array.isArray(raw.slots)) issues.push('"slots" must be an array')
    else raw.slots.forEach((item, index) => {
      if (typeof item !== 'object' || item === null) { issues.push(`slots[${index}] must be an object`); return }
      const slot = item as Record<string, unknown>
      if (!isNonEmptyString(slot.id)) { issues.push(`slots[${index}].id must be a non-empty string`); return }
      const framesOk = Array.isArray(slot.frames) && slot.frames.length > 0 && slot.frames.every(f => isNonEmptyString(f))
      if (!framesOk) { issues.push(`slots[${index}] ("${slot.id}").frames must be a non-empty array of frame names`); return }
      if (!isNonEmptyString(slot.metric)) { issues.push(`slots[${index}] ("${slot.id}").metric must be a non-empty string`); return }
      if (!Number.isInteger(slot.max) || (slot.max as number) < 0) { issues.push(`slots[${index}] ("${slot.id}").max must be a non-negative integer`); return }
      if (!isPoint(slot.anchor)) { issues.push(`slots[${index}] ("${slot.id}").anchor must be a {x,y} point`); return }
      if (!isPoint(slot.step)) { issues.push(`slots[${index}] ("${slot.id}").step must be a {x,y} point`); return }
      if (slot.perItem !== undefined && (!isFiniteNumber(slot.perItem) || (slot.perItem as number) <= 0)) {
        issues.push(`slots[${index}] ("${slot.id}").perItem must be a positive number when present`); return
      }
      pushUnlessSeen(slotIds, issues, 'slots', slot.id)
      slots.push({
        id: slot.id, frames: slot.frames as string[], metric: slot.metric, max: slot.max as number,
        perItem: slot.perItem as number | undefined, anchor: slot.anchor as RoomPoint, step: slot.step as RoomPoint,
        originX: isFiniteNumber(slot.originX) ? slot.originX : undefined,
        originY: isFiniteNumber(slot.originY) ? slot.originY : undefined,
        depth: isFiniteNumber(slot.depth) ? slot.depth : undefined,
      })
    })
  }

  const seats: RoomSeat[] = []
  const seatIds = new Set<string>()
  if (raw.seats !== undefined) {
    if (!Array.isArray(raw.seats)) issues.push('"seats" must be an array')
    else raw.seats.forEach((item, index) => {
      if (typeof item !== 'object' || item === null) { issues.push(`seats[${index}] must be an object`); return }
      const seat = item as Record<string, unknown>
      if (!isNonEmptyString(seat.id)) { issues.push(`seats[${index}].id must be a non-empty string`); return }
      if (!isFiniteNumber(seat.x) || !isFiniteNumber(seat.y)) { issues.push(`seats[${index}] ("${seat.id}") must have finite numeric x/y`); return }
      pushUnlessSeen(seatIds, issues, 'seats', seat.id)
      seats.push({ id: seat.id, x: seat.x, y: seat.y })
    })
  }

  if (issues.length > 0) throw new RoomMapValidationError(issues)

  return {
    id: raw.id as string,
    title: raw.title as string,
    tileSize: raw.tileSize as number,
    cols, rows,
    backgroundColor: raw.backgroundColor as string,
    spawn: raw.spawn as RoomPoint,
    layers: { floor, walls },
    collisions, doors, furniture, slots, seats,
  }
}

// ---------- data slots ----------

/** Evaluate every slot against `metrics`, returning the sprite instances the scene should draw.
 * Pure: no Phaser involved, so a room's whole "furniture reacts to your progress" behaviour can be
 * asserted with plain numbers in a unit test. */
export function evaluateSlots(room: Pick<RoomMapData, 'slots'>, metrics: RoomMetrics): SlotInstance[] {
  const out: SlotInstance[] = []
  for (const slot of room.slots) {
    const raw = metrics[slot.metric] ?? 0
    const perItem = slot.perItem ?? 1
    const count = Math.max(0, Math.min(slot.max, Math.floor(raw / perItem)))
    for (let index = 0; index < count; index += 1) {
      out.push({
        slotId: slot.id,
        index,
        frame: slot.frames[index % slot.frames.length],
        x: slot.anchor.x + slot.step.x * index,
        y: slot.anchor.y + slot.step.y * index,
        originX: slot.originX ?? DEFAULT_ORIGIN.x,
        originY: slot.originY ?? DEFAULT_ORIGIN.y,
        depth: slot.depth ?? slot.anchor.y + slot.step.y * index,
      })
    }
  }
  return out
}

// ---------- seats ----------

/** Fill seats in order, one resident each; extra residents (more than seats) are dropped rather
 * than thrown away noisily — same "don't crash on awkward input" spirit as parseRoomMap. */
export function assignSeats(room: Pick<RoomMapData, 'seats'>, residents: RoomResident[]): SeatAssignment[] {
  const assignments: SeatAssignment[] = []
  room.seats.forEach((seat, index) => {
    const resident = residents[index]
    if (resident) assignments.push({ seat, resident })
  })
  return assignments
}

// ---------- doors ----------

export function findDoor(room: Pick<RoomMapData, 'doors'>, doorId: string): RoomDoor | undefined {
  return room.doors.find(door => door.id === doorId)
}

/** The door (if any) whose rect contains the point — used both by click-to-exit and by a
 * roaming player walking into a doorway. */
export function doorAt(room: Pick<RoomMapData, 'doors'>, x: number, y: number): RoomDoor | undefined {
  return room.doors.find(door => x >= door.rect.x && x <= door.rect.x + door.rect.w && y >= door.rect.y && y <= door.rect.y + door.rect.h)
}

// ---------- interactable furniture (M3-3) ----------

/** Default click hit-box for a piece of interactive furniture that doesn't specify its own `hit`:
 * one tile, anchored the same way the sprite is drawn (bottom-center at x,y). Exported so
 * interior.scene.ts can draw its click zone at exactly the box `interactableAt` tests against —
 * one formula, so the visible click target and the hit-test can never drift apart. */
export function defaultInteractionHit(piece: RoomFurniture, tileSize: number): RoomRect {
  return { x: piece.x - tileSize / 2, y: piece.y - tileSize, w: tileSize, h: tileSize }
}

/** The interactive furniture piece (if any) whose hit-box contains the point — same shape as
 * `doorAt`, so interior.scene.ts can hit-test a click against furniture the same way it already
 * hit-tests one against doors. */
export function interactableAt(room: Pick<RoomMapData, 'furniture' | 'tileSize'>, x: number, y: number): RoomFurniture | undefined {
  return room.furniture.find(piece => {
    if (!piece.interactive) return false
    const box = piece.interactive.hit ?? defaultInteractionHit(piece, room.tileSize)
    return x >= box.x && x <= box.x + box.w && y >= box.y && y <= box.y + box.h
  })
}

/**
 * Where a resident/player should appear in `room` given which door they came through. This is the
 * door <-> spawn mapping: an orchestrator switching from another room passes that room's door id
 * (if known) here to place the arriving character sensibly; with no matching door it falls back to
 * the room's default `spawn`.
 */
export function entrySpawnFor(room: Pick<RoomMapData, 'doors' | 'spawn'>, viaDoorId?: string): RoomPoint {
  if (viaDoorId) {
    const door = findDoor(room, viaDoorId)
    if (door?.spawn) return door.spawn
  }
  return room.spawn
}

// ---------- movement helpers (pure; interior.scene.ts's default controller uses these, and a
// replacement controller is free to use them too instead of duplicating collision math) ----------

/** True when the axis-aligned box `w x h` centered (bottom-anchored, like a character's feet) at
 * (x,y) overlaps any collision rect. */
export function collidesAt(room: Pick<RoomMapData, 'collisions'>, x: number, y: number, w: number, h: number): boolean {
  const left = x - w / 2
  const right = x + w / 2
  const top = y - h
  const bottom = y
  return room.collisions.some(rect => left < rect.x + rect.w && right > rect.x && top < rect.y + rect.h && bottom > rect.y)
}

/** Advance a single axis toward `target` by at most `maxDelta`, never overshooting — the same
 * shape as walkers.ts's stepToward but kept local so this module has no Phaser-adjacent
 * dependencies of its own. */
export function stepAxis(current: number, target: number, maxDelta: number): number {
  const distance = target - current
  if (distance === 0) return current
  const clamped = Math.max(0, maxDelta)
  return current + Math.sign(distance) * Math.min(Math.abs(distance), clamped)
}

/** Clamp a world position to the room's pixel bounds, so a controller never has to special-case
 * walking past the edge of the map. */
export function clampToRoom(room: Pick<RoomMapData, 'tileSize' | 'cols' | 'rows'>, x: number, y: number): RoomPoint {
  return {
    x: Math.min(Math.max(x, 0), room.cols * room.tileSize),
    y: Math.min(Math.max(y, 0), room.rows * room.tileSize),
  }
}
