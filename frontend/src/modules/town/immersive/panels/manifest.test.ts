import { describe, expect, it } from 'vitest'
import { worldPanels } from './manifest'

describe('worldPanels manifest', () => {
  it('covers every feature exactly once with the fields the shell needs', () => {
    const keys = worldPanels.map(panel => panel.key)
    expect(new Set(keys).size).toBe(keys.length)
    expect(keys.sort()).toEqual(['ai', 'attributes', 'friends', 'goals', 'insights', 'partners', 'profile', 'settings', 'today'])
    for (const panel of worldPanels) {
      expect(panel.title.length).toBeGreaterThan(0)
      expect(panel.subtitle.length).toBeGreaterThan(0)
      expect(['compact', 'wide']).toContain(panel.size)
      expect(panel.fullPage.startsWith('/')).toBe(true)
    }
  })

  it('anchors the four town-navigable panels the brief calls for', () => {
    const byKey = Object.fromEntries(worldPanels.map(panel => [panel.key, panel]))
    expect(byKey.today.anchor).toBe('home')
    expect(byKey.ai.anchor).toBe('npc:assistant')
    expect(byKey.friends.anchor).toBe('npc:postman')
    expect(byKey.insights.anchor).toBe('academy')
  })

  it('every loader resolves to a mountable component', async () => {
    for (const panel of worldPanels) {
      const mod = await panel.loader()
      expect(mod.default).toBeTruthy()
    }
  })
})
