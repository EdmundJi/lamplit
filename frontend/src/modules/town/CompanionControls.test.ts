import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import CompanionControls from './CompanionControls.vue'
import { useTownCompanionStore } from './town-companion.store'

describe('CompanionControls', () => {
  beforeEach(() => setActivePinia(createPinia()))
  it('offers choosing a companion when none is loaded', async () => {
    const wrapper = mount(CompanionControls, { props: { inPark: false, inHome: true } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('choose')).toHaveLength(1)
  })
  it('only exposes roaming controls in the park', () => {
    const store = useTownCompanionStore()
    store.pet = { publicId: 'pet', speciesCode: 'DOG', speciesName: '狗', name: '豆包', breed: '柴犬', furColor: '棕色', level: 1, affection: 1, nextLevelAffection: 10, selected: true }
    store.mode = 'following'
    const street = mount(CompanionControls, { props: { inPark: false, inHome: false } })
    expect(street.text()).not.toContain('放开活动')
    const park = mount(CompanionControls, { props: { inPark: true, inHome: false } })
    expect(park.text()).toContain('放开活动')
  })
  it('emits scene intents without directly changing companion state', async () => {
    const store = useTownCompanionStore()
    store.pet = { publicId: 'pet', speciesCode: 'DOG', speciesName: '狗', name: '豆包', breed: '柴犬', furColor: '棕色', level: 1, affection: 1, nextLevelAffection: 10, selected: true }
    const wrapper = mount(CompanionControls, { props: { inPark: false, inHome: false } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('walk')).toHaveLength(1)
    expect(store.mode).toBe('home')

    store.mode = 'following'
    await wrapper.vm.$nextTick()
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('stroke')).toHaveLength(1)
    expect(store.mode).toBe('following')
    await wrapper.get('button:last-child').trigger('click')
    expect(wrapper.emitted('home')).toHaveLength(1)
    expect(store.mode).toBe('following')
  })
})
