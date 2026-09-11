import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, type ApiError } from '../../shared/api/client'
import { notifyDataChanged, onDataChanged } from '../../shared/data-sync'
import { randomUUID } from '../../shared/uuid'
import { encouragement, type EncouragementMoment } from '../../shared/encouragement'

/**
 * 「今日执行」的取数与业务动作：整页 TodayView 和沉浸式 TodayPanel 共用这一份逻辑，
 * 只在渲染层各自决定展示多少内容。逻辑本身不依赖任何 UI 框架以外的东西（无路由、无 DOM 查询）。
 */

export type Task = {
  publicId: string
  taskPublicId?: string
  taskTitle: string
  plannedStartAt: string
  status: string
  roleCode?: string
  roleName?: string
  estimatedMinutes?: number
  difficulty?: number
}
export type Goal = { publicId: string; title: string; description: string; startDate: string; endDate: string; status: string }
export type CheckMood = 'steady' | 'low' | 'open'
export type DailyStatus = {
  publicId: string
  localDate: string
  energy: 'LOW' | 'STEADY' | 'OPEN'
  availableMinutes: number
  advice: 'SHRINK' | 'KEEP' | 'LIGHT'
  updatedAt: string
}
export type TaskEventType = 'STARTED' | 'COMPLETED' | 'PARTIAL' | 'DEFERRED' | 'SKIPPED'

export const DAILY_LIMIT_MESSAGE = '今天已完成 4 个任务，明天再继续吧'

export function localDate(value = new Date()) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function localDateTime(value: number) {
  const date = new Date(value - new Date(value).getTimezoneOffset() * 60000)
  return date.toISOString().slice(0, 16)
}

/** 判断错误码是否是"今日完成额度用尽"，供 UI 挑选合适的提示文案。 */
export function isDailyLimitError(caught: unknown) {
  return (caught as Partial<ApiError> | undefined)?.code === 'DAILY_TASK_COMPLETION_LIMIT_REACHED'
}

export type UseTodayLogicOptions = {
  /** 任务被标记完成时触发；小镇面板用它驱动庆祝动画，整页面不需要传。 */
  onCelebrate?: (publicId: string) => void
}

export function useTodayLogic(options: UseTodayLogicOptions = {}) {
  const tasks = ref<Task[]>([])
  const goals = ref<Goal[]>([])
  const loading = ref(true)
  const error = ref('')
  const feedback = ref<{ tone: 'support' | 'celebrate'; text: string; experience?: string } | null>(null)
  const last = ref<{ schedule: string; event: string } | null>(null)
  const pending = ref(new Set<string>())
  const actionKeys = new Map<string, string>()
  const selected = ref<{ task: Task; eventType: 'PARTIAL' | 'DEFERRED' } | null>(null)
  const completionPercent = ref(50)
  const deferredStart = ref(localDateTime(Date.now() + 86400000))

  const checkMood = ref<CheckMood>('steady')
  const availableMinutes = ref(30)
  const checkSubmitted = ref(false)
  const showCheck = ref(false)
  const savedAdvice = ref<'SHRINK' | 'KEEP' | 'LIGHT' | null>(null)
  const recoveryChoice = ref('')
  const focusTask = ref<Task | null>(null)
  const focusRunning = ref(false)
  const focusSeconds = ref(25 * 60)
  let focusTimer: ReturnType<typeof setInterval> | undefined

  const plannedTasks = computed(() => tasks.value.filter(task => ['PLANNED', 'IN_PROGRESS'].includes(task.status)))
  const strainedTasks = computed(() => tasks.value.filter(task => ['DEFERRED', 'SKIPPED', 'EXPIRED'].includes(task.status)))
  const activeGoals = computed(() => goals.value.filter(goal => goal.status === 'ACTIVE'))
  const completedTaskCount = computed(() => tasks.value.filter(task => task.status === 'DONE').length)
  const suggestedPlan = computed(() => {
    if (checkMood.value === 'low' || availableMinutes.value < 20) {
      return { title: '缩小任务', body: '今天先保留一件最小行动，把完成比例目标降到 50%。', action: '缩小今天', advice: 'SHRINK' as const }
    }
    if (checkMood.value === 'open' && availableMinutes.value >= 45) {
      return { title: '保持原计划', body: '状态和时间都足够，适合按原计划推进，但仍然保留延期入口。', action: '保持节奏', advice: 'KEEP' as const }
    }
    return { title: '轻量推进', body: '先完成最靠前的一项任务，剩余任务根据实际精力决定。', action: '先做一项', advice: 'LIGHT' as const }
  })
  const activeAdvice = computed(() => savedAdvice.value ?? suggestedPlan.value.advice)
  const recommendedTasks = computed(() => {
    const list = tasks.value.filter(task => ['PLANNED', 'IN_PROGRESS'].includes(task.status)).slice()
    if (activeAdvice.value === 'SHRINK') {
      list.sort((a, b) => (a.estimatedMinutes ?? 99) - (b.estimatedMinutes ?? 99))
    } else {
      list.sort((a, b) => new Date(a.plannedStartAt).getTime() - new Date(b.plannedStartAt).getTime())
    }
    return list
  })
  const recommendedPublicIds = computed(() => {
    if (activeAdvice.value === 'SHRINK') return new Set(recommendedTasks.value.slice(0, 2).map(task => task.publicId))
    if (activeAdvice.value === 'LIGHT') return new Set(recommendedTasks.value.slice(0, 1).map(task => task.publicId))
    return new Set<string>()
  })
  const dailyGuidance = computed(() => {
    if (activeAdvice.value === 'SHRINK') return '今日建议：每项控制在 15 分钟以内，完成比例目标降到 50%。'
    if (activeAdvice.value === 'KEEP') return '今日建议：按原计划推进，保留延期入口。'
    return '今日建议：先完成一项，再根据实际精力决定下一项。'
  })
  const focusMinutes = computed(() => Math.max(5, Math.round(focusSeconds.value / 60)))
  const focusClock = computed(() => {
    const minutes = Math.floor(focusSeconds.value / 60).toString().padStart(2, '0')
    const seconds = Math.floor(focusSeconds.value % 60).toString().padStart(2, '0')
    return `${minutes}:${seconds}`
  })

  async function load(showLoading = true) {
    if (showLoading) loading.value = true
    error.value = ''
    try {
      const d = localDate()
      const [todayTasks, activeGoalList, status] = await Promise.all([
        api.get<Task[]>(`/task-schedules?localDate=${d}`),
        api.get<Goal[]>('/goals?status=ACTIVE'),
        api.get<DailyStatus | null>('/daily-status').catch(() => null),
      ])
      tasks.value = todayTasks
      goals.value = activeGoalList
      if (status) {
        checkMood.value = status.energy === 'LOW' ? 'low' : status.energy === 'OPEN' ? 'open' : 'steady'
        availableMinutes.value = status.availableMinutes
        savedAdvice.value = status.advice
        checkSubmitted.value = true
      }
    } catch {
      error.value = '今天的任务暂时无法加载'
    } finally {
      if (showLoading) loading.value = false
    }
  }

  function prepare(task: Task, eventType: 'PARTIAL' | 'DEFERRED') {
    selected.value = { task, eventType }
  }

  function canActOn(task: Task) {
    return ['PLANNED', 'IN_PROGRESS'].includes(task.status) && !Array.from(pending.value).some(key => key.startsWith(`${task.publicId}:`))
  }

  function canComplete(task: Task) {
    return canActOn(task)
  }

  async function applyCheck() {
    checkSubmitted.value = true
    try {
      const saved = await api.post<DailyStatus>('/daily-status', {
        energy: checkMood.value === 'low' ? 'LOW' : checkMood.value === 'open' ? 'OPEN' : 'STEADY',
        availableMinutes: availableMinutes.value,
      })
      savedAdvice.value = saved.advice
      notifyDataChanged(['today', 'insights'])
      feedback.value = { tone: 'support', text: `已按“${suggestedPlan.value.title}”整理今天：${suggestedPlan.value.body}` }
      return true
    } catch {
      error.value = '状态保存失败，请稍后重试'
      checkSubmitted.value = false
      return false
    }
  }

  function applyRecovery(choice: string) {
    recoveryChoice.value = choice
    feedback.value = { tone: 'support', text: `恢复计划已选：${choice}。系统不会追责中断，今天只需要重新拿回一点节奏。` }
  }

  function startFocus(task: Task) {
    focusTask.value = task
    focusSeconds.value = Math.max(5, task.estimatedMinutes ?? 25) * 60
    focusRunning.value = false
  }

  function toggleFocus() {
    focusRunning.value = !focusRunning.value
    if (focusRunning.value) {
      focusTimer = setInterval(() => {
        if (focusSeconds.value <= 1) {
          focusSeconds.value = 0
          focusRunning.value = false
          clearInterval(focusTimer)
          return
        }
        focusSeconds.value -= 1
      }, 1000)
    } else {
      clearInterval(focusTimer)
    }
  }

  function closeFocus() {
    clearInterval(focusTimer)
    focusRunning.value = false
    focusTask.value = null
  }

  async function act(task: Task, eventType: TaskEventType, extra: Record<string, unknown> = {}) {
    const intent = `${task.publicId}:${eventType}`
    if (pending.value.has(intent)) return
    const key = actionKeys.get(intent) ?? randomUUID()
    actionKeys.set(intent, key)
    pending.value.add(intent)
    error.value = ''
    try {
      const result: any = await api.post(`/task-schedules/${task.publicId}/events`, { eventType, ...extra }, { 'Idempotency-Key': key })
      task.status = result.scheduleStatus
      last.value = { schedule: task.publicId, event: result.eventPublicId }
      const moments: Partial<Record<string, EncouragementMoment>> = { STARTED: 'taskStarted', COMPLETED: 'taskCompleted', PARTIAL: 'taskPartial', DEFERRED: 'taskDeferred', SKIPPED: 'taskSkipped' }
      const moment = moments[eventType]
      if (moment) {
        feedback.value = {
          tone: eventType === 'COMPLETED' ? 'celebrate' : 'support',
          text: encouragement(moment),
          experience: [
            result.roleExperienceDelta > 0 ? `${result.roleProgress.roleName} +${result.roleExperienceDelta} 经验 · LV.${result.roleProgress.level}` : '',
            result.coinDelta > 0 ? `金币 +${result.coinDelta}` : '',
          ].filter(Boolean).join(' · ') || undefined,
        }
      }
      if (eventType === 'COMPLETED') options.onCelebrate?.(task.publicId)
      notifyDataChanged(['tasks', 'today', 'insights', 'attributes', 'achievements', 'profile', 'partners', 'town'])
      actionKeys.delete(intent)
      selected.value = null
    } catch (caught) {
      error.value = isDailyLimitError(caught) ? DAILY_LIMIT_MESSAGE : '记录未保存，请重试'
    } finally {
      pending.value.delete(intent)
    }
  }

  async function confirmAction() {
    if (!selected.value) return
    const { task, eventType } = selected.value
    if (eventType === 'PARTIAL') await act(task, eventType, { completionRatio: completionPercent.value / 100 })
    else await act(task, eventType, { deferredStartAt: new Date(deferredStart.value).toISOString() })
  }

  async function finishFocus(eventType: 'COMPLETED' | 'PARTIAL') {
    if (!focusTask.value) return
    const task = focusTask.value
    closeFocus()
    if (eventType === 'PARTIAL') await act(task, eventType, { completionRatio: 0.5 })
    else await act(task, eventType)
  }

  async function reverse() {
    if (!last.value) return
    await api.post(`/task-schedules/${last.value.schedule}/events/${last.value.event}/reverse`, undefined, { 'Idempotency-Key': randomUUID() })
    last.value = null
    feedback.value = null
    await load(false)
    notifyDataChanged(['tasks', 'today', 'insights', 'attributes', 'achievements', 'profile', 'partners', 'town'])
  }

  const stopDataSync = onDataChanged(['goals', 'tasks'], () => load(false))

  onMounted(() => load(true))
  onBeforeUnmount(() => {
    stopDataSync()
    clearInterval(focusTimer)
  })

  return {
    tasks, goals, loading, error, feedback, last, pending,
    selected, completionPercent, deferredStart,
    checkMood, availableMinutes, checkSubmitted, showCheck, savedAdvice, recoveryChoice,
    focusTask, focusRunning, focusSeconds,
    plannedTasks, strainedTasks, activeGoals, completedTaskCount,
    suggestedPlan, activeAdvice, recommendedTasks, recommendedPublicIds, dailyGuidance, focusMinutes, focusClock,
    load, prepare, canActOn, canComplete, applyCheck, applyRecovery, startFocus, toggleFocus, closeFocus,
    act, confirmAction, finishFocus, reverse,
  }
}

export type TodayLogic = ReturnType<typeof useTodayLogic>
