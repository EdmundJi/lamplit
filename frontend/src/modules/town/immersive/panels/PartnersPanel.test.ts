import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PartnersPanel from './PartnersPanel.vue'
import { worldBridgeKey } from '../panel.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))

const pet = { publicId: 'pet-1', speciesCode: 'CAT', speciesName: '猫', name: '豆豆', breed: '橘猫', furColor: '橘色', level: 2, affection: 30, nextLevelAffection: 50, selected: true }

describe('PartnersPanel', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.get.mockReset()
    api.post.mockReset()
  })

  it('mounts without a bridge and shows the selected pet', async () => {
    api.get.mockResolvedValue({ wallet: { coinBalance: 120, lifetimeCoins: 500 }, pets: [pet], selectedPet: pet, shopItems: [] })
    const wrapper = mount(PartnersPanel)
    await flushPromises()
    expect(wrapper.text()).toContain('豆豆')
    expect(wrapper.text()).toContain('120 金币')
  })

  it('interacts with the pet and celebrates through the bridge when rewarded', async () => {
    api.get.mockResolvedValue({ wallet: { coinBalance: 120, lifetimeCoins: 500 }, pets: [pet], selectedPet: pet, shopItems: [] })
    api.post.mockResolvedValueOnce({ pet, affectionDelta: 5, rewarded: true, interactionDate: '2026-09-05' })
    const emit = vi.fn()
    const wrapper = mount(PartnersPanel, { global: { provide: { [worldBridgeKey]: { emit, runMode: false, setRunMode: vi.fn() } } } })
    await flushPromises()

    await wrapper.get('.action-button').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/partners/pets/pet-1/interact')
    expect(emit).toHaveBeenCalledWith({ type: 'celebrate', publicId: 'pet-1' })
    expect(wrapper.text()).toContain('+5')
  })

  it('shows a readable message on failure', async () => {
    api.get.mockRejectedValue(new Error('network'))
    const wrapper = mount(PartnersPanel)
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('暂时无法加载')
  })
})
