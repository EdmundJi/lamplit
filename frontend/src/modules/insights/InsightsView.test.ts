import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import InsightsView from './InsightsView.vue'

const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

describe('Career progression', () => {
  it('shows all four independent role levels and the max-level state', async () => {
    api.get.mockImplementation((path: string) => {
      if (path === '/insights/overview') return Promise.resolve({ effectiveActions: 3, fulfillmentRate: .75, recoveryCount: 1, totalExperience: 22 })
      if (path === '/progress/roles') return Promise.resolve([
        { roleCode: 'STUDENT', roleName: '学生', level: 3, experience: 10, maxExperience: 25, experienceToNextLevel: 15, levelProgressPercent: 40 },
        { roleCode: 'FITNESS_USER', roleName: '健身用户', level: 1, experience: 0, maxExperience: 10, experienceToNextLevel: 10, levelProgressPercent: 0 },
        { roleCode: 'WORKER', roleName: '打工人', level: 2, experience: 5, maxExperience: 15, experienceToNextLevel: 10, levelProgressPercent: 33 },
        { roleCode: 'EMOTIONAL_SUPPORT_USER', roleName: '情绪支持用户', level: 10, experience: 999, maxExperience: 999, experienceToNextLevel: 0, levelProgressPercent: 100 },
      ])
      return Promise.resolve([])
    })
    const wrapper = mount(InsightsView)
    await flushPromises()
    expect(wrapper.findAll('.role-row')).toHaveLength(4)
    expect(wrapper.text()).toContain('学生')
    expect(wrapper.text()).toContain('情绪支持用户')
    expect(wrapper.text()).toContain('10 / 25 经验')
    expect(wrapper.text()).toContain('999 / 999 经验')
    expect(wrapper.text()).toContain('已满级')
  })
})
