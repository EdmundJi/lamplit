import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PartnersView from './PartnersView.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  patch: vi.fn(),
  riveReact: vi.fn(),
}))

vi.mock('../../shared/api/client', () => ({
  api: { get: mocks.get, post: mocks.post, patch: mocks.patch },
}))

vi.mock('./RivePet.vue', () => ({
  default: {
    props: ['speciesCode', 'name', 'disabled'],
    emits: ['activate'],
    setup(_props: unknown, { expose }: { expose: (value: unknown) => void }) {
      expose({ react: mocks.riveReact })
    },
    template: '<button type="button" class="rive-pet-stub" :disabled="disabled" @click="$emit(\'activate\')">{{ name }}</button>',
  },
}))

const selectedPet = {
  publicId: 'pet-one',
  speciesCode: 'CAT',
  speciesName: '猫',
  name: '小橘',
  breed: '中华田园猫',
  furColor: '橘白',
  level: 2,
  affection: 8,
  nextLevelAffection: 20,
  selected: true,
}

const profile = {
  wallet: { coinBalance: 20, lifetimeCoins: 40 },
  pets: [selectedPet],
  selectedPet,
  shopItems: [],
}

function mountPartners() {
  return mount(PartnersView, { global: { plugins: [createPinia()] } })
}

describe('PartnersView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.get.mockResolvedValue(profile)
    mocks.post.mockImplementation((path: string) => {
      if (path.endsWith('/interact')) {
        return Promise.resolve({ pet: selectedPet, affectionDelta: 2, rewarded: true, interactionDate: '2026-08-05' })
      }
      if (path === '/partners/pets') {
        return Promise.resolve({ ...selectedPet, publicId: 'pet-two', speciesCode: 'DOG', speciesName: '狗', name: '阿柴', breed: '柴犬', furColor: '赤棕' })
      }
      return Promise.resolve({})
    })
  })

  it('uses a species-specific kind selector and submits the existing breed API field', async () => {
    const wrapper = mountPartners()
    await flushPromises()

    await wrapper.get('.stage-actions button:last-child').trigger('click')
    expect(wrapper.get('label[for="pet-kind"]').text()).toBe('种类')
    expect(wrapper.get('#pet-kind').element.tagName).toBe('SELECT')
    expect(wrapper.findAll('#pet-kind option')).toHaveLength(5)

    const dog = wrapper.findAll('.species-grid button').find(button => button.text().includes('狗'))
    expect(dog).toBeTruthy()
    await dog!.trigger('click')
    expect(wrapper.get<HTMLSelectElement>('#pet-kind').element.value).toBe('互动小狗')
    expect(wrapper.findAll('#pet-kind option').map(option => option.text())).toEqual(['互动小狗', '开心小狗', '散步小狗', '腊肠狗'])

    await wrapper.get('#pet-kind').setValue('开心小狗')
    expect(wrapper.get<HTMLInputElement>('#pet-color').element.value).toBe('赤棕')
    await wrapper.get('#pet-name').setValue('阿柴')
    await wrapper.get('form.pet-form').trigger('submit')
    await flushPromises()

    expect(mocks.post).toHaveBeenCalledWith('/partners/pets', {
      speciesCode: 'DOG',
      name: '阿柴',
      breed: '开心小狗',
      furColor: '赤棕',
    })
    wrapper.unmount()
  })

  it('offers four interaction choices and records the selected action', async () => {
    const wrapper = mountPartners()
    await flushPromises()

    const actions = wrapper.findAll('.interaction-option')
    expect(actions.map(button => button.text())).toEqual(['摸摸脑袋', '逗猫棒', '靠一会儿', '开心转圈'])
    await actions[1].trigger('click')
    await flushPromises()

    expect(mocks.riveReact).toHaveBeenCalledWith('play')
    expect(mocks.post).toHaveBeenCalledWith('/partners/pets/pet-one/interact')
    expect(wrapper.get('.dialogue-action').text()).toBe('逗猫棒')
    wrapper.unmount()
  })

  it('keeps a saved custom kind available while editing older data', async () => {
    mocks.get.mockResolvedValue({
      ...profile,
      pets: [{ ...selectedPet, breed: '自定义猫种' }],
      selectedPet: { ...selectedPet, breed: '自定义猫种' },
    })
    const wrapper = mountPartners()
    await flushPromises()

    await wrapper.get('.stage-actions button:first-child').trigger('click')
    expect(wrapper.get<HTMLSelectElement>('#pet-kind').element.value).toBe('自定义猫种')
    expect(wrapper.findAll('#pet-kind option').map(option => option.text()).some(text => text.includes('自定义猫种'))).toBe(true)
    wrapper.unmount()
  })
})
