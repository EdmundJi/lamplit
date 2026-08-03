import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import GoalsView from './GoalsView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

describe('Goal encouragement', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    api.get.mockReset().mockImplementation((path: string) => {
      if (path === '/goals') return Promise.resolve([{ publicId: 'goal-1', dimensionPublicId: 'dimension-1', title: '完成四周学习', description: '完成四章', startDate: '2026-08-01', endDate: '2026-08-28', status: 'ACTIVE' }])
      if (path === '/dimensions') return Promise.resolve([{ publicId: 'dimension-1', code: 'KNOWLEDGE', name: '知识' }])
      return Promise.resolve([])
    })
    api.post.mockReset().mockResolvedValue({ status: 'COMPLETED' })
  })

  it('offers support while setting a goal and celebrates completion', async () => {
    const wrapper = mount(GoalsView)
    await flushPromises()
    await wrapper.get('button.primary').trigger('click')
    expect(wrapper.get('.support-line').text().length).toBeGreaterThan(12)

    await wrapper.get('button[aria-label="完成目标"]').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/goals/goal-1/complete')
    expect(wrapper.get('.feedback-banner[data-tone="celebrate"]').text().length).toBeGreaterThan(12)
  })

  it('loads four career presets, fills the task form and refreshes the draw', async () => {
    const presets = {
      roleCode: 'STUDENT', roleName: '学生', localDate: '2026-08-01', refreshesRemaining: 3,
      items: Array.from({ length: 4 }, (_, index) => ({
        publicId: `preset-${index + 1}`, roleCode: 'STUDENT', roleName: '学生',
        name: `学习任务 ${index + 1}`, notes: `任务备注 ${index + 1}`, estimatedMinutes: 20 + index * 5,
        difficulty: 2, plannedLocalTime: '18:30:00', rrule: 'FREQ=WEEKLY;BYDAY=MO,WE,FR',
        dimensionCode: 'KNOWLEDGE', dimensionWeight: 10, experienceReward: 6,
      })),
    }
    api.get.mockImplementation((path: string) => {
      if (path === '/goals') return Promise.resolve([{ publicId: 'goal-1', dimensionPublicId: 'dimension-1', title: '完成四周学习', description: '完成四章', startDate: '2026-08-01', endDate: '2026-08-28', status: 'ACTIVE' }])
      if (path === '/dimensions') return Promise.resolve([{ publicId: 'dimension-1', code: 'KNOWLEDGE', name: '知识' }])
      if (path === '/plans/weekly') return Promise.resolve([{ publicId: 'plan-1', goalPublicId: 'goal-1', weekStartDate: '2026-07-27', timezone: 'Asia/Shanghai', status: 'DRAFT' }])
      if (path.startsWith('/task-presets')) return Promise.resolve(presets)
      return Promise.resolve([])
    })
    api.post.mockImplementation((path: string) => path.startsWith('/task-presets/refresh')
      ? Promise.resolve({ ...presets, refreshesRemaining: 2 })
      : Promise.resolve({}))

    const wrapper = mount(GoalsView)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text().trim()==='任务')!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.preset-item')).toHaveLength(4)
    expect(wrapper.text()).toContain('今日还可换 3 次')

    await wrapper.findAll('.preset-item')[0].trigger('click')
    expect((wrapper.get('#task-title').element as HTMLInputElement).value).toBe('学习任务 1')
    expect((wrapper.get('#task-minutes').element as HTMLInputElement).value).toBe('20')
    expect(wrapper.get('.preset-item.selected').attributes('aria-pressed')).toBe('true')

    await wrapper.get('.refresh-button').trigger('click')
    await flushPromises()
    expect(api.post).toHaveBeenCalledWith('/task-presets/refresh?role=STUDENT')
    expect(wrapper.text()).toContain('今日还可换 2 次')

    await wrapper.findAll('.role-tabs button').find(button=>button.text()==='健身用户')!.trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/task-presets?role=FITNESS_USER')
  })

  it('takes the AI draft and pre-fills an editable goal form', async () => {
    window.sessionStorage.setItem('better-self:ai-goal-draft', JSON.stringify({
      sourceSessionPublicId: 'session-1', title: 'AI 生成的学习目标', description: '四周完成十二次复习',
      dimensionCode: 'KNOWLEDGE', durationDays: 28, weeklyFocus: '保持每周三次',
      starterTasks: [{ title: '复习一节', estimatedMinutes: 25, difficulty: 2 }],
    }))
    const wrapper = mount(GoalsView)
    await flushPromises()
    expect((wrapper.get('#goal-title').element as HTMLInputElement).value).toBe('AI 生成的学习目标')
    expect((wrapper.get('#dimension').element as HTMLSelectElement).value).toBe('dimension-1')
    expect(wrapper.get('.feedback-banner').text()).toContain('AI 目标草案已填入')
    expect(window.sessionStorage.getItem('better-self:ai-goal-draft')).toBeNull()
  })

  it('shows a specific message when the active goal limit blocks creation', async () => {
    api.post.mockRejectedValue({ status: 409, code: 'ACTIVE_GOAL_LIMIT', message: 'At most three active goals are allowed' })
    const wrapper = mount(GoalsView)
    await flushPromises()
    await wrapper.get('button.primary').trigger('click')
    await wrapper.get('#goal-title').setValue('第四个目标')
    await wrapper.get('.band.stack').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('活跃目标最多 3 个')
    expect(wrapper.get('.band.stack')).toBeTruthy()
  })

  it('stacks multiple goals into a carousel switched with arrows', async () => {
    api.get.mockImplementation((path: string) => {
      if (path === '/goals') return Promise.resolve([
        { publicId: 'goal-1', dimensionPublicId: 'dimension-1', title: '第一个目标', description: '目标一描述', startDate: '2026-08-01', endDate: '2026-08-28', status: 'ACTIVE' },
        { publicId: 'goal-2', dimensionPublicId: 'dimension-1', title: '第二个目标', description: '目标二描述', startDate: '2026-08-02', endDate: '2026-08-29', status: 'ACTIVE' },
      ])
      if (path === '/dimensions') return Promise.resolve([{ publicId: 'dimension-1', code: 'KNOWLEDGE', name: '知识' }])
      if (path === '/plans/weekly') return Promise.resolve([
        { publicId: 'plan-1', goalPublicId: 'goal-1', weekStartDate: '2026-07-27', timezone: 'Asia/Shanghai', status: 'DRAFT' },
        { publicId: 'plan-2', goalPublicId: 'goal-2', weekStartDate: '2026-08-03', timezone: 'Asia/Shanghai', status: 'DRAFT' },
      ])
      return Promise.resolve([])
    })
    const wrapper = mount(GoalsView)
    await flushPromises()

    const goalCards = wrapper.findAll('.goal-stack article')
    expect(goalCards).toHaveLength(2)
    expect(wrapper.get('.goal-stack article.current h3').text()).toBe('第一个目标')
    expect(wrapper.get('.stack-count').text()).toBe('1 / 2')

    const prevGoal = wrapper.get('button[aria-label="上一个目标"]')
    const nextGoal = wrapper.get('button[aria-label="下一个目标"]')
    expect((prevGoal.attributes('disabled'))).toBeDefined()

    await nextGoal.trigger('click')
    await flushPromises()
    expect(wrapper.get('.goal-stack article.current h3').text()).toBe('第二个目标')
    expect(wrapper.get('.stack-count').text()).toBe('2 / 2')
    expect((nextGoal.attributes('disabled'))).toBeDefined()

    const planCards = wrapper.findAll('.plan-stack article')
    expect(planCards).toHaveLength(2)
    expect(wrapper.get('.plan-stack article.current header strong').text()).toBe('第一个目标')
    await wrapper.get('button[aria-label="下一个周计划"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.plan-stack article.current header strong').text()).toBe('第二个目标')
  })
})
