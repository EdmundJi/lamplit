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
  /** Covers 0-24h, no holes, ascending by `startHour`. */
  schedule: NpcScheduleSlot[]
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
