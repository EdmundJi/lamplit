import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import GoalsView from './GoalsView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const goals = [
  {
    publicId: 'goal-1',
    dimensionPublicId: 'dimension-1',
    title: '完成四周学习',
    description: '完成四章',
    startDate: '2026-08-01',
    endDate: '2026-08-28',
    status: 'ACTIVE',
  },
]
const dimensions = [{ publicId: 'dimension-1', code: 'KNOWLEDGE', name: '知识' }]

function standardGet(path: string) {
  if (path === '/goals') return Promise.resolve(goals.map(goal => ({ ...goal })))
  if (path === '/dimensions') return Promise.resolve(dimensions.map(dimension => ({ ...dimension })))
  if (path === '/tasks') return Promise.resolve([])
  return Promise.resolve([])
}

describe('Goal and task workflow', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    api.get.mockReset().mockImplementation(standardGet)
    api.post.mockReset().mockResolvedValue({ status: 'COMPLETED' })
  })

  it('offers support while setting a goal and celebrates completion', async () => {
    const wrapper = mount(GoalsView)
    await flushPromises()

    await wrapper.get('.page-head button.primary').trigger('click')
    expect(wrapper.get('.support-line').text().length).toBeGreaterThan(12)

    await wrapper.get('button[aria-label="完成目标"]').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/goals/goal-1/complete')
    expect(wrapper.get('.feedback-banner[data-tone="celebrate"]').text().length).toBeGreaterThan(12)
  })

  it('creates a periodic task directly under a goal without loading weekly plans', async () => {
    const presets = {
      roleCode: 'STUDENT',
      roleName: '学生',
      localDate: '2026-08-04',
      refreshesRemaining: 3,
      items: Array.from({ length: 4 }, (_, index) => ({
        publicId: `preset-${index + 1}`,
        roleCode: 'STUDENT',
        roleName: '学生',
        name: `学习任务 ${index + 1}`,
        notes: `任务备注 ${index + 1}`,
        estimatedMinutes: 20 + index * 5,
        difficulty: 2,
        plannedLocalTime: '18:30:00',
        rrule: 'FREQ=WEEKLY;BYDAY=MO,WE,FR',
        dimensionCode: 'KNOWLEDGE',
        dimensionWeight: 10,
        experienceReward: 6,
      })),
    }
    api.get.mockImplementation((path: string) => {
      if (path.startsWith('/task-presets')) return Promise.resolve(presets)
      return standardGet(path)
    })
    api.post.mockImplementation((path: string) => path.startsWith('/task-presets/refresh')
      ? Promise.resolve({ ...presets, refreshesRemaining: 2 })
      : Promise.resolve({ publicId: 'task-1' }))

    const wrapper = mount(GoalsView)
    await flushPromises()
    await wrapper.get('.page-head button.secondary').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.preset-item')).toHaveLength(4)
    expect(wrapper.text()).toContain('今日还可换 3 次')
    expect((wrapper.get('#task-goal').element as HTMLSelectElement).value).toBe('goal-1')
    expect(api.get).not.toHaveBeenCalledWith('/plans/weekly')

    await wrapper.findAll('.preset-item')[0].trigger('click')
    expect((wrapper.get('#task-title').element as HTMLInputElement).value).toBe('学习任务 1')
    expect((wrapper.get('#task-minutes').element as HTMLInputElement).value).toBe('20')
    expect((wrapper.get('#task-rule').element as HTMLSelectElement).value).toBe('FREQ=WEEKLY;BYDAY=MO,WE,FR')
    expect(wrapper.get('.preset-item.selected').attributes('aria-pressed')).toBe('true')

    await wrapper.get('#task-start').setValue('2026-08-05')
    await wrapper.get('#task-end').setValue('2026-08-26')
    await wrapper.get('.task-builder').trigger('submit')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/tasks', expect.objectContaining({
      goalPublicId: 'goal-1',
      activeFrom: '2026-08-05',
      activeUntil: '2026-08-26',
      rrule: 'FREQ=WEEKLY;BYDAY=MO,WE,FR',
      plannedLocalTime: '18:30',
    }))
    expect(api.post.mock.calls.find(call => call[0] === '/tasks')?.[1]).not.toHaveProperty('weeklyPlanPublicId')
  })

  it('refreshes task templates and switches role', async () => {
    const presets = {
      roleCode: 'STUDENT', roleName: '学生', localDate: '2026-08-04', refreshesRemaining: 3,
      items: [{
        publicId: 'preset-1', roleCode: 'STUDENT', roleName: '学生', name: '学习任务', notes: '任务备注',
        estimatedMinutes: 25, difficulty: 2, plannedLocalTime: '18:30:00', rrule: 'FREQ=DAILY',
        dimensionCode: 'KNOWLEDGE', dimensionWeight: 10, experienceReward: 6,
      }],
    }
    api.get.mockImplementation((path: string) => path.startsWith('/task-presets') ? Promise.resolve(presets) : standardGet(path))
    api.post.mockResolvedValue({ ...presets, refreshesRemaining: 2 })

    const wrapper = mount(GoalsView)
    await flushPromises()
    await wrapper.get('.page-head button.secondary').trigger('click')
    await flushPromises()
    await wrapper.get('.refresh-button').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/task-presets/refresh?role=STUDENT')
    expect(wrapper.text()).toContain('今日还可换 2 次')

    await wrapper.findAll('.role-tabs button').find(button => button.text() === '健身用户')!.trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/task-presets?role=FITNESS_USER')

    await wrapper.get('#task-title').setValue('晚间拉伸')
    await wrapper.get('.task-builder .actions button.secondary').trigger('click')
    await wrapper.get('.page-head button.secondary').trigger('click')
    await flushPromises()
    expect((wrapper.get('#task-title').element as HTMLInputElement).value).toBe('')

    await wrapper.get('#task-title').setValue('轻量训练')
    await wrapper.get('.task-builder').trigger('submit')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/tasks', expect.objectContaining({
      roleCode: 'FITNESS_USER',
      dimensionWeights: { HEALTH: 10 },
    }))
  })

  it('takes the AI draft and pre-fills an editable goal form', async () => {
    window.sessionStorage.setItem('better-self:ai-goal-draft', JSON.stringify({
      sourceSessionPublicId: 'session-1',
      title: 'AI 生成的学习目标',
      description: '四周完成十二次复习',
      dimensionCode: 'KNOWLEDGE',
      durationDays: 28,
      weeklyFocus: '保持每周三次',
      starterTasks: [{ title: '复习一节', estimatedMinutes: 25, difficulty: 2 }],
    }))
    const wrapper = mount(GoalsView)
    await flushPromises()

    expect((wrapper.get('#goal-title').element as HTMLInputElement).value).toBe('AI 生成的学习目标')
    expect((wrapper.get('#dimension').element as HTMLSelectElement).value).toBe('dimension-1')
    expect(wrapper.get('.feedback-banner').text()).toContain('AI 目标草案已填入')
    expect(window.sessionStorage.getItem('better-self:ai-goal-draft')).toBeNull()
  })

  it('creates every AI starter task after the confirmed goal is saved', async () => {
    window.sessionStorage.setItem('better-self:ai-goal-draft', JSON.stringify({
      sourceSessionPublicId: 'session-1',
      title: '八周六级备考',
      description: '完成四套真题模考',
      dimensionCode: 'KNOWLEDGE',
      durationDays: 56,
      weeklyFocus: '词汇、听力与真题',
      starterTasks: [
        { title: '每天背诵 30 个高频词', estimatedMinutes: 30, difficulty: 2 },
        { title: '精听一篇听力真题', estimatedMinutes: 45, difficulty: 3 },
        { title: '精读一篇阅读真题', estimatedMinutes: 40, difficulty: 2 },
      ],
    }))
    const createdGoal = {
      ...goals[0],
      publicId: 'goal-ai',
      startDate: '2026-09-05',
      endDate: '2026-10-30',
    }
    let taskNumber = 0
    api.post.mockImplementation((path: string) => {
      if (path === '/goals') return Promise.resolve(createdGoal)
      if (path === '/tasks') return Promise.resolve({ publicId: `task-ai-${++taskNumber}` })
      return Promise.resolve({})
    })

    const wrapper = mount(GoalsView)
    await flushPromises()
    await wrapper.get('#goal-start').setValue('2026-09-05')
    await wrapper.get('#goal-end').setValue('2026-10-30')
    await wrapper.get('.editor').trigger('submit')
    await flushPromises()

    expect(api.post.mock.calls.map(call => call[0])).toEqual(['/goals', '/tasks', '/tasks', '/tasks'])
    const taskCalls = api.post.mock.calls.filter(call => call[0] === '/tasks')
    expect(taskCalls.map(call => (call[1] as { title: string }).title)).toEqual([
      '每天背诵 30 个高频词',
      '精听一篇听力真题',
      '精读一篇阅读真题',
    ])
    expect(taskCalls[0][1]).toMatchObject({
      goalPublicId: 'goal-ai',
      estimatedMinutes: 30,
      difficulty: 2,
      rrule: null,
      plannedLocalTime: '09:00',
      activeFrom: '2026-09-05',
      activeUntil: '2026-10-30',
      dimensionWeights: { KNOWLEDGE: 10 },
    })
    expect(wrapper.get('.feedback-banner').text()).toContain('已创建 3 个起步任务')
  })

  it('reports partial AI starter task failures without claiming full success', async () => {
    window.sessionStorage.setItem('better-self:ai-goal-draft', JSON.stringify({
      sourceSessionPublicId: 'session-1',
      title: '学习目标',
      description: '完成复习',
      dimensionCode: 'KNOWLEDGE',
      durationDays: 28,
      weeklyFocus: '保持频率',
      starterTasks: [
        { title: '复习一节', estimatedMinutes: 25, difficulty: 2 },
        { title: '记录错题', estimatedMinutes: 10, difficulty: 1 },
      ],
    }))
    let taskNumber = 0
    api.post.mockImplementation((path: string) => {
      if (path === '/goals') return Promise.resolve({ ...goals[0], publicId: 'goal-partial' })
      if (path === '/tasks' && taskNumber++ === 1) return Promise.reject({ code: 'INVALID_TASK_DATES' })
      if (path === '/tasks') return Promise.resolve({ publicId: 'task-ok' })
      return Promise.resolve({})
    })

    const wrapper = mount(GoalsView)
    await flushPromises()
    await wrapper.get('.editor').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.error').text()).toContain('目标已保存，但 1 个起步任务未保存')
    expect(wrapper.get('.feedback-banner').text()).toContain('已创建 1 个起步任务')
  })

  it('shows a specific message when the active goal limit blocks creation', async () => {
    api.post.mockRejectedValue({ status: 409, code: 'ACTIVE_GOAL_LIMIT', message: 'At most three active goals are allowed' })
    const wrapper = mount(GoalsView)
    await flushPromises()
    await wrapper.get('.page-head button.primary').trigger('click')
    await wrapper.get('#goal-title').setValue('第四个目标')
    await wrapper.get('.editor').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.error').text()).toContain('活跃目标最多 3 个')
    expect(wrapper.get('.editor')).toBeTruthy()
  })

  it('switches goals and shows only the selected goal tasks', async () => {
    api.get.mockImplementation((path: string) => {
      if (path === '/goals') return Promise.resolve([
        ...goals,
        { ...goals[0], publicId: 'goal-2', title: '第二个目标', description: '目标二描述' },
      ])
      if (path === '/dimensions') return Promise.resolve(dimensions)
      if (path === '/tasks') return Promise.resolve([
        {
          publicId: 'task-1', weeklyPlanPublicId: 'internal-plan-1', goalPublicId: 'goal-1', title: '复习一章', notes: '',
          estimatedMinutes: 25, difficulty: 2, rrule: 'FREQ=DAILY', plannedLocalTime: '19:00:00',
          activeFrom: '2026-08-04', activeUntil: '2026-08-28', active: true, roleCode: 'STUDENT',
        },
        {
          publicId: 'task-2', weeklyPlanPublicId: 'internal-plan-2', goalPublicId: 'goal-2', title: '跑步二十分钟', notes: '',
          estimatedMinutes: 20, difficulty: 1, rrule: 'FREQ=WEEKLY;BYDAY=TU,TH', plannedLocalTime: '07:30:00',
          activeFrom: '2026-08-04', activeUntil: '2026-08-28', active: true, roleCode: 'FITNESS_USER',
        },
      ])
      return Promise.resolve([])
    })

    const wrapper = mount(GoalsView)
    await flushPromises()

    expect(wrapper.get('.goal-card.current h3').text()).toBe('完成四周学习')
    expect(wrapper.get('.task-list').text()).toContain('复习一章')
    expect(wrapper.get('.task-list').text()).not.toContain('跑步二十分钟')
    expect(wrapper.get('.stack-count').text()).toBe('1 / 2')

    await wrapper.get('button[aria-label="下一个目标"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.goal-card.current h3').text()).toBe('第二个目标')
    expect(wrapper.get('.task-list').text()).toContain('跑步二十分钟')
    expect(wrapper.get('.task-list').text()).not.toContain('复习一章')
    expect(wrapper.get('.stack-count').text()).toBe('2 / 2')
  })
})
