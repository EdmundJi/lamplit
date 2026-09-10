import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SegmentedControl from './SegmentedControl.vue'

const options = [
  { value: 'a', label: 'A' },
  { value: 'b', label: 'B' },
  { value: 'c', label: 'C' },
]

// jsdom never lays anything out, so every element's real getBoundingClientRect
// reports zeros — stub the track and each option to three even, 100px-wide
// columns so the thumb math (and the drag math, which reads the same rects)
// has real geometry to work with.
async function mountControl(modelValue = 'a') {
  const wrapper = mount(SegmentedControl, { props: { modelValue, options, label: '选项' } })
  const track = wrapper.get('[role="radiogroup"]').element as HTMLElement
  track.getBoundingClientRect = () => ({ left: 0, top: 0, width: 300, height: 40, right: 300, bottom: 40, x: 0, y: 0, toJSON() {} }) as DOMRect
  wrapper.findAll('[role="radio"]').forEach((radio, index) => {
    ;(radio.element as HTMLElement).getBoundingClientRect = () => ({
      left: index * 100, top: 0, width: 100, height: 34, right: index * 100 + 100, bottom: 34, x: index * 100, y: 0, toJSON() {},
    }) as DOMRect
  })
  // Re-measure now that the mocked rects are in place (mount already ran the
  // component's own nextTick measure against the zeroed defaults).
  window.dispatchEvent(new Event('resize'))
  await wrapper.vm.$nextTick()
  return wrapper
}

afterEach(() => {
  delete document.documentElement.dataset.motion
})

describe('SegmentedControl', () => {
  it('stays a real radiogroup with one radio checked', async () => {
    const wrapper = await mountControl('b')
    const radios = wrapper.findAll('[role="radio"]')
    expect(wrapper.get('[role="radiogroup"]').attributes('aria-label')).toBe('选项')
    expect(radios.map(r => r.attributes('aria-checked'))).toEqual(['false', 'true', 'false'])
  })

  it('switches on click and emits the new value', async () => {
    const wrapper = await mountControl('a')
    const radios = wrapper.findAll('[role="radio"]')
    await radios[2].trigger('click')
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('c')
    expect(radios[2].attributes('aria-checked')).toBe('true')
    expect(radios[0].attributes('aria-checked')).toBe('false')
  })

  it('moves with arrow keys and jumps with Home/End', async () => {
    const wrapper = await mountControl('b')
    const radio = wrapper.findAll('[role="radio"]')[0]
    await radio.trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('c')
    await radio.trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('b')
    await radio.trigger('keydown', { key: 'End' })
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('c')
    await radio.trigger('keydown', { key: 'Home' })
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('a')
  })

  it('drags past the halfway point and releases onto the nearest option', async () => {
    const wrapper = await mountControl('a')
    const handle = wrapper.findAll('[role="radio"]')[0].element as HTMLElement
    handle.dispatchEvent(new MouseEvent('pointerdown', { clientX: 0, bubbles: true }))
    handle.dispatchEvent(new MouseEvent('pointermove', { clientX: 260, bubbles: true }))
    handle.dispatchEvent(new MouseEvent('pointerup', { clientX: 260, bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('c')
  })

  it('does not switch when the drag never crosses the threshold', async () => {
    const wrapper = await mountControl('a')
    const handle = wrapper.findAll('[role="radio"]')[0].element as HTMLElement
    handle.dispatchEvent(new MouseEvent('pointerdown', { clientX: 0, bubbles: true }))
    handle.dispatchEvent(new MouseEvent('pointermove', { clientX: 2, bubbles: true }))
    handle.dispatchEvent(new MouseEvent('pointerup', { clientX: 2, bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('animates the thumb over frames when motion is allowed, and lands instantly when it is off', async () => {
    const raf = vi.spyOn(window, 'requestAnimationFrame')
    const onWrapper = await mountControl('a')
    raf.mockClear()
    await onWrapper.findAll('[role="radio"]')[2].trigger('click')
    expect(raf).toHaveBeenCalled()
    raf.mockClear()

    document.documentElement.dataset.motion = 'off'
    const offWrapper = await mountControl('a')
    raf.mockClear()
    await offWrapper.findAll('[role="radio"]')[2].trigger('click')
    expect(raf).not.toHaveBeenCalled()
    expect(offWrapper.emitted('update:modelValue')?.at(-1)?.[0]).toBe('c')
    raf.mockRestore()
  })
})
