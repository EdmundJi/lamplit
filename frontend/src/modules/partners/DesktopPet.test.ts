import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DesktopPet from './DesktopPet.vue'
import { api } from '../../shared/api/client'

vi.mock('../../shared/api/client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
}))

vi.mock('./RivePet.vue', () => ({
  default: { template: '<button type="button" aria-label="测试宠物" />', methods: { react() {} } },
}))

const profile = {
  wallet: { coinBalance: 20, lifetimeCoins: 40 },
  pets: [{
    publicId: 'pet-one', speciesCode: 'CAT', speciesName: '猫', name: '小橘', breed: '田园猫', furColor: '橘白',
    level: 2, affection: 8, nextLevelAffection: 20, selected: true,
  }],
  selectedPet: {
    publicId: 'pet-one', speciesCode: 'CAT', speciesName: '猫', name: '小橘', breed: '田园猫', furColor: '橘白',
    level: 2, affection: 8, nextLevelAffection: 20, selected: true,
  },
  shopItems: [],
}

describe('DesktopPet', () => {
  const setCompact = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.get).mockResolvedValue(profile)
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: { getItem: vi.fn(() => null), setItem: vi.fn(), removeItem: vi.fn() },
    })
    Object.defineProperty(window, 'betterSelfDesktop', {
      configurable: true,
      value: { isDesktopApp: true, setCompact, showPet: vi.fn(), showSetup: vi.fn(), close: vi.fn() },
    })
  })

  it('collapses to a ball and wakes again', async () => {
    const wrapper = mount(DesktopPet, { props: { standalone: true }, global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.get('button[aria-label="最小化桌宠"]').trigger('click')
    expect(wrapper.get('.desktop-pet').classes()).toContain('minimized')
    expect(setCompact).toHaveBeenCalledWith(true)

    await wrapper.get('button[aria-label="唤醒小橘"]').trigger('click')
    expect(wrapper.get('.desktop-pet').classes()).not.toContain('minimized')
    expect(setCompact).toHaveBeenLastCalledWith(false)
  })
})
