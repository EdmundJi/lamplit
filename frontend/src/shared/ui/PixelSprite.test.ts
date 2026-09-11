import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Component } from 'vue'

// The real atlas (town-atlas.json/png) is generated locally from licensed art
// and is git-ignored — tests stand in a minimal fixture instead.
const FIXTURE_ATLAS = {
  frames: {
    mailbox_1: { frame: { x: 290, y: 4266, w: 32, h: 64 } },
    lamp_1: { frame: { x: 1710, y: 2930, w: 64, h: 128 } },
  },
  meta: { size: { w: 2048, h: 4440 } },
}

function stubFetch() {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: () => Promise.resolve(FIXTURE_ATLAS),
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

// PixelSprite caches its atlas fetch at module scope so every instance on a
// page shares one request; reset the module registry between tests so that
// cache doesn't leak from one test into the next.
async function freshPixelSprite(): Promise<Component> {
  vi.resetModules()
  const mod = await import('./PixelSprite.vue')
  return mod.default
}

describe('PixelSprite', () => {
  beforeEach(() => {
    stubFetch()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('positions a known town-atlas frame at the default 2x scale', async () => {
    const PixelSprite = await freshPixelSprite()
    const wrapper = mount(PixelSprite, { props: { name: 'mailbox_1' } })
    await flushPromises()

    const el = wrapper.get('.pixel-sprite')
    expect(el.attributes('style')).toContain('width: 64px')
    expect(el.attributes('style')).toContain('height: 128px')
    expect(el.attributes('style')).toContain('background-position: -580px -8532px')
    expect(el.attributes('style')).toContain('background-image: url("/assets/town/town-atlas.png")')
  })

  it('scales the frame and its offset together', async () => {
    const PixelSprite = await freshPixelSprite()
    const wrapper = mount(PixelSprite, { props: { name: 'lamp_1', scale: 1 } })
    await flushPromises()

    const el = wrapper.get('.pixel-sprite')
    expect(el.attributes('style')).toContain('width: 64px')
    expect(el.attributes('style')).toContain('height: 128px')
    expect(el.attributes('style')).toContain('background-position: -1710px -2930px')
  })

  it('renders an empty placeholder for an unknown frame name, without throwing or logging', async () => {
    const errorSpy = vi.spyOn(console, 'error')
    const warnSpy = vi.spyOn(console, 'warn')
    const PixelSprite = await freshPixelSprite()

    const wrapper = mount(PixelSprite, { props: { name: 'nonexistent_frame' } })
    await flushPromises()

    expect(wrapper.get('.pixel-sprite').classes()).toContain('pixel-sprite--empty')
    expect(errorSpy).not.toHaveBeenCalled()
    expect(warnSpy).not.toHaveBeenCalled()
  })

  it('is aria-hidden by default and becomes an accessible image when alt is given', async () => {
    const PixelSprite = await freshPixelSprite()
    const hidden = mount(PixelSprite, { props: { name: 'mailbox_1' } })
    await flushPromises()
    expect(hidden.get('.pixel-sprite').attributes('aria-hidden')).toBe('true')
    expect(hidden.get('.pixel-sprite').attributes('role')).toBeUndefined()

    const labelled = mount(PixelSprite, { props: { name: 'mailbox_1', alt: '一个信箱' } })
    await flushPromises()
    expect(labelled.get('.pixel-sprite').attributes('aria-hidden')).toBeUndefined()
    expect(labelled.get('.pixel-sprite').attributes('role')).toBe('img')
    expect(labelled.get('.pixel-sprite').attributes('aria-label')).toBe('一个信箱')
  })

  it('fetches the atlas once and reuses it across mounts', async () => {
    const fetchMock = stubFetch()
    const PixelSprite = await freshPixelSprite()
    const first = mount(PixelSprite, { props: { name: 'mailbox_1' } })
    await flushPromises()
    const second = mount(PixelSprite, { props: { name: 'lamp_1' } })
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(second.get('.pixel-sprite').attributes('style')).toContain('width: 128px')
    first.unmount()
    second.unmount()
  })

  it('resolves emotes frames from a fixed grid without any network call', async () => {
    const fetchMock = stubFetch()
    const PixelSprite = await freshPixelSprite()
    const wrapper = mount(PixelSprite, { props: { name: 'emote-11', source: 'emotes' } })
    await flushPromises()

    // index 11 on a 10-wide, 32px grid -> col 1, row 1 -> (32, 32)
    const el = wrapper.get('.pixel-sprite')
    expect(el.attributes('style')).toContain('background-position: -64px -64px')
    expect(el.attributes('style')).toContain('background-image: url("/assets/town/emotes.png")')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
