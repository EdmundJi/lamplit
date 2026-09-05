import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import InsightsView from './InsightsView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), patch: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const review = {
  publicId: 'review-1',
  planPublicId: 'plan-1',
  facts: { plannedActions: 4, effectiveActions: 3, fulfillmentRate: 0.75 },
  userReflection: '比上周更从容',
  proposedAdjustments: { steadyAction: '晚饭后阅读', shrinkAction: '把训练缩短', nextAction: '继续阅读' },
  confirmedAdjustments: {},
  confirmedAt: null,
}

function mockPageData() {
  api.get.mockImplementation((path: string) => {
    if (path === '/insights/overview') return Promise.resolve({ effectiveActions: 3, fulfillmentRate: 0.75, recoveryCount: 1, totalExperience: 22, statusCheckCount: 0, statusAdvices: {} })
    if (path === '/insights/trends') return Promise.resolve([])
    if (path === '/progress/roles') return Promise.resolve([
      { roleCode: 'STUDENT', roleName: '学生', level: 3, experience: 10, maxExperience: 25, experienceToNextLevel: 15, levelProgressPercent: 40 },
      { roleCode: 'FITNESS_USER', roleName: '健身用户', level: 1, experience: 0, maxExperience: 10, experienceToNextLevel: 10, levelProgressPercent: 0 },
      { roleCode: 'WORKER', roleName: '打工人', level: 2, experience: 5, maxExperience: 15, experienceToNextLevel: 10, levelProgressPercent: 33 },
      { roleCode: 'EMOTIONAL_SUPPORT_USER', roleName: '情绪支持用户', level: 10, experience: 999, maxExperience: 999, experienceToNextLevel: 0, levelProgressPercent: 100 },
    ])
    if (path === '/achievements') return Promise.resolve([
      { code: 'FIRST_STEP', name: '第一步', body: '完成第一次有效行动', triggerText: '累计完成 1 次有效行动', category: 'ACTION', iconKey: 'Footprints', tone: 'green', earned: true, earnedAt: '2026-08-03T08:00:00Z' },
      { code: 'WEEK_BUILDER', name: '一周筑基', body: '一周内保持行动', triggerText: '本周完成 5 次有效行动', category: 'ACTION', iconKey: 'CalendarCheck', tone: 'blue', earned: false, earnedAt: null },
    ])
    if (path === '/plans/weekly?weekStart=2026-08-03') return Promise.resolve([
      { publicId: 'plan-1', goalPublicId: 'goal-1', weekStartDate: '2026-08-03', timezone: 'Asia/Shanghai', status: 'ACTIVE' },
    ])
    if (path === '/reviews/weekly/plan-1') return Promise.resolve(review)
    return Promise.reject(new Error(`Unexpected GET ${path}`))
  })
}

describe('Insights', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-04T08:00:00+08:00'))
    vi.clearAllMocks()
    mockPageData()
  })

  afterEach(() => vi.useRealTimers())

  it('uses server achievements and shows all four independent role levels', async () => {
    const wrapper = mount(InsightsView)
    await flushPromises()

    expect(api.get).toHaveBeenCalledWith('/achievements')
    // Badges start collapsed to the ones actually earned; the rest sit behind the toggle.
    expect(wrapper.findAll('.badge-card')).toHaveLength(1)
    await wrapper.get('.badge-toggle').trigger('click')
    expect(wrapper.findAll('.badge-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('第一步')
    expect(wrapper.text()).toContain('累计完成 1 次有效行动')
    expect(wrapper.text()).toContain('1 / 2')
    expect(wrapper.findAll('.role-row')).toHaveLength(4)
    expect(wrapper.text()).toContain('10 / 25 经验')
    expect(wrapper.text()).toContain('999 / 999 经验')
    expect(wrapper.text()).toContain('已满级')
  })

  it('loads, saves and explicitly confirms the current weekly review', async () => {
    api.patch.mockImplementation((_path: string, body: any) => Promise.resolve({
      ...review,
      userReflection: body.userReflection,
      proposedAdjustments: body.proposedAdjustments,
    }))
    api.post.mockResolvedValue({
      ...review,
      userReflection: '本周找到了稳定节奏',
      confirmedAdjustments: { steadyAction: '早起阅读' },
      confirmedAt: '2026-08-04T09:30:00Z',
    })
    const wrapper = mount(InsightsView)
    await flushPromises()

    expect(api.get).toHaveBeenCalledWith('/reviews/weekly/plan-1')
    expect((wrapper.get('#reflection').element as HTMLTextAreaElement).value).toBe('比上周更从容')
    await wrapper.get('#reflection').setValue('本周找到了稳定节奏')
    await wrapper.get('#steady').setValue('早起阅读')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.patch).toHaveBeenCalledWith('/reviews/weekly/plan-1', {
      userReflection: '本周找到了稳定节奏',
      proposedAdjustments: {
        steadyAction: '早起阅读',
        shrinkAction: '把训练缩短',
        nextAction: '继续阅读',
      },
    })
    expect(wrapper.text()).toContain('复盘草稿已保存')

    await wrapper.get('.review-actions .primary').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/reviews/weekly/plan-1/confirm')
    expect(wrapper.text()).toContain('本周复盘已确认')
    expect(wrapper.find('.review-actions').exists()).toBe(false)
  })
})
