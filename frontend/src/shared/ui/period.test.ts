import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { currentPeriod, usePeriod } from './period'

describe('currentPeriod', () => {
  it.each([
    [0, 'night'],
    [5, 'night'],
    [6, 'morning'],
    [11, 'morning'],
    [12, 'afternoon'],
    [17, 'afternoon'],
    [18, 'evening'],
    [22, 'evening'],
    [23, 'night'],
  ] as const)('hour %i maps to %s, matching the backend town clock', (hour, expected) => {
    expect(currentPeriod(new Date(2026, 0, 1, hour, 0, 0))).toBe(expected)
  })
})

const Host = defineComponent({
  setup() {
    usePeriod()
    return () => h('div')
  },
})

describe('usePeriod', () => {
  afterEach(() => {
    delete document.documentElement.dataset.period
    vi.useRealTimers()
  })

  it('writes the current period to <html data-period> on mount and clears it on unmount', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 0, 1, 9, 0, 0))
    const wrapper = mount(Host)

    expect(document.documentElement.dataset.period).toBe('morning')

    wrapper.unmount()
    expect(document.documentElement.dataset.period).toBeUndefined()
  })

  it('rechecks about once a minute and updates the attribute once the period actually changes', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 0, 1, 11, 59, 30))
    const wrapper = mount(Host)
    expect(document.documentElement.dataset.period).toBe('morning')

    vi.setSystemTime(new Date(2026, 0, 1, 12, 0, 30))
    vi.advanceTimersByTime(60_000)
    expect(document.documentElement.dataset.period).toBe('afternoon')

    wrapper.unmount()
  })
})
