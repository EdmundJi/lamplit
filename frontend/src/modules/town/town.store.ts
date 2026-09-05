import { defineStore } from 'pinia'
import { api, type ApiError } from '../../shared/api/client'
import type { FriendList, FriendProfile, FriendTask, UnreadSummary } from '../friends/friends.types'
import type { Celebration, DimensionCode, ScheduleItem, ScheduleStatus, TownModel, TownResident, TownPresence } from './town.types'
import type { PresencePayload } from './presence'
import { reconcilePresence } from './presence'

type Profile = { publicId: string; displayName: string; overallLevel: number; totalExperience: number; longestStreak?: number; soloGrowth?: boolean; equippedTitle?: { name: string } | null }
type AttributesOverview = { attributes: { code: string; experience: number }[] }
/** Shape returned by `/task-schedules`, see modules/today/TodayView.vue. */
type LegacyTask = { publicId: string; taskTitle: string; plannedStartAt: string; status: string; roleCode?: string; roleName?: string; estimatedMinutes?: number; difficulty?: number }

/** `GET /town` response, see docs/成长小镇-接口约定.md §1. */
type TownApiResident = {
  publicId: string
  displayName: string
  level: number
  totalExperience: number
  dominantDimension: DimensionCode | null
  longestStreak: number
  title?: string | null
  self?: boolean
  timezone?: string
  schedules?: ScheduleItem[]
  presence?: TownPresence | null
}
type TownApiResponse = {
  localDate: string
  serverTime: string
  unread: number
  soloGrowth: boolean
  residents: TownApiResident[]
}

const DONE = new Set(['DONE', 'PARTIAL'])
const STARTED = new Set(['IN_PROGRESS', 'STARTED'])
const PLANNED = new Set(['PLANNED', 'IN_PROGRESS', 'STARTED'])
const POLL_INTERVAL_MS = 30_000
const DEFAULT_TIMEZONE = 'Asia/Shanghai'

export function localDate(value = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
}

export function dominantDimension(attributes: { code: string; experience: number }[]): DimensionCode | null {
  const known: DimensionCode[] = ['KNOWLEDGE', 'HEALTH', 'CAREER', 'RELATIONSHIP', 'WELLBEING']
  const best = attributes
    .filter(row => known.includes(row.code as DimensionCode) && row.experience > 0)
    .sort((a, b) => b.experience - a.experience)[0]
  return best ? (best.code as DimensionCode) : null
}

export function summarizeTasks(tasks: { status: string }[]) {
  return {
    todayPlanned: tasks.filter(task => PLANNED.has(task.status) || DONE.has(task.status)).length,
    todayDone: tasks.filter(task => DONE.has(task.status)).length,
    todayStarted: tasks.filter(task => STARTED.has(task.status)).length,
  }
}

function scheduleItemsFromLegacyTasks(tasks: LegacyTask[]): ScheduleItem[] {
  return tasks.map(task => ({
    publicId: task.publicId,
    title: task.taskTitle,
    status: task.status as ScheduleStatus,
    roleCode: task.roleCode ?? null,
    roleName: task.roleName ?? null,
    plannedStartAt: task.plannedStartAt ?? null,
    plannedEndAt: null,
    estimatedMinutes: task.estimatedMinutes ?? null,
    difficulty: task.difficulty ?? null,
  }))
}

function scheduleItemsFromFriendTasks(tasks: FriendTask[]): ScheduleItem[] {
  // FriendTask carries no roleCode today, so schedule-driven placement degrades to "at home"
  // for neighbours loaded through this fallback — acceptable, it's a temporary bridge.
  return tasks.map(task => ({
    publicId: task.publicId,
    title: task.title,
    status: task.status as ScheduleStatus,
    roleCode: null,
    roleName: task.roleName ?? null,
    plannedStartAt: task.plannedStartAt ?? null,
    plannedEndAt: null,
    estimatedMinutes: null,
    difficulty: null,
  }))
}

export function residentFromFriend(profile: FriendProfile): TownResident {
  const schedules = scheduleItemsFromFriendTasks(profile.todayTasks ?? [])
  return {
    publicId: profile.publicId,
    displayName: profile.displayName,
    level: profile.overallLevel,
    totalExperience: profile.totalExperience,
    dominantDimension: dominantDimension(profile.attributes ?? []),
    longestStreak: profile.longestStreak ?? 0,
    ...summarizeTasks(schedules),
    isSelf: false,
    title: null,
    schedules,
    timezone: DEFAULT_TIMEZONE,
  }
}

function residentFromTownApi(row: TownApiResident): TownResident {
  const schedules = row.schedules ?? []
  return {
    publicId: row.publicId,
    displayName: row.displayName,
    level: row.level,
    totalExperience: row.totalExperience,
    dominantDimension: row.dominantDimension,
    longestStreak: row.longestStreak,
    ...summarizeTasks(schedules),
    isSelf: Boolean(row.self),
    title: row.self ? (row.title ?? null) : null,
    schedules,
    timezone: row.timezone ?? DEFAULT_TIMEZONE,
    presence: row.presence ?? null,
  }
}

/**
 * Schedules whose status flipped to DONE/PARTIAL between two consecutive models. `previous`
 * is `null` on the very first load, which intentionally yields no celebrations — only new
 * progress made while the resident is watching earns fireworks.
 */
export function diffCelebrations(previous: TownModel | null, next: TownModel): Celebration[] {
  if (!previous) return []
  const celebrations: Celebration[] = []
  for (const resident of next.residents) {
    const prevResident = previous.residents.find(item => item.publicId === resident.publicId)
    for (const item of resident.schedules) {
      if (!DONE.has(item.status)) continue
      const prevItem = prevResident?.schedules.find(row => row.publicId === item.publicId)
      if (prevItem && DONE.has(prevItem.status)) continue
      celebrations.push({ publicId: resident.publicId, scheduleId: item.publicId, title: item.title })
    }
  }
  return celebrations
}

async function loadLegacyModel(): Promise<TownModel> {
  const date = localDate()
  const [profile, attributes, tasks, friends, unread] = await Promise.all([
    api.get<Profile>('/me/profile'),
    api.get<AttributesOverview>('/insights/attributes').catch(() => ({ attributes: [] })),
    api.get<LegacyTask[]>(`/task-schedules?localDate=${date}`).catch(() => [] as LegacyTask[]),
    api.get<FriendList>('/friends').catch(() => ({ friends: [], incoming: [], outgoing: [] }) as FriendList),
    api.get<UnreadSummary>('/friends/unread-summary').catch(() => null),
  ])
  const schedules = scheduleItemsFromLegacyTasks(tasks)
  const self: TownResident = {
    publicId: profile.publicId,
    displayName: profile.displayName,
    level: profile.overallLevel,
    totalExperience: profile.totalExperience,
    dominantDimension: dominantDimension(attributes.attributes),
    longestStreak: profile.longestStreak ?? 0,
    ...summarizeTasks(schedules),
    isSelf: true,
    title: profile.equippedTitle?.name ?? null,
    schedules,
    timezone: DEFAULT_TIMEZONE,
  }
  const accepted = profile.soloGrowth ? [] : friends.friends.filter(item => item.status === 'ACCEPTED')
  const profiles = await Promise.all(accepted.map(item => api.get<FriendProfile>(`/friends/${item.publicId}`).catch(() => null)))
  const neighbours = profiles.filter((item): item is FriendProfile => item !== null).map(residentFromFriend)
  return {
    localDate: date,
    serverTime: new Date().toISOString(),
    residents: [self, ...neighbours],
    unread: unread?.totalUnread ?? 0,
    soloGrowth: Boolean(profile.soloGrowth),
  }
}

async function fetchTownModel(): Promise<TownModel> {
  try {
    const response = await api.get<TownApiResponse>('/town')
    return {
      localDate: response.localDate,
      serverTime: response.serverTime,
      unread: response.unread ?? 0,
      soloGrowth: Boolean(response.soloGrowth),
      residents: response.residents.map(residentFromTownApi),
    }
  } catch (error) {
    if ((error as ApiError).status === 404) return loadLegacyModel()
    throw error
  }
}

export const useTownStore = defineStore('town', {
  state: () => ({
    model: null as TownModel | null,
    loading: false,
    error: '',
    lastCelebrations: [] as Celebration[],
    pollHandle: null as ReturnType<typeof setInterval> | null,
  }),
  actions: {
    /** `silent: true` is used by the poller: no loading spinner, no error banner for a transient hiccup. */
    async load(opts: { silent?: boolean } = {}) {
      if (!opts.silent) { this.loading = true; this.error = '' }
      try {
        const next = await fetchTownModel()
        this.lastCelebrations = diffCelebrations(this.model, next)
        this.model = next
        if (opts.silent) this.error = ''
      } catch (error) {
        if (!opts.silent) this.error = (error as { message?: string }).message ?? '小镇暂时没能加载出来，请稍后再试'
      } finally {
        if (!opts.silent) this.loading = false
      }
    },

    /** Polls `GET /town` every 30s, skipping the request while the tab is hidden. */
    startPolling() {
      this.stopPolling()
      this.pollHandle = setInterval(() => {
        if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return
        void this.load({ silent: true })
      }, POLL_INTERVAL_MS)
    },

    stopPolling() {
      if (this.pollHandle !== null) { clearInterval(this.pollHandle); this.pollHandle = null }
    },

    /** Report self presence to server. Silently fails (404/501 -> graceful degradation). */
    async reportPresence(payload: PresencePayload): Promise<TownPresence | null> {
      try {
        const response = await api.post<TownPresence>('/town/presence', payload)
        return reconcilePresence(payload, response)
      } catch (error) {
        const status = (error as ApiError).status
        // 404/501: backend not yet deployed, graceful degradation
        if (status === 404 || status === 501) return null
        // Other errors: log but don't crash
        console.warn('Failed to report presence:', error)
        return null
      }
    },
  },
})
