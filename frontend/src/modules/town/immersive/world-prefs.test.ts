import { beforeEach, describe, expect, it } from 'vitest'
import { loadWorldPrefs, saveWorldPrefs } from './world-prefs'

describe('world-prefs', () => {
  const saved = new Map<string, string>()

  beforeEach(() => {
    saved.clear()
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: {
        getItem: (key: string) => saved.get(key) ?? null,
        setItem: (key: string, value: string) => saved.set(key, value),
        removeItem: (key: string) => saved.delete(key),
      },
    })
  })

  it('returns sensible defaults when nothing is stored yet', () => {
    expect(loadWorldPrefs()).toEqual({ runMode: false, openPanels: [], positions: {} })
  })

  it('round-trips run mode, open panels and window positions', () => {
    saveWorldPrefs({ runMode: true, openPanels: ['today', 'ai'], positions: { today: { x: 40, y: 60 }, ai: { x: 10, y: 20, minimized: true } } })
    expect(loadWorldPrefs()).toEqual({
      runMode: true,
      openPanels: ['today', 'ai'],
      positions: { today: { x: 40, y: 60 }, ai: { x: 10, y: 20, minimized: true } },
    })
  })

  it('falls back to defaults and clears the key when the stored value is corrupt', () => {
    saved.set('better-self:town-immersive', '{not json')
    expect(loadWorldPrefs()).toEqual({ runMode: false, openPanels: [], positions: {} })
    expect(saved.has('better-self:town-immersive')).toBe(false)
  })
})
