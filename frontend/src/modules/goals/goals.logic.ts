import { computed, onBeforeUnmount, onMounted, reactive, ref, type Component } from 'vue'
import { BookOpen, BriefcaseBusiness, Dumbbell, HeartPulse } from 'lucide-vue-next'
import { api, type ApiError } from '../../shared/api/client'
import { encouragement } from '../../shared/encouragement'
import { notifyDataChanged, onDataChanged } from '../../shared/data-sync'
import { takeGoalDraft, type GoalDraft, type GoalDraftTask } from '../ai/goal-draft'

/**
 * 「目标规划」的取数与业务动作：整页 GoalsView 和沉浸式 GoalsPanel 共用这一份逻辑。
 * 逻辑本身不依赖路由或 DOM，面板与整页各自决定用多大的界面呈现它。
 */

export type Goal = {
  publicId: string
  dimensionPublicId: string
  title: string
  description: string
  startDate: string
  endDate: string
  status: string
}
export type Dimension = { publicId: string; code: string; name: string }
export type Task = {
  publicId: string
  weeklyPlanPublicId: string
  goalPublicId: string
  title: string
  notes: string
  estimatedMinutes: number
  difficulty: number
  rrule: string | null
  plannedLocalTime: string
  activeFrom: string
  activeUntil: string
  active: boolean
  roleCode: string
}
export type RoleCode = 'STUDENT' | 'FITNESS_USER' | 'WORKER' | 'EMOTIONAL_SUPPORT_USER'
export type Preset = {
  publicId: string
  roleCode: RoleCode
  roleName: string
  name: string
  notes: string
  estimatedMinutes: number
  difficulty: number
  plannedLocalTime: string
  rrule: string
  dimensionCode: string
  dimensionWeight: number
  experienceReward: number
}
export type PresetDraw = {
  roleCode: RoleCode
  roleName: string
  localDate: string
  refreshesRemaining: number
  items: Preset[]
}
/** Shape of the reactive goal-drawer form; extracted so drawer components can type their props. */
export type GoalForm = {
  dimensionPublicId: string
  title: string
  description: string
  startDate: string
  endDate: string
}
/** Shape of the reactive task-drawer form; extracted so drawer components can type their props. */
export type TaskForm = {
  goalPublicId: string
  title: string
  notes: string
  estimatedMinutes: number
  difficulty: number
  rrule: string
  plannedLocalTime: string
  activeFrom: string
  activeUntil: string
  sourceTemplatePublicId: string
  roleCode: RoleCode
  dimensionCode: string
  dimensionWeight: number
}
export type GoalTemplate = {
  roleCode: RoleCode
  title: string
  description: string
  dimensionName: string
  durationDays: number
  icon: Component
  tasks: string[]
}

export const roles: { code: RoleCode; name: string; dimension: string }[] = [
  { code: 'STUDENT', name: '学生', dimension: 'KNOWLEDGE' },
  { code: 'FITNESS_USER', name: '健身用户', dimension: 'HEALTH' },
  { code: 'WORKER', name: '打工人', dimension: 'CAREER' },
  { code: 'EMOTIONAL_SUPPORT_USER', name: '情绪支持用户', dimension: 'WELLBEING' },
]
export const goalTemplates: GoalTemplate[] = [
  {
    roleCode: 'STUDENT',
    title: '四周完成一轮学习复盘',
    description: '每周完成一次知识整理，并在第 4 周产出一份可回看的复盘笔记。',
    dimensionName: '知识',
    durationDays: 28,
    icon: BookOpen,
    tasks: ['每周整理 1 章要点', '用 25 分钟做错题回看', '周末写 5 行复盘'],
  },
  {
    roleCode: 'FITNESS_USER',
    title: '建立温和的身体照顾习惯',
    description: '以不透支为前提，每周完成 3 次低压力训练或恢复行动。',
    dimensionName: '健康',
    durationDays: 28,
    icon: Dumbbell,
    tasks: ['20 分钟轻训练', '训练后记录感受', '每周安排 1 次恢复'],
  },
  {
    roleCode: 'WORKER',
    title: '推进一个可交付的职场成果',
    description: '把一个工作成果拆成每周可验证的小交付，减少临时抱佛脚。',
    dimensionName: '职业',
    durationDays: 28,
    icon: BriefcaseBusiness,
    tasks: ['明确本周交付物', '每天推进一个 25 分钟片段', '周五整理风险'],
  },
  {
    roleCode: 'EMOTIONAL_SUPPORT_USER',
    title: '建立一套情绪支持流程',
    description: '在四周内形成可重复的自我照顾、求助和复盘方式。',
    dimensionName: '情绪',
    durationDays: 28,
    icon: HeartPulse,
    tasks: ['写下今日状态', '准备可信任联系人', '复盘一次恢复经验'],
  },
]
export const roleNames = Object.fromEntries(roles.map(role => [role.code, role.name])) as Record<string, string>
export const rruleOptions: [string, string][] = [
  ['', '仅一次'],
  ['FREQ=DAILY', '每天'],
  ['FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR', '每个工作日'],
  ['FREQ=WEEKLY;BYDAY=MO,WE,FR', '每周一、三、五'],
  ['FREQ=WEEKLY;BYDAY=TU,TH', '每周二、四'],
  ['FREQ=WEEKLY;BYDAY=TU,TH,SA', '每周二、四、六'],
  ['FREQ=WEEKLY;BYDAY=WE,SA', '每周三、六'],
  ['FREQ=WEEKLY;BYDAY=MO,TH', '每周一、四'],
  ['FREQ=WEEKLY;BYDAY=TU,FR', '每周二、五'],
  ['FREQ=WEEKLY;BYDAY=MO,WE', '每周一、三'],
  ['FREQ=WEEKLY;BYDAY=MO,FR', '每周一、五'],
  ['FREQ=WEEKLY;BYDAY=FR', '每周五'],
  ['FREQ=WEEKLY;BYDAY=TH', '每周四'],
  ['FREQ=WEEKLY;BYDAY=SA', '每周六'],
  ['FREQ=WEEKLY;BYDAY=SU', '每周日'],
  ['FREQ=WEEKLY;BYDAY=TU,SA', '每周二、六'],
]

export function formatLocalDate(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function today() {
  return formatLocalDate(new Date())
}

export function plusDays(value: string, days: number) {
  const date = new Date(`${value}T00:00:00`)
  date.setDate(date.getDate() + days)
  return formatLocalDate(date)
}

export function statusLabel(value: string) {
  return ({ ACTIVE: '进行中', PAUSED: '已暂停', COMPLETED: '已完成', DRAFT: '草稿' } as Record<string, string>)[value] ?? value
}

export function rruleLabel(value: string | null) {
  return rruleOptions.find(option => option[0] === (value ?? ''))?.[1] ?? '自定义周期'
}

function friendlyGoalError(value: unknown, fallback: string) {
  const code = (value as Partial<ApiError> | null)?.code
  const messages: Record<string, string> = {
    ACTIVE_GOAL_LIMIT: '同时进行的活跃目标最多 3 个，请先完成或暂停一个目标再创建。',
    INVALID_GOAL_DURATION: '目标时长需在 14 到 84 天之间，请检查起止日期。',
    INVALID_GOAL_TRANSITION: '当前状态下不能执行该操作，请刷新后再试。',
  }
  return messages[code ?? ''] ?? fallback
}

function friendlyTaskError(value: unknown) {
  const code = (value as Partial<ApiError> | null)?.code
  const messages: Record<string, string> = {
    TASK_OUTSIDE_GOAL: '任务周期需要在目标的起止日期内。',
    INVALID_TASK_DATES: '任务结束日期不能早于开始日期。',
    GOAL_NOT_FOUND: '这个目标当前不能添加任务，请选择进行中的目标。',
    INVALID_RRULE: '任务周期设置无效，请重新选择。',
  }
  return messages[code ?? ''] ?? '任务未保存，请检查周期、日期和其他属性'
}

function boundedNumber(value: unknown, fallback: number, min: number, max: number) {
  const parsed = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.round(Math.min(max, Math.max(min, parsed)))
}

export type UseGoalsLogicOptions = {
  /** 目标被标记完成时触发；小镇面板用它驱动庆祝动画，整页面不需要传。 */
  onCelebrate?: (publicId: string) => void
}

export function useGoalsLogic(options: UseGoalsLogicOptions = {}) {
  const goals = ref<Goal[]>([])
  const dimensions = ref<Dimension[]>([])
  const tasks = ref<Task[]>([])
  const loading = ref(true)
  const panel = ref<'goal' | 'task' | null>(null)
  const error = ref('')
  const busy = ref(false)
  const feedback = ref<{ tone: 'support' | 'celebrate'; text: string } | null>(null)
  const goalPrompt = ref(encouragement('goalDraft'))
  const selectedRole = ref<RoleCode>('STUDENT')
  const presetDraw = ref<PresetDraw | null>(null)
  const presetsLoading = ref(false)
  const presetError = ref('')
  const goalsCurrent = ref(0)
  const aiStarterTasks = ref<GoalDraftTask[]>([])
  const aiStarterDimensionCode = ref('')
  /** 非空时，目标抽屉处于「编辑」而非「新建」模式。 */
  const editingGoalPublicId = ref<string | null>(null)
  /** 非空时，任务抽屉处于「编辑」而非「新建」模式。 */
  const editingTaskPublicId = ref<string | null>(null)

  const goalForm = reactive<GoalForm>({
    dimensionPublicId: '',
    title: '',
    description: '',
    startDate: today(),
    endDate: plusDays(today(), 27),
  })
  const taskForm = reactive<TaskForm>({
    goalPublicId: '',
    title: '',
    notes: '',
    estimatedMinutes: 25,
    difficulty: 2,
    rrule: 'FREQ=DAILY',
    plannedLocalTime: '19:00',
    activeFrom: today(),
    activeUntil: '',
    sourceTemplatePublicId: '',
    roleCode: 'STUDENT' as RoleCode,
    dimensionCode: 'KNOWLEDGE',
    dimensionWeight: 10,
  })

  const currentGoal = computed(() => goals.value[goalsCurrent.value] ?? null)
  const currentTasks = computed(() => currentGoal.value
    ? tasks.value.filter(task => task.goalPublicId === currentGoal.value?.publicId)
    : [])
  const activeGoals = computed(() => goals.value.filter(goal => goal.status === 'ACTIVE'))
  const visibleGoals = computed(() => goals.value.filter(goal => goal.status === 'ACTIVE' || goal.status === 'PAUSED'))
  const canPrevGoals = computed(() => goalsCurrent.value > 0)
  const canNextGoals = computed(() => goalsCurrent.value < goals.value.length - 1)

  function goalsPrev() {
    goalsCurrent.value = Math.max(0, goalsCurrent.value - 1)
  }

  function goalsNext() {
    goalsCurrent.value = Math.min(goals.value.length - 1, goalsCurrent.value + 1)
  }

  async function load(showLoading = true) {
    if (showLoading) loading.value = true
    const selectedGoalId = currentGoal.value?.publicId
    try {
      const [goalRows, dimensionRows, taskRows] = await Promise.all([
        api.get<Goal[]>('/goals'),
        api.get<Dimension[]>('/dimensions'),
        api.get<Task[]>('/tasks'),
      ])
      goals.value = goalRows
      dimensions.value = dimensionRows
      tasks.value = taskRows
      const selectedIndex = selectedGoalId ? goalRows.findIndex(goal => goal.publicId === selectedGoalId) : -1
      goalsCurrent.value = selectedIndex >= 0 ? selectedIndex : 0
      goalForm.dimensionPublicId ||= dimensionRows[0]?.publicId ?? ''
    } catch {
      error.value = '目标与任务暂时无法加载'
    } finally {
      if (showLoading) loading.value = false
    }
  }

  function resetGoalForm() {
    goalForm.dimensionPublicId = dimensions.value[0]?.publicId ?? ''
    goalForm.title = ''
    goalForm.description = ''
    goalForm.startDate = today()
    goalForm.endDate = plusDays(today(), 27)
  }

  function openGoal() {
    editingGoalPublicId.value = null
    resetGoalForm()
    goalPrompt.value = encouragement('goalDraft')
    feedback.value = null
    if (panel.value !== 'goal') {
      aiStarterTasks.value = []
      aiStarterDimensionCode.value = ''
    }
    panel.value = 'goal'
  }

  /** 编辑一个已有目标：不重新生成起步任务，保存走 PATCH。 */
  function openGoalForEdit(goal: Goal) {
    editingGoalPublicId.value = goal.publicId
    aiStarterTasks.value = []
    aiStarterDimensionCode.value = ''
    goalForm.dimensionPublicId = goal.dimensionPublicId
    goalForm.title = goal.title
    goalForm.description = goal.description
    goalForm.startDate = goal.startDate
    goalForm.endDate = goal.endDate
    goalPrompt.value = '调整这个目标的名称、完成标准或起止日期。'
    feedback.value = null
    panel.value = 'goal'
  }

  function cancelGoal() {
    panel.value = null
    editingGoalPublicId.value = null
    aiStarterTasks.value = []
    aiStarterDimensionCode.value = ''
  }

  function syncTaskPeriod(goalPublicId = taskForm.goalPublicId) {
    const goal = goals.value.find(item => item.publicId === goalPublicId)
    if (!goal) return
    const start = today() < goal.startDate || today() > goal.endDate ? goal.startDate : today()
    taskForm.activeFrom = start
    taskForm.activeUntil = goal.endDate
  }

  function resetTaskForm(goalPublicId: string) {
    const role = roles.find(item => item.code === selectedRole.value) ?? roles[0]
    taskForm.goalPublicId = goalPublicId
    taskForm.title = ''
    taskForm.notes = ''
    taskForm.estimatedMinutes = 25
    taskForm.difficulty = 2
    taskForm.rrule = 'FREQ=DAILY'
    taskForm.plannedLocalTime = '19:00'
    taskForm.sourceTemplatePublicId = ''
    taskForm.roleCode = role.code
    taskForm.dimensionCode = role.dimension
    taskForm.dimensionWeight = 10
    syncTaskPeriod(goalPublicId)
  }

  async function openTask(goalPublicId?: string) {
    const target = activeGoals.value.find(goal => goal.publicId === goalPublicId)
      ?? (currentGoal.value?.status === 'ACTIVE' ? currentGoal.value : activeGoals.value[0])
    if (!target) {
      error.value = '请先创建一个进行中的目标'
      return
    }
    error.value = ''
    feedback.value = null
    editingTaskPublicId.value = null
    resetTaskForm(target.publicId)
    panel.value = 'task'
    await loadPresets(false)
  }

  /** 编辑一个已有周期任务：跳过模板选择，直接展示可编辑字段，保存走 PATCH。 */
  function openTaskForEdit(task: Task) {
    editingTaskPublicId.value = task.publicId
    error.value = ''
    feedback.value = null
    taskForm.goalPublicId = task.goalPublicId
    taskForm.title = task.title
    taskForm.notes = task.notes
    taskForm.estimatedMinutes = task.estimatedMinutes
    taskForm.difficulty = task.difficulty
    taskForm.rrule = task.rrule ?? ''
    taskForm.plannedLocalTime = task.plannedLocalTime.slice(0, 5)
    taskForm.activeFrom = task.activeFrom
    taskForm.activeUntil = task.activeUntil
    taskForm.sourceTemplatePublicId = ''
    taskForm.roleCode = (task.roleCode as RoleCode) ?? 'STUDENT'
    presetDraw.value = null
    panel.value = 'task'
  }

  async function loadPresets(refresh: boolean) {
    presetsLoading.value = true
    presetError.value = ''
    try {
      presetDraw.value = refresh
        ? await api.post<PresetDraw>(`/task-presets/refresh?role=${selectedRole.value}`)
        : await api.get<PresetDraw>(`/task-presets?role=${selectedRole.value}`)
    } catch (err: any) {
      presetError.value = err?.code === 'TASK_PRESET_REFRESH_LIMIT'
        ? '今天的换一批机会已经用完了'
        : '任务模板暂时无法加载，请稍后重试'
    } finally {
      presetsLoading.value = false
    }
  }

  async function chooseRole(role: typeof roles[number]) {
    selectedRole.value = role.code
    taskForm.roleCode = role.code
    taskForm.dimensionCode = role.dimension
    taskForm.dimensionWeight = 10
    taskForm.sourceTemplatePublicId = ''
    await loadPresets(false)
  }

  function choosePreset(preset: Preset) {
    taskForm.title = preset.name
    taskForm.notes = preset.notes
    taskForm.estimatedMinutes = preset.estimatedMinutes
    taskForm.difficulty = preset.difficulty
    taskForm.rrule = preset.rrule
    taskForm.plannedLocalTime = preset.plannedLocalTime.slice(0, 5)
    taskForm.sourceTemplatePublicId = preset.publicId
    taskForm.roleCode = preset.roleCode
    taskForm.dimensionCode = preset.dimensionCode
    taskForm.dimensionWeight = preset.dimensionWeight
  }

  function applyGoalTemplate(template: GoalTemplate) {
    editingGoalPublicId.value = null
    aiStarterTasks.value = []
    aiStarterDimensionCode.value = ''
    const dimension = dimensions.value.find(item => String(item.name).includes(template.dimensionName)) ?? dimensions.value[0]
    goalForm.dimensionPublicId = dimension?.publicId ?? goalForm.dimensionPublicId
    goalForm.title = template.title
    goalForm.description = template.description
    goalForm.startDate = today()
    goalForm.endDate = plusDays(goalForm.startDate, template.durationDays - 1)
    selectedRole.value = template.roleCode
    goalPrompt.value = `可以从这些行动开始：${template.tasks.join('、')}。`
    feedback.value = null
    panel.value = 'goal'
  }

  function applyAiGoalDraft(draft: GoalDraft) {
    editingGoalPublicId.value = null
    const dimension = dimensions.value.find(item => item.code === draft.dimensionCode) ?? dimensions.value[0]
    aiStarterTasks.value = Array.isArray(draft.starterTasks)
      ? draft.starterTasks
        .filter(task => task && typeof task.title === 'string' && task.title.trim())
        .map(task => ({
          title: task.title.trim(),
          estimatedMinutes: task.estimatedMinutes,
          difficulty: task.difficulty,
        }))
      : []
    aiStarterDimensionCode.value = typeof draft.dimensionCode === 'string' ? draft.dimensionCode : ''
    goalForm.dimensionPublicId = dimension?.publicId ?? goalForm.dimensionPublicId
    goalForm.title = draft.title
    goalForm.description = draft.description
    goalForm.startDate = today()
    goalForm.endDate = plusDays(goalForm.startDate, draft.durationDays - 1)
    const taskTitles = aiStarterTasks.value.map(task => task.title).join('、')
    goalPrompt.value = taskTitles
      ? `本周重点：${draft.weeklyFocus} 起步任务：${taskTitles}。`
      : `本周重点：${draft.weeklyFocus}`
    feedback.value = { tone: 'support', text: 'AI 目标草案已填入。你可以继续修改，确认后再保存。' }
    panel.value = 'goal'
  }

  /** 载入时若有 AI 会话留下的目标草案，自动填入表单（一次性，取走即清空）。 */
  function absorbAiGoalDraft() {
    const draft = takeGoalDraft()
    if (draft) applyAiGoalDraft(draft)
  }

  function aiTaskDimensionCode(created: Goal) {
    return dimensions.value.find(item => item.publicId === goalForm.dimensionPublicId)?.code
      ?? dimensions.value.find(item => item.publicId === created.dimensionPublicId)?.code
      ?? aiStarterDimensionCode.value
  }

  async function createAiStarterTasks(created: Goal, starterTasks: GoalDraftTask[], startDate: string, endDate: string) {
    const dimensionCode = aiTaskDimensionCode(created)
    if (!dimensionCode) {
      return { created: 0, failed: starterTasks.length, firstError: { code: 'INVALID_DIMENSION_WEIGHTS' } }
    }

    let createdCount = 0
    let firstError: unknown
    for (const starter of starterTasks) {
      try {
        await api.post('/tasks', {
          goalPublicId: created.publicId,
          title: starter.title.trim(),
          notes: '',
          estimatedMinutes: boundedNumber(starter.estimatedMinutes, 15, 5, 240),
          difficulty: boundedNumber(starter.difficulty, 2, 1, 3),
          rrule: null,
          plannedLocalTime: '09:00',
          activeFrom: startDate,
          activeUntil: endDate,
          dimensionWeights: { [dimensionCode]: 10 },
        })
        createdCount += 1
      } catch (failure) {
        firstError ??= failure
      }
    }
    return { created: createdCount, failed: starterTasks.length - createdCount, firstError }
  }

  /** 新建目标（含 AI 起步任务）或保存对已有目标的编辑，取决于 editingGoalPublicId。 */
  async function createGoal() {
    busy.value = true
    error.value = ''
    const editingId = editingGoalPublicId.value
    if (editingId) {
      try {
        const updated = await api.patch<Goal>(`/goals/${editingId}`, goalForm)
        const index = goals.value.findIndex(goal => goal.publicId === editingId)
        if (index >= 0) goals.value[index] = updated
        panel.value = null
        editingGoalPublicId.value = null
        feedback.value = { tone: 'support', text: '目标已更新。' }
        notifyDataChanged(['goals', 'today', 'insights'])
      } catch (err) {
        error.value = friendlyGoalError(err, '目标未保存，请检查日期与内容')
      } finally {
        busy.value = false
      }
      return
    }

    const starterTasks = aiStarterTasks.value.map(task => ({ ...task }))
    const startDate = goalForm.startDate
    const endDate = goalForm.endDate
    try {
      const created = await api.post<Goal>('/goals', goalForm)
      const taskResult = starterTasks.length
        ? await createAiStarterTasks(created, starterTasks, startDate, endDate)
        : { created: 0, failed: 0, firstError: undefined }
      panel.value = null
      aiStarterTasks.value = []
      aiStarterDimensionCode.value = ''
      feedback.value = {
        tone: 'support',
        text: taskResult.created
          ? `${encouragement('goalCreated')} 已创建 ${taskResult.created} 个起步任务。`
          : encouragement('goalCreated'),
      }
      if (taskResult.failed) {
        const detail = taskResult.firstError ? friendlyTaskError(taskResult.firstError) : '请在目标下重新添加。'
        error.value = `目标已保存，但 ${taskResult.failed} 个起步任务未保存。${detail}`
      }
      await load(false)
      notifyDataChanged(['goals', 'tasks', 'today', 'insights'])
      const index = goals.value.findIndex(goal => goal.publicId === created.publicId)
      if (index >= 0) goalsCurrent.value = index
    } catch (err) {
      error.value = friendlyGoalError(err, '目标未保存，请检查日期与内容')
    } finally {
      busy.value = false
    }
  }

  /** 新建周期任务，或保存对已有任务的编辑，取决于 editingTaskPublicId。 */
  async function createTask() {
    busy.value = true
    error.value = ''
    const editingId = editingTaskPublicId.value
    try {
      if (editingId) {
        const updated = await api.patch<Task>(`/tasks/${editingId}`, {
          title: taskForm.title,
          notes: taskForm.notes,
          estimatedMinutes: taskForm.estimatedMinutes,
          difficulty: taskForm.difficulty,
          rrule: taskForm.rrule || null,
          plannedLocalTime: taskForm.plannedLocalTime,
          activeFrom: taskForm.activeFrom,
          activeUntil: taskForm.activeUntil,
          dimensionWeights: { [taskForm.dimensionCode]: taskForm.dimensionWeight },
        })
        const index = tasks.value.findIndex(task => task.publicId === editingId)
        if (index >= 0) tasks.value[index] = updated
        panel.value = null
        editingTaskPublicId.value = null
        feedback.value = { tone: 'support', text: '任务已更新。' }
      } else {
        await api.post('/tasks', {
          goalPublicId: taskForm.goalPublicId,
          title: taskForm.title,
          notes: taskForm.notes,
          estimatedMinutes: taskForm.estimatedMinutes,
          difficulty: taskForm.difficulty,
          rrule: taskForm.rrule || null,
          plannedLocalTime: taskForm.plannedLocalTime,
          activeFrom: taskForm.activeFrom,
          activeUntil: taskForm.activeUntil,
          sourceTemplatePublicId: taskForm.sourceTemplatePublicId || null,
          roleCode: taskForm.roleCode,
          dimensionWeights: { [taskForm.dimensionCode]: taskForm.dimensionWeight },
        })
        panel.value = null
        feedback.value = { tone: 'support', text: '任务与周期已保存，并已排入对应日期。' }
      }
      await load(false)
      notifyDataChanged(['goals', 'tasks', 'today', 'insights'])
    } catch (err) {
      error.value = friendlyTaskError(err)
    } finally {
      busy.value = false
    }
  }

  async function setTaskActive(task: Task, active: boolean) {
    error.value = ''
    try {
      const updated = await api.post<Task>(`/tasks/${task.publicId}/${active ? 'resume' : 'pause'}`)
      task.active = updated.active
      notifyDataChanged(['goals', 'tasks', 'today', 'insights'])
    } catch {
      error.value = '任务状态暂时无法更新，请重试'
    }
  }

  async function setGoalStatus(goal: Goal, action: 'pause' | 'resume' | 'complete') {
    error.value = ''
    try {
      goal.status = (await api.post<Goal>(`/goals/${goal.publicId}/${action}`)).status
      if (action === 'complete') {
        feedback.value = { tone: 'celebrate', text: encouragement('goalCompleted') }
        options.onCelebrate?.(goal.publicId)
      }
      notifyDataChanged(['goals', 'today', 'insights'])
    } catch (err) {
      error.value = friendlyGoalError(err, '目标状态暂时无法更新，请重试')
    }
  }

  const stopDataSync = onDataChanged(['goals', 'tasks'], () => load(false))

  onMounted(async () => {
    await load()
    absorbAiGoalDraft()
  })
  onBeforeUnmount(stopDataSync)

  return {
    goals, dimensions, tasks, loading, panel, error, busy, feedback, goalPrompt,
    selectedRole, presetDraw, presetsLoading, presetError, goalsCurrent,
    aiStarterTasks, editingGoalPublicId, editingTaskPublicId,
    goalForm, taskForm,
    currentGoal, currentTasks, activeGoals, visibleGoals, canPrevGoals, canNextGoals,
    goalsPrev, goalsNext, load,
    openGoal, openGoalForEdit, cancelGoal, syncTaskPeriod,
    openTask, openTaskForEdit, loadPresets, chooseRole, choosePreset,
    applyGoalTemplate, applyAiGoalDraft,
    createGoal, createTask, setTaskActive, setGoalStatus,
  }
}

export type GoalsLogic = ReturnType<typeof useGoalsLogic>
