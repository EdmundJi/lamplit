/**
 * Pure placement helpers for the 18-NPC roster (plan.md §2.4, CONTRACT.md §6/§8).
 * No Phaser import — `town.engine.ts` owns wiring these into real sprites and passes its own
 * world constants in via `TownLayout` rather than this module importing them.
 */
import type { NpcPlace, NpcScheduleSlot, TownNpcLayer } from './town-npc.types'

/**
 * The subset of `town.engine.ts`'s world geometry that placement needs, expressed as a plain
 * data record so the engine can pass its real constants (e.g. `PLOT_START`, `academyDoorX`) in
 * without this module ever importing the engine.
 */
export type TownLayout = {
  /** Front door of 成长学院 — anchor for `academy` and (offset from it) `park`. */
  academyDoorX: number
  /** Left edge of the resident-plot row; also where NPCs without a house of their own "live". */
  plotStartX: number
  /** Gym storefront x (first plot in the row, per `town.engine.ts`). */
  gymX: number
  /** Café storefront x (second plot in the row, per `town.engine.ts`). */
  cafeX: number
  /** Park bench cluster x. */
  parkX: number
  parkY?: number
  branchX?: number
  /** Town-square span; `plaza` resolves to its midpoint. */
  plazaMinX: number
  plazaMaxX: number
  /** Total street length; `street` (generic strolling) resolves to its midpoint. */
  worldWidth: number
}

/** Maps a §6 `place` code to a world x coordinate using the engine's real geometry. */
export function placeFor(place: NpcPlace, layout: TownLayout): number {
  switch (place) {
    case 'home':
      return layout.plotStartX
    case 'academy':
      return layout.academyDoorX
    case 'gym':
      return layout.gymX
    case 'cafe':
      return layout.cafeX
    case 'park':
      return layout.parkX
    case 'plaza':
      return (layout.plazaMinX + layout.plazaMaxX) / 2
    case 'street':
      return layout.worldWidth / 2
  }
}

function normalizeHour(hour: number): number {
  return ((hour % 24) + 24) % 24
}

function inSlot(slot: NpcScheduleSlot, hour: number): boolean {
  const { startHour, endHour } = slot
  if (startHour === endHour) return false // zero-length slot is never active
  if (startHour < endHour) return hour >= startHour && hour < endHour
  return hour >= startHour || hour < endHour // wraps past midnight, e.g. 22 -> 6
}

/**
 * The schedule slot active at `hour`, handling slots that wrap past midnight (`startHour >
 * endHour`, e.g. 22-6). Returns `null` for an empty schedule or a genuine hole (no slot covers
 * `hour`) — callers should treat that as "off screen" rather than guessing a default.
 */
export function activeSlot(schedule: NpcScheduleSlot[], hour: number): NpcScheduleSlot | null {
  const h = normalizeHour(hour)
  for (const slot of schedule) {
    if (inSlot(slot, h)) return slot
  }
  return null
}

/**
 * 护栏 B (plan.md §2.4): same-screen NPC density cap, bucketed by time of day. The hard
 * invariant is that this never exceeds 12 regardless of hour.
 *
 * Bands (all bounds inclusive-start/exclusive-end, in local hour):
 *  - 00-05 deep night   -> 3   (plan: 深夜 2~3人; pick the upper bound)
 *  - 05-07 early morning-> 4   (plan: 早晨 4~5人)
 *  - 07-10 morning ramp -> 8
 *  - 10-13 cafe peak    -> 12  (plan: 午间咖啡馆高峰 10~12人; the one hard ceiling)
 *  - 13-18 afternoon    -> 9
 *  - 18-22 evening      -> 7
 *  - 22-24 late night   -> 3
 */
export function densityCap(hour: number): number {
  const h = normalizeHour(hour)
  let cap: number
  if (h < 5) cap = 3
  else if (h < 7) cap = 4
  else if (h < 10) cap = 8
  else if (h < 13) cap = 12
  else if (h < 18) cap = 9
  else if (h < 22) cap = 7
  else cap = 3
  return Math.min(cap, 12)
}

/** The shape `selectVisible` needs from an NPC — just enough to rank and place it. */
export type PlaceableNpc = {
  code: string
  layer: TownNpcLayer
  schedule: NpcScheduleSlot[]
}

/**
 * Picks which NPCs are on screen this hour, capped at `cap`. Deterministic and stable: the same
 * `(npcs, hour, cap)` always yields the same set in the same order — no randomness, so the
 * roster never reshuffles frame to frame. NPCs whose active slot is `home` are considered
 * indoors (not on screen). Layers 1 and 2 are prioritised over layer 3; ties break on `code`
 * (a stable, arbitrary-but-fixed order) so the result never depends on input array order.
 */
export function selectVisible<T extends PlaceableNpc>(npcs: T[], hour: number, cap: number): T[] {
  const outAndAbout = npcs.filter(npc => {
    const slot = activeSlot(npc.schedule, hour)
    return slot !== null && slot.place !== 'home'
  })
  const rank = (layer: TownNpcLayer) => (layer === 3 ? 1 : 0)
  const sorted = [...outAndAbout].sort((a, b) => {
    const byLayer = rank(a.layer) - rank(b.layer)
    if (byLayer !== 0) return byLayer
    return a.code < b.code ? -1 : a.code > b.code ? 1 : 0
  })
  return sorted.slice(0, Math.max(0, cap))
}

/**
 * M7-8 站位纵向散开: how far an NPC's y may drift from `place`'s baseline. The sidewalk is a
 * narrow band (a big jitter would walk someone into the street or into a shopfront wall), while
 * the plaza and park are open ground with real depth to use — plan.md §3.5: "广场与公园允许更大
 * 的纵深".
 */
export const SIDEWALK_JITTER_PX = 10
export const OPEN_GROUND_JITTER_PX = 28

const OPEN_GROUND_PLACES: ReadonlySet<NpcPlace> = new Set(['plaza', 'park'])

/** FNV-1a, 32-bit — small, dependency-free, and good enough to spread a couple dozen npcCodes
 * without visibly clustering. Not used anywhere security-sensitive, just as a stable hash. */
function hashString(input: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < input.length; i++) {
    hash ^= input.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

/**
 * Deterministic y offset from `place`'s baseline for one NPC (M7-8). Pure function of
 * `(npcCode, place)` — same inputs always give the same offset, so re-rendering a frame (or
 * reloading the roster) never reshuffles who stands where. Different NPCs at the same place
 * land at different offsets because their codes hash differently; the same NPC gets a different
 * offset at a different place, so it doesn't look pinned to one exact spot as it moves around
 * town over the day.
 */
export function verticalJitter(npcCode: string, place: NpcPlace): number {
  const amplitude = OPEN_GROUND_PLACES.has(place) ? OPEN_GROUND_JITTER_PX : SIDEWALK_JITTER_PX
  const unit = hashString(`${npcCode}|${place}`) / 0xffffffff // hash spread over [0,1]
  return (unit * 2 - 1) * amplitude // -> [-amplitude, +amplitude]
}

/**
 * The convention `town.engine.ts` should follow when placing a sprite: `depth` tracks `y`
 * directly, so someone standing further "down" the screen draws in front of someone further
 * "up" instead of every jittered NPC drawing in whatever order the roster array happens to be
 * in. Written as a function (not just a comment) so the convention has one place to change if
 * the engine ever needs a constant offset between the two.
 */
export function depthForY(y: number): number {
  return y
}
