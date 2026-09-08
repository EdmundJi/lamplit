import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SnapSlider from './SnapSlider.vue'

function mountSlider(step = 5) {
  return mount(SnapSlider, { props: { modelValue: 30, min: 10, max: 90, step } })
}

describe('SnapSlider', () => {
  it('stays a real range input', () => {
    const input = mountSlider().get('input')
    expect(input.attributes('type')).toBe('range')
    expect(input.attributes('min')).toBe('10')
    expect(input.attributes('max')).toBe('90')
    expect((input.element as HTMLInputElement).value).toBe('30')
  })

  it('snaps a value set outside a drag', async () => {
    const wrapper = mountSlider()
    await wrapper.get('input').setValue(43)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([45])
  })

  it('follows the pointer freely while dragging', async () => {
    const wrapper = mountSlider()
    const input = wrapper.get('input')
    await input.trigger('pointerdown')
    await input.setValue(43)
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([43])
  })

  it('lands on a tick when the drag ends', async () => {
    const wrapper = mountSlider()
    const input = wrapper.get('input')
    await input.trigger('pointerdown')
    await input.setValue(43)
    await input.trigger('pointerup')
    await new Promise(resolve => setTimeout(resolve, 400))
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([45])
  })

  it('moves by whole ticks from the keyboard', async () => {
    const wrapper = mountSlider()
    await wrapper.get('input').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([35])
    await wrapper.get('input').trigger('keydown', { key: 'Home' })
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([10])
  })
})
