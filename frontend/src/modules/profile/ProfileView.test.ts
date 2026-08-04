import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProfileView from './ProfileView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), patch: vi.fn(), delete: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
vi.mock('../partners/RivePet.vue', () => ({ default: { props: ['speciesCode', 'name'], template: '<div class="rive-pet-stub">{{name}}</div>' } }))

const profile = {
  publicId: 'user-1', email: 'yang@example.test', displayName: '杨旭光', birthDate: '1996-06-08', age: 30,
  timezone: 'Asia/Shanghai', createdAt: '2026-07-31T00:00:00Z', overallLevel: 3, totalExperience: 235,
  effectiveActions: 18, wallet: { coinBalance: 42, lifetimeCoins: 80 }, petCount: 2, soloGrowth: false, equippedTitle: null,
  selectedPet: { publicId: 'pet-1', speciesCode: 'CAT', speciesName: '猫', name: '小橘', breed: '中华田园猫', furColor: '橘白', level: 2, affection: 8, nextLevelAffection: 20, selected: true },
}
const titles = [
  { code: 'NEWCOMER_PATH', name: '成长之路', description: '完成起步设置', graphicType: 'LUCIDE', graphicKey: 'Route', frameStyle: 'emerald', held: true, equipped: false, acquiredAt: '2026-08-04T08:00:00Z' },
  { code: 'STEADY_GROWER', name: '稳步成长者', description: '稳定积累行动', graphicType: 'LUCIDE', graphicKey: 'Sprout', frameStyle: 'gold', held: false, equipped: false, acquiredAt: null },
]

function mountProfile() {
  return mount(ProfileView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
}

describe('Personal profile', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.patch.mockReset()
    api.delete.mockReset()
    api.get.mockImplementation((path: string) => path === '/me/profile' ? Promise.resolve({ ...profile }) : Promise.resolve(titles))
  })

  it('shows identity, growth totals, selected pet and title inventory', async () => {
    const wrapper = mountProfile()
    await flushPromises()

    expect(api.get).toHaveBeenCalledWith('/me/profile')
    expect(api.get).toHaveBeenCalledWith('/titles')
    expect(wrapper.text()).toContain('杨旭光')
    expect(wrapper.text()).toContain('30 岁')
    expect(wrapper.text()).toContain('LV.3')
    expect(wrapper.text()).toContain('小橘')
    expect(wrapper.text()).toContain('成长之路')
    expect(wrapper.text()).toContain('未获得')
  })

  it('equips and removes an owned title', async () => {
    api.patch.mockResolvedValue([{ ...titles[0], equipped: true }, titles[1]])
    api.delete.mockResolvedValue(titles)
    const wrapper = mountProfile()
    await flushPromises()

    await wrapper.get('[aria-label="佩戴成长之路"]').trigger('click')
    await flushPromises()
    expect(api.patch).toHaveBeenCalledWith('/titles/equipped', { code: 'NEWCOMER_PATH' })
    expect(wrapper.text()).toContain('已佩戴「成长之路」')
    expect(wrapper.get('.avatar-shell').attributes('data-frame')).toBe('emerald')

    await wrapper.get('[aria-label="卸下称号"]').trigger('click')
    await flushPromises()
    expect(api.delete).toHaveBeenCalledWith('/titles/equipped')
    expect(wrapper.text()).toContain('已卸下称号')
    expect(wrapper.get('.avatar-shell').attributes('data-frame')).toBe('default')
  })

  it('enables solo growth from the profile privacy switch', async () => {
    api.patch.mockResolvedValue({ soloGrowth: true })
    const wrapper = mountProfile()
    await flushPromises()

    const privacySwitch = wrapper.get('[role="switch"]')
    expect(privacySwitch.attributes('aria-checked')).toBe('false')
    await privacySwitch.trigger('click')
    await flushPromises()

    expect(api.patch).toHaveBeenCalledWith('/me/privacy', { soloGrowth: true })
    expect(privacySwitch.attributes('aria-checked')).toBe('true')
    expect(wrapper.text()).toContain('已进入独自升级模式')
    expect(wrapper.text()).toContain('已从好友搜索中隐藏')
  })
})
