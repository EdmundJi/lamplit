import type { Blueprint, DimensionCode, ResidentActivity, ScheduleItem, TownResident } from './town.types'

/** FNV-1a: stable per-user variation without storing anything. */
export function hashString(value: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193) >>> 0
  }
  return hash >>> 0
}

export function paletteFor(publicId: string): 0 | 1 | 2 {
  return (hashString(publicId) % 3) as 0 | 1 | 2
}

/** Levels are sparse on purpose: a new floor every two levels, four at most. */
export function floorsForLevel(level: number): number {
  return Math.max(0, Math.min(4, Math.floor((Math.max(1, level) - 1) / 2)))
}

export function activityFor(resident: Pick<TownResident, 'todayPlanned' | 'todayDone' | 'todayStarted'>): ResidentActivity {
  if (resident.todayDone > 0) return 'done'
  if (resident.todayStarted > 0) return 'working'
  if (resident.todayPlanned > 0) return 'planned'
  return 'resting'
}

/**
 * Ground floor by growth direction. Each LimeZu storefront ships in three palettes
 * with an "open" and a "shutters down" variant; the shutters come down when the
 * neighbour has not touched today's plan yet. The player's home stays welcoming on rest days.
 */
export function groundFloorFor(dimension: DimensionCode | null, palette: 0 | 1 | 2, open: boolean): string {
  const closed = open ? 0 : 1
  switch (dimension) {
    case 'KNOWLEDGE': return `shop_${palette * 12 + 1 + closed}`
    case 'HEALTH': return `gym_${palette * 4 + 1 + closed}`
    case 'CAREER': return `condo_${palette * 4 + 2}`
    case 'RELATIONSHIP': return `icecream_${palette * 2 + 1 + closed}`
    case 'WELLBEING': return `bakery_${palette * 4 + 1 + closed}`
    default: return `condo_${palette * 4 + 1}`
  }
}

export function roofPropsFor(longestStreak: number): string[] {
  const props: string[] = []
  if (longestStreak >= 3) props.push('roofprop_3')
  if (longestStreak >= 7) props.push('roofprop_8')
  if (longestStreak >= 21) props.push('roofprop_1')
  return props
}

export function buildBlueprint(resident: TownResident): Blueprint {
  const palette = paletteFor(resident.publicId)
  const seed = hashString(`${resident.publicId}:floors`)
  const floors = floorsForLevel(resident.level)
  const activity = activityFor(resident)
  const open = resident.isSelf || activity !== 'resting'
  const middles = Array.from({ length: floors }, (_, index) => `middle_${palette * 6 + 1 + ((seed >>> (index * 3)) % 6)}`)
  const roofProps = roofPropsFor(resident.longestStreak)
  const flatRoof = roofProps.length > 0 || floors >= 3
  const roof = `roof_${palette * 2 + (flatRoof ? 1 : 2)}`
  return { palette, ground: groundFloorFor(resident.dominantDimension, palette, open), middles, roof, roofProps, open }
}

export const dimensionLabels: Record<DimensionCode, string> = {
  KNOWLEDGE: '知识',
  HEALTH: '健康',
  CAREER: '职场',
  RELATIONSHIP: '关系',
  WELLBEING: '心境',
}

export const activityLabels: Record<ResidentActivity, string> = {
  resting: '今天慢慢来，也可以歇一歇',
  planned: '今天有安排，还没开始',
  working: '正在进行中',
  done: '今天已经有收获',
}

// ---------- schedule-driven presence ----------
// Rules below follow the 场地映射 / 日程驱动规则 tables in docs/成长小镇-接口约定.md §1 exactly.

export type TownVenue = 'home' | 'academy' | 'court' | 'park'
export type VenueAction = 'idle' | 'read' | 'phone'

/** 场地映射: which spot in the town a task's role sends its resident to. */
export function venueForRole(roleCode: string | null | undefined): TownVenue {
  if (roleCode === 'STUDENT' || roleCode === 'WORKER') return 'academy'
  if (roleCode === 'FITNESS_USER') return 'court'
  if (roleCode === 'EMOTIONAL_SUPPORT_USER') return 'park'
  return 'home'
}

/** What the resident does once they've arrived at the role's venue. */
export function actionForRole(roleCode: string | null | undefined): VenueAction {
  if (roleCode === 'STUDENT') return 'read'
  if (roleCode === 'WORKER') return 'phone'
  return 'idle'
}

const ACTIVE_STATUSES = new Set<string>(['PLANNED', 'IN_PROGRESS'])
const DONE_STATUSES = new Set<string>(['DONE', 'PARTIAL'])
const MIN_WINDOW_MINUTES = 30
const LINGER_MS = 2 * 60 * 60 * 1000

export type ScheduleWindow = { start: number; end: number; lingerEnd: number }

/**
 * Absolute [start, end] (epoch ms) a schedule item occupies, plus the instant the 2-hour
 * "stay after finishing" linger runs out. Falls back to `estimatedMinutes` (at least 30)
 * when there is no `plannedEndAt`. `serverOffsetMs` (serverTime - Date.now() at load time)
 * shifts the window into local-clock coordinates so callers can compare it straight against
 * a local `Date.now()` without recomputing anything.
 */
export function scheduleWindow(
  item: Pick<ScheduleItem, 'plannedStartAt' | 'plannedEndAt' | 'estimatedMinutes'>,
  serverOffsetMs = 0,
): ScheduleWindow | null {
  if (!item.plannedStartAt) return null
  const start = new Date(item.plannedStartAt).getTime()
  if (Number.isNaN(start)) return null
  const parsedEnd = item.plannedEndAt ? new Date(item.plannedEndAt).getTime() : NaN
  const minutes = Math.max(MIN_WINDOW_MINUTES, item.estimatedMinutes ?? 0)
  const end = Number.isNaN(parsedEnd) ? start + minutes * 60_000 : parsedEnd
  return { start: start - serverOffsetMs, end: end - serverOffsetMs, lingerEnd: end - serverOffsetMs + LINGER_MS }
}

export type WhereShouldBe = { venue: TownVenue; action: VenueAction; schedule?: ScheduleItem }

/**
 * 日程驱动规则: where a resident should be standing right now and what they're doing there.
 * `now` is a local `Date.now()`-style timestamp; `serverOffsetMs` calibrates it against the
 * schedule's server-clock timestamps. Falls back to home/idle when nothing matches.
 */
export function whereShouldBe(resident: Pick<TownResident, 'schedules'>, now: number, serverOffsetMs = 0): WhereShouldBe {
  for (const item of resident.schedules ?? []) {
    const window = scheduleWindow(item, serverOffsetMs)
    if (!window) continue
    const active = ACTIVE_STATUSES.has(item.status) && now >= window.start && now <= window.end
    const lingering = DONE_STATUSES.has(item.status) && now > window.end && now <= window.lingerEnd
    if (active || lingering) return { venue: venueForRole(item.roleCode), action: actionForRole(item.roleCode), schedule: item }
  }
  return { venue: 'home', action: 'idle' }
}
