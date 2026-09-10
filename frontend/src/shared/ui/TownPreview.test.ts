import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TownPreview from './TownPreview.vue'

function stubFetchAndImage() {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    json: () => Promise.resolve({ frames: {} }),
  }))
  // jsdom's Image has no decode() at all; add one that resolves immediately like a real browser would.
  ;(window.HTMLImageElement.prototype as unknown as { decode: () => Promise<void> }).decode = vi.fn().mockResolvedValue(undefined)
}

/** A minimal IntersectionObserver stand-in that hands its callback out via `state.trigger`. */
function stubIntersectionObserver() {
  const state: { trigger: IntersectionObserverCallback | null; disconnected: boolean; observed: boolean } = {
    trigger: null,
    disconnected: false,
    observed: false,
  }
  class FakeObserver {
    constructor(cb: IntersectionObserverCallback) { state.trigger = cb }
    observe() { state.observed = true }
    disconnect() { state.disconnected = true }
  }
  vi.stubGlobal('IntersectionObserver', FakeObserver as unknown as typeof IntersectionObserver)
  return state
}

function intersect(state: { trigger: IntersectionObserverCallback | null }) {
  state.trigger?.([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver)
}

describe('TownPreview', () => {
  beforeEach(() => {
    stubFetchAndImage()
    // Default: run the idle callback synchronously so tests that don't care about
    // idle timing don't need to wait on a real tick.
    vi.stubGlobal('requestIdleCallback', (cb: IdleRequestCallback) => { cb({} as IdleDeadline); return 1 })
    vi.stubGlobal('cancelIdleCallback', () => {})
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('falls back straight to the idle callback when IntersectionObserver is unavailable (as in jsdom)', async () => {
    expect(typeof (globalThis as any).IntersectionObserver).toBe('undefined')
    const wrapper = mount(TownPreview)
    await flushPromises()
    expect(fetch).toHaveBeenCalledWith('/assets/town/town-atlas.json', expect.any(Object))
    wrapper.unmount()
  })

  it('does not fetch the atlas until the root element intersects the viewport', async () => {
    const observer = stubIntersectionObserver()

    const wrapper = mount(TownPreview)
    await flushPromises()
    expect(fetch).not.toHaveBeenCalled()

    intersect(observer)
    await flushPromises()

    expect(observer.disconnected).toBe(true)
    expect(fetch).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('waits for main-thread idle even after intersection, before fetching', async () => {
    const idle: { run: (() => void) | null } = { run: null }
    vi.stubGlobal('requestIdleCallback', (cb: IdleRequestCallback) => { idle.run = () => cb({} as IdleDeadline); return 1 })
    vi.stubGlobal('cancelIdleCallback', () => {})
    const observer = stubIntersectionObserver()

    const wrapper = mount(TownPreview)
    intersect(observer)
    await flushPromises()
    expect(fetch).not.toHaveBeenCalled()

    idle.run?.()
    await flushPromises()
    expect(fetch).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('disconnects a still-watching observer on unmount', async () => {
    const observer = stubIntersectionObserver()

    const wrapper = mount(TownPreview)
    expect(observer.observed).toBe(true)
    wrapper.unmount()

    expect(observer.disconnected).toBe(true)
  })

  it('cancels a pending idle callback on unmount', async () => {
    // No IntersectionObserver here (default jsdom), so mounting goes straight to
    // scheduling the idle callback, which we deliberately never fire.
    expect(typeof (globalThis as any).IntersectionObserver).toBe('undefined')
    const idle: { cancelled: boolean } = { cancelled: false }
    vi.stubGlobal('requestIdleCallback', () => 42)
    vi.stubGlobal('cancelIdleCallback', (handle: number) => { if (handle === 42) idle.cancelled = true })

    const wrapper = mount(TownPreview)
    wrapper.unmount()

    expect(idle.cancelled).toBe(true)
  })
})
