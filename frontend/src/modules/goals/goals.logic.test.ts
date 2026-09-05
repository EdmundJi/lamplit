import { effectScope } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { statusLabel, useGoalsLogic } from './goals.logic'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const dimensions = [{ publicId: 'dimension-1', code: 'KNOWLEDGE', name: '知识' }]
const activeGoal = { publicId: 'goal-1', dimensionPublicId: 'dimension-1', title: '学习', description: '', startDate: '2026-01-01', endDate: '2026-03-01', status: 'ACTIVE' }

function standardGet(path: string) {
  if (path === '/goals') return Promise.resolve([{ ...activeGoal }])
  if (path === '/dimensions') return Promise.resolve(dimensions)
  if (path === '/tasks') return Promise.resolve([])
  return Promise.resolve([])
}

/** onMounted/onBeforeUnmount are no-ops outside a component; drive the initial load ourselves. */
async function setup() {
  const scope = effectScope()
  const logic = scope.run(() => useGoalsLogic())!
  await logic.load()
  return { logic, dispose: () => scope.stop() }
}

describe('useGoalsLogic', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    api.get.mockReset().mockImplementation(standardGet)
    api.post.mockReset()
    api.patch.mockReset()
  })

  it('translates every goal status to its Chinese label', () => {
    expect(statusLabel('ACTIVE')).toBe('进行中')
    expect(statusLabel('PAUSED')).toBe('已暂停')
    expect(statusLabel('COMPLETED')).toBe('已完成')
    expect(statusLabel('DRAFT')).toBe('草稿')
    expect(statusLabel('UNKNOWN')).toBe('UNKNOWN')
  })

  it('moves a goal through pause -> resume -> complete, celebrating only on completion', async () => {
    const onCelebrate = vi.fn()
    const scope = effectScope()
    const logic = scope.run(() => useGoalsLogic({ onCelebrate }))!
    await logic.load()
    const goal = logic.goals.value[0]

    api.post.mockResolvedValueOnce({ ...goal, status: 'PAUSED' })
    await logic.setGoalStatus(goal, 'pause')
    expect(goal.status).toBe('PAUSED')
    expect(onCelebrate).not.toHaveBeenCalled()

    api.post.mockResolvedValueOnce({ ...goal, status: 'ACTIVE' })
    await logic.setGoalStatus(goal, 'resume')
    expect(goal.status).toBe('ACTIVE')
    expect(onCelebrate).not.toHaveBeenCalled()

    api.post.mockResolvedValueOnce({ ...goal, status: 'COMPLETED' })
    await logic.setGoalStatus(goal, 'complete')
    expect(goal.status).toBe('COMPLETED')
    expect(onCelebrate).toHaveBeenCalledWith('goal-1')
    scope.stop()
  })

  it('reports a friendly message and leaves status untouched when a transition is rejected', async () => {
    const { logic, dispose } = await setup()
    const goal = logic.goals.value[0]
    api.post.mockRejectedValueOnce({ code: 'INVALID_GOAL_TRANSITION' })
    await logic.setGoalStatus(goal, 'complete')
    expect(goal.status).toBe('ACTIVE')
    expect(logic.error.value).toContain('当前状态下不能执行该操作')
    dispose()
  })

  it('edits a goal through PATCH and updates the local list without touching starter tasks', async () => {
    // the edit itself notifies 'goals', which the same logic instance listens on and reloads from —
    // so the GET mock must reflect the PATCH, exactly as the real backend would.
    const goalsStore = [{ ...activeGoal }]
    api.get.mockImplementation((path: string) => {
      if (path === '/goals') return Promise.resolve(goalsStore.map(item => ({ ...item })))
      if (path === '/dimensions') return Promise.resolve(dimensions)
      if (path === '/tasks') return Promise.resolve([])
      return Promise.resolve([])
    })
    const { logic, dispose } = await setup()
    const goal = logic.goals.value[0]
    logic.openGoalForEdit(goal)
    expect(logic.editingGoalPublicId.value).toBe('goal-1')
    logic.goalForm.title = '更新后的标题'

    api.patch.mockImplementationOnce((_path: string, body: Record<string, unknown>) => {
      goalsStore[0] = { ...goalsStore[0], ...body }
      return Promise.resolve(goalsStore[0])
    })
    await logic.createGoal()
    await flushPromises()

    expect(api.patch).toHaveBeenCalledWith('/goals/goal-1', expect.objectContaining({ title: '更新后的标题' }))
    expect(api.post).not.toHaveBeenCalledWith('/goals', expect.anything())
    expect(logic.goals.value[0].title).toBe('更新后的标题')
    expect(logic.editingGoalPublicId.value).toBeNull()
    dispose()
  })

  it('edits a periodic task through PATCH, keeping the goal and role untouched', async () => {
    const tasksStore = [{
      publicId: 'task-1', weeklyPlanPublicId: 'plan-1', goalPublicId: 'goal-1', title: '原任务', notes: '',
      estimatedMinutes: 25, difficulty: 2, rrule: 'FREQ=DAILY', plannedLocalTime: '19:00:00',
      activeFrom: '2026-01-01', activeUntil: '2026-03-01', active: true, roleCode: 'STUDENT',
    }]
    api.get.mockImplementation((path: string) => {
      if (path === '/tasks') return Promise.resolve(tasksStore.map(item => ({ ...item })))
      return standardGet(path)
    })
    const { logic, dispose } = await setup()
    const task = logic.tasks.value[0]
    logic.openTaskForEdit(task)
    expect(logic.editingTaskPublicId.value).toBe('task-1')
    logic.taskForm.title = '调整后的任务'

    api.patch.mockImplementationOnce((_path: string, body: Record<string, unknown>) => {
      tasksStore[0] = { ...tasksStore[0], ...body }
      return Promise.resolve(tasksStore[0])
    })
    await logic.createTask()
    await flushPromises()

    expect(api.patch).toHaveBeenCalledWith('/tasks/task-1', expect.objectContaining({ title: '调整后的任务' }))
    expect(api.patch.mock.calls[0][1]).not.toHaveProperty('goalPublicId')
    expect(api.post).not.toHaveBeenCalledWith('/tasks', expect.anything())
    expect(logic.tasks.value[0].title).toBe('调整后的任务')
    dispose()
  })
})
