/**
 * TS mirror of CONTRACT.md §6 (`GET /town/npcs`, `GET /town/npc/{code}/talking-points`).
 * Keep in lockstep with the backend contract — do not invent fields here.
 */
import type { DimensionCode } from './town.types'

/** 1 = 锁死 2（小助/邮递员），2 = 六个可搭话人设，3 = 十个背景居民。See plan.md §2.4. */
export type TownNpcLayer = 1 | 2 | 3

/** Where an NPC can stand; the frontend maps each to a world x via `npc-placement.ts`. */
export type NpcPlace = 'home' | 'academy' | 'gym' | 'cafe' | 'park' | 'plaza' | 'street'

/** What an NPC is doing while at `place`. */
export type NpcActivity =
  | 'idle'
  | 'walking'
  | 'reading'
  | 'sit'
  | 'phone'
  | 'watering'
  | 'chopping'
  | 'fishing'
  | 'harvesting'
  | 'digging'

/** One slot of an NPC's daily schedule. Slots cover 0-24h with no holes, ascending by `startHour`. */
export type NpcScheduleSlot = {
  startHour: number
  endHour: number
  place: NpcPlace
  activity: NpcActivity
}

/** Where a day-plan errand came from (CONTRACT-M7.md §1). Purely informational for the
 * frontend — it never branches rendering on `origin`, but keeps the field so a round-trip
 * through this type doesn't silently drop backend data. */
export type NpcErrandOrigin = 'RHYTHM' | 'DEVIATION' | 'EVENT'

/** One thing an NPC is doing somewhere today (M7-2/M7-6). Minutes are local-time-of-day,
 * `[0,1440)`, counted from 00:00 — NOT hours like `NpcScheduleSlot`. `errands` within a
 * `NpcDayPlan` are ascending by `startMinute` and never overlap (CONTRACT-M7.md §1). */
export type NpcErrand = {
  place: NpcPlace
  activity: NpcActivity
  startMinute: number
  endMinute: number
  /** 0=可有可无 1=常态 2=要紧（活动/请柬插入的是 2）。 */
  priority: 0 | 1 | 2
  origin: NpcErrandOrigin
}

/** The commute between two consecutive errands. Derived, not separately authored:
 * `legs[i].arriveMinute === errands[i+1].startMinute` (CONTRACT-M7.md §1). */
export type NpcLeg = {
  fromPlace: NpcPlace
  toPlace: NpcPlace
  departMinute: number
  arriveMinute: number
}

/**
 * A whole day's itinerary for one NPC (CONTRACT-M7.md §1). `errands ∪ legs` covers the full
 * day with no holes (0 → 1440); `positionAt` (see `day-plan.ts`) is the pure function both
 * frontend and backend use to turn this plus a minute-of-day into a place-or-in-transit result.
 * Same `(npcCode, date)` is expected to always produce the same `NpcDayPlan` — this type itself
 * carries no npcCode because the server already scopes one `dayPlan` per `TownNpcView`.
 */
export type NpcDayPlan = {
  /** Local calendar date this plan is for, e.g. `"2026-09-06"`. */
  date: string
  errands: NpcErrand[]
  legs: NpcLeg[]
}

/** A single thing an NPC can say today, already walked-through-the-grapevine text. */
export type NpcTalkingPoint = {
  factId: string
  text: string
  hops: number
  salience: number
}

/** One NPC's full view as returned by `GET /town/npcs`. */
export type TownNpcView = {
  code: string
  displayName: string
  layer: TownNpcLayer
  sprite: string
  /** Only layer-2 NPCs bind to a dimension; layer 1/3 are always `null`. */
  dimension: DimensionCode | null
  interests: Partial<Record<DimensionCode, number>>
  affinityToPlayer: number
  mood: { valence: number; energy: number }
  /** Covers 0-24h, no holes, ascending by `startHour`. Kept alongside `dayPlan` for backwards
   * compatibility (CONTRACT-M7.md §1: the backend sends both during the M7 rollout). */
  schedule: NpcScheduleSlot[]
  /** M7: the richer errands+legs itinerary `positionAt` consumes. Optional because older
   * backends (pre-M7) don't send it yet — callers must fall back to `dayPlanFallback(schedule)`
   * (see `day-plan.ts`) rather than assume this is always present. */
  dayPlan?: NpcDayPlan
  /** At most 3, salience descending. */
  talkingPoints: NpcTalkingPoint[]
}

/** 护栏 A: the whole town shares one daily budget of NPC-initiated greetings. */
export type InitiativeBudget = {
  limit: number
  used: number
}

/** `data` shape of `GET /town/npcs` — an object, not a bare array (see CONTRACT.md §6). */
export type TownNpcsResponse = {
  npcs: TownNpcView[]
  initiativeBudget: InitiativeBudget
}

/** `data` shape of `GET /town/npc/{code}/talking-points`. */
export type TownNpcTalkingPointsResponse = {
  code: string
  displayName: string
  points: NpcTalkingPoint[]
}
