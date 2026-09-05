import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import InsightsPanel from './InsightsPanel.vue'
import { worldBridgeKey } from '../panel.types'

const api = vi.hoisted(() => ({ get: vi.fn(), patch: vi.fn(), post: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))

const review = {
  publicId: 'review-1',
  planPublicId: 'plan-1',
  facts: { plannedActions: 4, effectiveActions: 3, fulfillmentRate: 0.75 },
  userReflection: '',
  proposedAdjustments: {},
  confirmedAdjustments: {},
  confirmedAt: null,
}

function mockPageData() {
  api.get.mockImplementation((path: string) => {
    if (path === '/insights/overview') return Promise.resolve({ effectiveActions: 12, fulfillmentRate: 0.75, recoveryCount: 2, totalExperience: 500, statusCheckCount: 0, statusAdvices: {} })
    if (path === '/insights/trends') return Promise.resolve([])
    if (path === '/progress/roles') return Promise.resolve([{ roleCode: 'STUDENT', roleName: '学生', level: 3, experience: 1, maxExperience: 2, totalExperience: 1, nextLevelExperience: 2, experienceToNextLevel: 1, levelProgressPercent: 40 }])
    if (path === '/achievements') return Promise.resolve([{ code: 'A1', name: '第一步', body: '', triggerText: '完成 1 次', category: 'ACTION', iconKey: 'Footprints', tone: 'green', earned: true, earnedAt: '2026-08-01T00:00:00Z' }, { code: 'A2', name: '第二步', body: '', triggerText: '完成 5 次', category: 'ACTION', iconKey: 'Footprints', tone: 'green', earned: false, earnedAt: null }])
    if (path.startsWith('/plans/weekly')) return Promise.resolve([{ publicId: 'plan-1', goalPublicId: 'goal-1', weekStartDate: '2026-08-03', timezone: 'Asia/Shanghai', status: 'ACTIVE' }])
    if (path === '/reviews/weekly/plan-1') return Promise.resolve(review)
    return Promise.resolve([])
  })
}

describe('InsightsPanel', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.patch.mockReset()
    api.post.mockReset()
    mockPageData()
  })

  it('mounts without a bridge and renders real overview data', async () => {
    const wrapper = mount(InsightsPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('75%')
    expect(wrapper.text()).toContain('1 / 2')
    expect(wrapper.text()).toContain('学生')
  })

  it('shows a readable message on failure', async () => {
    api.get.mockRejectedValue(new Error('network'))
    const wrapper = mount(InsightsPanel)
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('暂时无法加载')
  })

  it('fills in and saves a weekly review draft from the review tab', async () => {
    api.patch.mockImplementation((_path: string, body: any) => Promise.resolve({ ...review, userReflection: body.userReflection, proposedAdjustments: body.proposedAdjustments }))
    const wrapper = mount(InsightsPanel)
    await flushPromises()

    await wrapper.findAll('.tab-row button').find(button => button.text() === '周复盘')!.trigger('click')
    await flushPromises()

    const textarea = wrapper.get('.review-form textarea')
    await textarea.setValue('这周找到了节奏')
    await wrapper.get('.review-form').trigger('submit')
    await flushPromises()

    expect(api.patch).toHaveBeenCalledWith('/reviews/weekly/plan-1', expect.objectContaining({ userReflection: '这周找到了节奏' }))
    expect(wrapper.text()).toContain('复盘草稿已保存')
  })

  it('confirms the weekly review and celebrates the linked goal through the bridge', async () => {
    api.patch.mockResolvedValue(review)
    api.post.mockResolvedValue({ ...review, confirmedAt: '2026-08-04T09:00:00Z' })
    const emit = vi.fn()
    const wrapper = mount(InsightsPanel, { global: { provide: { [worldBridgeKey]: { emit, runMode: false, setRunMode: vi.fn() } } } })
    await flushPromises()

    await wrapper.findAll('.tab-row button').find(button => button.text() === '周复盘')!.trigger('click')
    await flushPromises()
    await wrapper.get('.review-actions .primary').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/reviews/weekly/plan-1/confirm')
    expect(emit).toHaveBeenCalledWith({ type: 'toast', text: '本周复盘已确认' })
    expect(emit).toHaveBeenCalledWith({ type: 'celebrate', publicId: 'goal-1' })
    expect(wrapper.text()).toContain('已于')
  })
})
