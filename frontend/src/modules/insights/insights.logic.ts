import { computed, reactive, ref } from 'vue'
import { api } from '../../shared/api/client'
import type { Achievement } from '../achievements/achievement.types'

export type InsightOverview = {
  effectiveActions: number
  fulfillmentRate: number
  recoveryCount: number
  totalExperience: number
  statusCheckCount: number
  statusAdvices: Record<string, number>
}
export type RoleProgress = {
  roleCode: string
  roleName: string
  level: number
  experience: number
  maxExperience: number
  totalExperience: number
  nextLevelExperience: number | null
  experienceToNextLevel: number
  levelProgressPercent: number
}
export type TrendRow = { date: string; effectiveActions: number; experience: number }
export type WeeklyPlan = { publicId: string; goalPublicId: string; weekStartDate: string; timezone: string; status: string }
export type WeeklyReview = {
  publicId: string
  planPublicId: string
  facts: Record<string, unknown>
  userReflection: string | null
  proposedAdjustments: Record<string, unknown>
  confirmedAdjustments: Record<string, unknown>
  confirmedAt: string | null
}
export type CalendarDay = { date: string; label: number; currentMonth: boolean; tone: 'done' | 'partial' | 'empty'; row?: TrendRow }
export type ChangeSummary = { headline: string; detail: string; hasBaseline: boolean }
export type WeeklyReviewForm = { userReflection: string; steadyAction: string; shrinkAction: string; nextAction: string }

export function localDateKey(date: Date) {
  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function currentMonday(reference = new Date()) {
  const date = new Date(reference)
  const weekday = date.getDay() || 7
  date.setDate(date.getDate() - weekday + 1)
  return localDateKey(date)
}

function buildTrendMap(trends: TrendRow[]) {
  return new Map(trends.map(row => [row.date, row]))
}

export function buildCalendarDays(trends: TrendRow[], reference = new Date()): CalendarDay[] {
  const trendMap = buildTrendMap(trends)
  const first = new Date(reference.getFullYear(), reference.getMonth(), 1)
  const start = new Date(first)
  start.setDate(first.getDate() - first.getDay())
  return Array.from({ length: 42 }, (_, index) => {
    const day = new Date(start)
    day.setDate(start.getDate() + index)
    const date = localDateKey(day)
    const row = trendMap.get(date)
    return {
      date,
      label: day.getDate(),
      currentMonth: day.getMonth() === reference.getMonth(),
      tone: (row?.effectiveActions ? 'done' : row?.experience ? 'partial' : 'empty') as CalendarDay['tone'],
      row,
    }
  })
}

export function monthLabel(reference = new Date()) {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long' }).format(reference)
}

/** Only compares windows that actually have records; without a baseline it states facts and stops. */
export function computeChangeSummary(trends: TrendRow[], reference = new Date()): ChangeSummary {
  const trendMap = buildTrendMap(trends)
  const windowSum = (offset: number) => {
    let actions = 0
    let days = 0
    for (let index = 0; index < 7; index++) {
      const day = new Date(reference)
      day.setDate(reference.getDate() - offset - index)
      const row = trendMap.get(localDateKey(day))
      if (row) {
        actions += row.effectiveActions
        days += 1
      }
    }
    return { actions, days }
  }
  if (!trends.length) {
    return { headline: '还没有可以对比的记录', detail: '完成一次行动之后，这里会用你自己的历史做对比。', hasBaseline: false }
  }
  const recent = windowSum(0)
  const previous = windowSum(7)
  if (!previous.days) {
    return {
      headline: `最近 7 天有 ${recent.actions} 次有效行动`,
      detail: '还没有上一周的记录可比，先积累一周，再看变化。',
      hasBaseline: false,
    }
  }
  const diff = recent.actions - previous.actions
  const trend = diff > 0 ? `比上一周多 ${diff} 次` : diff < 0 ? `比上一周少 ${-diff} 次` : '和上一周持平'
  return {
    headline: `最近 7 天有 ${recent.actions} 次有效行动，${trend}`,
    detail: `上一周同样长度的窗口是 ${previous.actions} 次。这里只和你自己的历史比较，不做排名。`,
    hasBaseline: true,
  }
}

export function advicePercent(overview: InsightOverview | null, advice: string) {
  const count = overview?.statusCheckCount ?? 0
  return count ? Math.round(((overview?.statusAdvices?.[advice] ?? 0) * 100) / count) : 0
}

export function earnedAtLabel(value: string | null) {
  if (!value) return ''
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(value))
}

export function confirmedAtLabel(value: string | null | undefined) {
  if (!value) return ''
  return new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function stringValue(values: Record<string, unknown>, key: string) {
  const value = values[key]
  return typeof value === 'string' ? value : ''
}

export function reviewFact(review: WeeklyReview | null, key: string) {
  const value = review?.facts[key]
  return typeof value === 'number' ? value : Number(value ?? 0)
}

export function fulfillmentPercent(review: WeeklyReview | null) {
  return Math.round(reviewFact(review, 'fulfillmentRate') * 100)
}

/** 周复盘的草稿/确认状态机：读取 -> 反复保存草稿 -> 确认后归档。整页与小镇面板共用同一份逻辑。 */
export function useWeeklyReview() {
  const review = ref<WeeklyReview | null>(null)
  const form = reactive<WeeklyReviewForm>({ userReflection: '', steadyAction: '', shrinkAction: '', nextAction: '' })
  const loading = ref(false)
  const saving = ref(false)
  const confirming = ref(false)
  const error = ref('')
  const feedback = ref('')
  const confirmed = computed(() => Boolean(review.value?.confirmedAt))

  function apply(value: WeeklyReview) {
    review.value = value
    form.userReflection = value.userReflection ?? ''
    form.steadyAction = stringValue(value.proposedAdjustments, 'steadyAction')
    form.shrinkAction = stringValue(value.proposedAdjustments, 'shrinkAction')
    form.nextAction = stringValue(value.proposedAdjustments, 'nextAction')
  }

  function reset() {
    review.value = null
    form.userReflection = ''
    form.steadyAction = ''
    form.shrinkAction = ''
    form.nextAction = ''
  }

  async function load(planId: string) {
    reset()
    error.value = ''
    feedback.value = ''
    if (!planId) return
    loading.value = true
    try {
      apply(await api.get<WeeklyReview>(`/reviews/weekly/${planId}`))
    } catch {
      error.value = '这份周复盘暂时无法加载'
    } finally {
      loading.value = false
    }
  }

  function payload() {
    return {
      userReflection: form.userReflection,
      proposedAdjustments: { steadyAction: form.steadyAction, shrinkAction: form.shrinkAction, nextAction: form.nextAction },
    }
  }

  /** Saves the draft; returns whether it succeeded so confirm() can chain on it. */
  async function save(planId: string, showFeedback = true) {
    if (!planId || confirmed.value) return false
    saving.value = true
    error.value = ''
    feedback.value = ''
    try {
      apply(await api.patch<WeeklyReview>(`/reviews/weekly/${planId}`, payload()))
      if (showFeedback) feedback.value = '复盘草稿已保存'
      return true
    } catch {
      error.value = '复盘草稿暂时无法保存'
      return false
    } finally {
      saving.value = false
    }
  }

  async function confirm(planId: string) {
    if (!(await save(planId, false))) return false
    confirming.value = true
    error.value = ''
    try {
      apply(await api.post<WeeklyReview>(`/reviews/weekly/${planId}/confirm`))
      feedback.value = '本周复盘已确认'
      return true
    } catch {
      error.value = '复盘暂时无法确认，请重试'
      return false
    } finally {
      confirming.value = false
    }
  }

  return { review, form, loading, saving, confirming, error, feedback, confirmed, load, save, confirm, reset }
}

/** 洞察概览：本周指标、趋势、职业等级、成就与本周计划列表，整页与面板按需取用同一份状态。 */
export function useInsightsOverview() {
  const overview = ref<InsightOverview | null>(null)
  const trends = ref<TrendRow[]>([])
  const roles = ref<RoleProgress[]>([])
  const achievements = ref<Achievement[]>([])
  const weeklyPlans = ref<WeeklyPlan[]>([])
  const selectedPlanId = ref('')
  const loading = ref(true)
  const error = ref('')

  const changeSummary = computed(() => computeChangeSummary(trends.value))
  const calendarDays = computed(() => buildCalendarDays(trends.value))
  const earnedAchievementCount = computed(() => achievements.value.filter(item => item.earned).length)
  const leadRole = computed(() => roles.value.slice().sort((a, b) => b.levelProgressPercent - a.levelProgressPercent)[0] ?? null)
  const selectedPlan = computed(() => weeklyPlans.value.find(plan => plan.publicId === selectedPlanId.value) ?? null)

  async function load(showLoading = true) {
    if (showLoading) loading.value = true
    error.value = ''
    try {
      const weekStart = currentMonday()
      const [overviewData, trendRows, roleRows, achievementRows, planRows] = await Promise.all([
        api.get<InsightOverview>('/insights/overview'),
        api.get<TrendRow[]>('/insights/trends'),
        api.get<RoleProgress[]>('/progress/roles'),
        api.get<Achievement[]>('/achievements'),
        api.get<WeeklyPlan[]>(`/plans/weekly?weekStart=${weekStart}`),
      ])
      overview.value = overviewData
      trends.value = trendRows
      roles.value = roleRows
      achievements.value = achievementRows
      weeklyPlans.value = planRows
      const stillExists = planRows.some(plan => plan.publicId === selectedPlanId.value)
      selectedPlanId.value = stillExists ? selectedPlanId.value : planRows[0]?.publicId ?? ''
    } catch {
      error.value = '洞察暂时无法加载'
    } finally {
      if (showLoading) loading.value = false
    }
  }

  return {
    overview, trends, roles, achievements, weeklyPlans, selectedPlanId, loading, error,
    changeSummary, calendarDays, earnedAchievementCount, leadRole, selectedPlan, load,
  }
}
