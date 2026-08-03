import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FriendDetailView from './FriendDetailView.vue'

const api = vi.hoisted(() => ({ get: vi.fn() }))
const chart = vi.hoisted(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
vi.mock('echarts/core', () => ({ init: vi.fn(() => chart), use: vi.fn() }))
vi.mock('echarts/charts', () => ({ RadarChart: {} }))
vi.mock('echarts/components', () => ({ LegendComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))
vi.mock('../partners/RivePet.vue', () => ({ default: { props: ['speciesCode', 'name'], template: '<div class="rive-pet-stub">{{name}}</div>' } }))

const friendProfile = {
  publicId: 'user-friend',
  displayName: '苏珊',
  memberSince: '2026-06-15',
  overallLevel: 3,
  totalExperience: 45,
  overview: { effectiveActions: 2, fulfillmentRate: 0.5, recoveryCount: 1, totalExperience: 45 },
  longestStreak: 2,
  roles: [{ roleCode: 'STUDENT', roleName: '学生', level: 1 }],
  pet: { speciesCode: 'CAT', speciesName: '猫', name: '团子', breed: '英国短毛', furColor: '蓝色', level: 2, affection: 12, nextLevelAffection: 30 },
  attributes: [
    { code: 'KNOWLEDGE', name: '智力', dimensionName: '知识', experience: 20, level: 1, radarScore: 26 },
    { code: 'HEALTH', name: '体力', dimensionName: '健康', experience: 10, level: 1, radarScore: 15 },
    { code: 'CAREER', name: '事业', dimensionName: '职业', experience: 5, level: 1, radarScore: 8 },
    { code: 'RELATIONSHIP', name: '社交', dimensionName: '关系', experience: 5, level: 1, radarScore: 8 },
    { code: 'WELLBEING', name: '心境', dimensionName: '情绪', experience: 5, level: 1, radarScore: 8 },
  ],
  todayTasks: [
    { publicId: 'task-1', title: '整理本周要点', status: 'DONE', roleName: '学生', plannedStartAt: '2026-08-03T09:00:00Z' },
    { publicId: 'task-2', title: '慢跑二十分钟', status: 'PLANNED', roleName: '健身用户', plannedStartAt: '2026-08-03T19:00:00Z' },
  ],
}

describe('FriendDetailView', () => {
  beforeEach(() => {
    api.get.mockReset().mockResolvedValue(friendProfile)
    chart.setOption.mockClear()
    chart.resize.mockClear()
    chart.dispose.mockClear()
  })

  it('renders the friend identity, metrics, today tasks, pet, badges and radar', async () => {
    const wrapper = mount(FriendDetailView, {
      props: { publicId: 'user-friend' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/friends/user-friend')
    expect(wrapper.get('.friend-identity h1').text()).toBe('苏珊')
    expect(wrapper.text()).toContain('LV.3 成长者')
    expect(wrapper.text()).toContain('本周有效行动')
    expect(wrapper.text()).toContain('1 / 2 已完成')

    const tasks = wrapper.findAll('.task-row')
    expect(tasks).toHaveLength(2)
    expect(tasks[0].text()).toContain('DONE')
    expect(tasks[0].text()).toContain('已完成')

    expect(wrapper.text()).toContain('团子正在陪着ta')
    expect(wrapper.get('.rive-pet-stub').text()).toBe('团子')
    expect(wrapper.text()).toContain('12 / 30')

    expect(wrapper.findAll('.badge-card')).toHaveLength(20)
    expect(wrapper.get('.badge-summary span').text()).toMatch(/^\d+ \/ 20$/)
    expect(wrapper.text()).toContain('第一步行动')
    expect(chart.setOption).toHaveBeenCalledOnce()
    expect(wrapper.get('.radar-chart').attributes('aria-label')).toContain('好友成长属性雷达图')
  })

  it('shows a friendly error when the friend detail is not available', async () => {
    api.get.mockRejectedValue({ status: 404, code: 'FRIENDSHIP_NOT_FOUND', message: 'not found' })
    const wrapper = mount(FriendDetailView, {
      props: { publicId: 'user-stranger' },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('只能查看好友的资料')
  })
})
