export type DimensionCode = 'KNOWLEDGE' | 'HEALTH' | 'CAREER' | 'RELATIONSHIP' | 'WELLBEING'

/** What a resident is doing today, derived from their task schedule. */
export type ResidentActivity = 'resting' | 'planned' | 'working' | 'done'

export type ScheduleStatus = 'PLANNED' | 'IN_PROGRESS' | 'DONE' | 'PARTIAL' | 'DEFERRED' | 'SKIPPED' | 'EXPIRED'

/** One task-schedule item as seen from the town: enough to know where a resident should stand and what they're doing. */
export type ScheduleItem = {
  publicId: string
  title: string
  status: ScheduleStatus
  roleCode: string | null
  roleName?: string | null
  plannedStartAt: string | null
  plannedEndAt?: string | null
  estimatedMinutes?: number | null
  difficulty?: number | null
}

export type TownResident = {
  publicId: string
  displayName: string
  level: number
  totalExperience: number
  dominantDimension: DimensionCode | null
  longestStreak: number
  todayPlanned: number
  todayDone: number
  todayStarted: number
  isSelf: boolean
  title: string | null
  schedules: ScheduleItem[]
  timezone: string
  presence?: TownPresence | null
}

export type TownPresence = { x: number; y: number; facing: string; scene: string; updatedAt?: string; stale?: boolean }

export type TownModel = {
  localDate: string
  serverTime: string
  residents: TownResident[]
  unread: number
  soloGrowth: boolean
}

/** A schedule that flipped to DONE/PARTIAL between two polls; the engine celebrates each one once. */
export type Celebration = { publicId: string; scheduleId: string; title: string }

/** Building pieces are stacked bottom-up by the renderer using real texture heights. */
export type Blueprint = {
  palette: 0 | 1 | 2
  ground: string
  middles: string[]
  roof: string
  roofProps: string[]
  open: boolean
}

/** 新手引导状态 */
export type OnboardingState = {
  completed: boolean
  currentStep: number
  skipped: boolean
}
