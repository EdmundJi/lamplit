import { describe, expect, it } from 'vitest'
import { worldPanels } from './manifest'

describe('worldPanels manifest', () => {
  it('covers every feature exactly once with the fields the shell needs', () => {
    const keys = worldPanels.map(panel => panel.key)
    expect(new Set(keys).size).toBe(keys.length)
    expect(keys.sort()).toEqual(['ai', 'attributes', 'friends', 'goals', 'insights', 'mementos', 'partners', 'profile', 'settings', 'today'])
    for (const panel of worldPanels) {
      expect(panel.title.length).toBeGreaterThan(0)
      expect(panel.subtitle.length).toBeGreaterThan(0)
      expect(['compact', 'wide']).toContain(panel.size)
      expect(panel.fullPage.startsWith('/')).toBe(true)
    }
  })

  it('anchors every panel to a place so the dock stops being a plain nav bar', () => {
    const byKey = Object.fromEntries(worldPanels.map(panel => [panel.key, panel]))
    expect(byKey.today.anchor).toBe('home')
    expect(byKey.ai.anchor).toBe('npc:assistant')
    expect(byKey.friends.anchor).toBe('npc:postman')
    expect(byKey.insights.anchor).toBe('academy')
    expect(byKey.goals.anchor).toBe('cafe')
    expect(byKey.attributes.anchor).toBe('gym')
    expect(byKey.partners.anchor).toBe('park')
    expect(byKey.profile.anchor).toBe('plaza')
    expect(byKey.settings.anchor).toBe('street')
    // 全部 9 个面板都要有锚点：dock 才不会退化成和地点无关的导航栏。
    expect(worldPanels.every(panel => Boolean(panel.anchor))).toBe(true)
  })

  it('every loader resolves to a mountable component', async () => {
    for (const panel of worldPanels) {
      const mod = await panel.loader()
      expect(mod.default).toBeTruthy()
    }
  })
})
