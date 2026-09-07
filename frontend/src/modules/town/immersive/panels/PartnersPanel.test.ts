import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PartnersPanel from './PartnersPanel.vue'
import { worldBridgeKey } from '../panel.types'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../../../shared/api/client', () => ({ api }))
vi.mock('../../../../app/router', () => ({ router: { push: vi.fn() } }))
vi.mock('../../../partners/RivePet.vue', () => ({ default: { template: '<div />', methods: { react() {} } } }))
enableAutoUnmount(afterEach)

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
    expect(wrapper.text()).not.toContain('完整页面')
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

  it('keeps the previous pet on failed selection, then retries locally and shows success without a bridge', async () => {
    const other = { ...pet, publicId: 'pet-2', name: '花花', selected: false }
    api.get.mockResolvedValue({ wallet: { coinBalance: 120, lifetimeCoins: 500 }, pets: [pet, other], selectedPet: pet, shopItems: [] })
    api.post.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({ ...other, selected: true })
    const wrapper = mount(PartnersPanel)
    await flushPromises()
    await wrapper.get('[aria-label="切换到 花花"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.error').text()).toContain('伙伴切换失败')
    expect(wrapper.get('.pet-header').text()).toContain('豆豆')
    expect(wrapper.get('[aria-label="切换到 花花"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('[aria-label="切换到 花花"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('.pet-header').text()).toContain('花花')
    expect(wrapper.get('[role="status"]').text()).toContain('已切换到 花花')
    expect(wrapper.find('.error').exists()).toBe(false)
    expect(api.post).toHaveBeenCalledTimes(2)
  })

  it('locks switching and interaction during selection and never emits a success toast on failure', async () => {
    const other = { ...pet, publicId: 'pet-2', name: '花花', selected: false }
    api.get.mockResolvedValue({ wallet: { coinBalance: 120, lifetimeCoins: 500 }, pets: [pet, other], selectedPet: pet, shopItems: [] })
    let reject!: (error: unknown) => void
    api.post.mockReturnValue(new Promise((_resolve, no) => { reject = no }))
    const emit = vi.fn()
    const wrapper = mount(PartnersPanel, { global: { provide: { [worldBridgeKey]: { emit } } } })
    await flushPromises()
    await wrapper.get('[aria-label="切换到 花花"]').trigger('click')
    expect(wrapper.get('[aria-label="切换到 花花"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.action-button').attributes('disabled')).toBeDefined()
    await wrapper.get('[aria-label="切换到 花花"]').trigger('click')
    expect(api.post).toHaveBeenCalledTimes(1)
    reject(new Error('network'))
    await flushPromises()
    expect(emit).not.toHaveBeenCalled()
  })

  it('can retry a failed profile load without leaving town', async () => {
    api.get.mockRejectedValueOnce(new Error('network')).mockResolvedValue({ wallet: { coinBalance: 0, lifetimeCoins: 0 }, pets: [pet], selectedPet: pet, shopItems: [] })
    const wrapper = mount(PartnersPanel)
    await flushPromises()
    await wrapper.get('.panel-footer button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.pet-header').text()).toContain('豆豆')
    expect(wrapper.find('.error').exists()).toBe(false)
  })
})
