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
    expect(loadWorldPrefs()).toEqual({ runMode: false, openPanels: [], positions: {}, dockCollapsed: true })
  })

  it('round-trips run mode, open panels and window positions', () => {
    saveWorldPrefs({ runMode: true, openPanels: ['today', 'ai'], positions: { today: { x: 40, y: 60 }, ai: { x: 10, y: 20, minimized: true } }, dockCollapsed: false })
    expect(loadWorldPrefs()).toEqual({
      runMode: true,
      openPanels: ['today', 'ai'],
      positions: { today: { x: 40, y: 60 }, ai: { x: 10, y: 20, minimized: true } },
      dockCollapsed: false,
    })
  })

  it('falls back to defaults and clears the key when the stored value is corrupt', () => {
    saved.set('better-self:town-immersive', '{not json')
    expect(loadWorldPrefs()).toEqual({ runMode: false, openPanels: [], positions: {}, dockCollapsed: true })
    expect(saved.has('better-self:town-immersive')).toBe(false)
  })

  it('keeps the dock collapsed for prefs saved before the flag existed', () => {
    // 老键里没有 dockCollapsed，不能被 Boolean(undefined) 读成"展开"——那样升级一次就退回导航栏。
    saved.set('better-self:town-immersive', JSON.stringify({ runMode: true, openPanels: [], positions: {} }))
    expect(loadWorldPrefs().dockCollapsed).toBe(true)
  })
})
