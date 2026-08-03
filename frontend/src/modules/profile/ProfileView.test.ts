import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProfileView from './ProfileView.vue'

const api = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))
vi.mock('../partners/RivePet.vue', () => ({ default: { props: ['speciesCode', 'name'], template: '<div class="rive-pet-stub">{{name}}</div>' } }))

describe('Personal profile', () => {
  it('shows identity, growth totals and the selected pet', async () => {
    api.get.mockResolvedValue({
      publicId: 'user-1', email: 'yang@example.test', displayName: '杨旭光', birthDate: '1996-06-08', age: 30,
      timezone: 'Asia/Shanghai', createdAt: '2026-07-31T00:00:00Z', overallLevel: 3, totalExperience: 235,
      effectiveActions: 18, wallet: { coinBalance: 42, lifetimeCoins: 80 }, petCount: 2,
      selectedPet: { publicId: 'pet-1', speciesCode: 'CAT', speciesName: '猫', name: '小橘', breed: '中华田园猫', furColor: '橘白', level: 2, affection: 8, nextLevelAffection: 20, selected: true },
    })
    const wrapper = mount(ProfileView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/me/profile')
    expect(wrapper.text()).toContain('杨旭光')
    expect(wrapper.text()).toContain('30 岁')
    expect(wrapper.text()).toContain('LV.3')
    expect(wrapper.text()).toContain('小橘')
    expect(wrapper.text()).toContain('42')
  })
})
