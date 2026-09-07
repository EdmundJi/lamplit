import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import type { WorldPanelDef } from './panel.types'
import { MAX_OPEN_WINDOWS, anchorForSelection, useImmersiveStore } from './immersive.store'

function stubPanel(over: Partial<WorldPanelDef> & Pick<WorldPanelDef, 'key'>): WorldPanelDef {
  return {
    title: over.key,
    subtitle: '',
    icon: {} as WorldPanelDef['icon'],
    loader: () => Promise.reject(new Error('not used in this test')),
    size: 'compact',
    fullPage: `/${over.key}`,
    ...over,
  }
}

const panels: WorldPanelDef[] = [
  stubPanel({ key: 'today', anchor: 'home' }),
  stubPanel({ key: 'ai', anchor: 'npc:assistant' }),
  stubPanel({ key: 'friends', anchor: 'npc:postman' }),
  stubPanel({ key: 'insights', anchor: 'academy' }),
  stubPanel({ key: 'goals' }),
  stubPanel({ key: 'attributes' }),
]

describe('anchorForSelection', () => {
  it('maps academy/npc selections straight to their anchors', () => {
    expect(anchorForSelection('academy', 'me')).toBe('academy')
    expect(anchorForSelection('npc:assistant', 'me')).toBe('npc:assistant')
    expect(anchorForSelection('npc:postman', 'me')).toBe('npc:postman')
  })

  it('maps the self resident to home, and everyone else to nothing', () => {
    expect(anchorForSelection('me', 'me')).toBe('home')
    expect(anchorForSelection('friend-1', 'me')).toBeNull()
    expect(anchorForSelection(null, 'me')).toBeNull()
  })
})

describe('useImmersiveStore', () => {
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
    setActivePinia(createPinia())
  })

  it('compact mode keeps one visible window while preserving positions and reopen state', () => {
    const store = useImmersiveStore()
    store.openPanel('today')
    store.movePanel('today', 120, 150)
    store.openPanel('friends')
    store.setCompact(true)
    expect(store.windows.filter(win => !win.minimized).map(win => win.key)).toEqual(['friends'])
    store.focusPanel('today')
    expect(store.windows.filter(win => !win.minimized).map(win => win.key)).toEqual(['today'])
    expect(store.windows.find(win => win.key === 'today')).toMatchObject({ x: 120, y: 150 })
    expect(store.windows).toHaveLength(2)
  })

  it('opens a panel once and focuses it (rather than duplicating) on a second open', () => {
    const store = useImmersiveStore()
    store.openPanel('today')
    store.openPanel('ai')
    store.openPanel('today')
    expect(store.windows.map(item => item.key)).toEqual(['today', 'ai'])
    // "today" was re-opened last, so it is now the topmost window.
    expect(store.topmost).toBe('today')
  })

  it('closes a panel, and closeTopmost() removes whichever window is on top', () => {
    const store = useImmersiveStore()
    store.openPanel('today')
    store.openPanel('ai')
    store.closeTopmost()
    expect(store.windows.map(item => item.key)).toEqual(['today'])
    store.closePanel('today')
    expect(store.windows).toHaveLength(0)
  })

  it('minimizes and restores a window without losing its place in the list', () => {
    const store = useImmersiveStore()
    store.openPanel('today')
    store.minimizePanel('today')
    expect(store.isOpen('today')).toBe(true)
    expect(store.windows[0].minimized).toBe(true)
    store.focusPanel('today')
    expect(store.windows[0].minimized).toBe(false)
  })

  it('persists window drags immediately', () => {
    const store = useImmersiveStore()
    store.openPanel('today')
    store.movePanel('today', 111, 222)
    expect(store.windows[0]).toMatchObject({ x: 111, y: 222 })
    const saved2 = JSON.parse(saved.get('better-self:town-immersive') ?? '{}')
    expect(saved2.positions.today).toMatchObject({ x: 111, y: 222 })
  })

  it('caps simultaneously visible windows, minimizing the least-recently-focused one first', () => {
    const store = useImmersiveStore()
    // MAX_OPEN_WINDOWS is 4, so the 5th distinct panel pushes the first-opened one into minimized.
    for (const panel of panels.slice(0, 5)) store.openPanel(panel.key)
    const visible = store.windows.filter(item => !item.minimized)
    const minimized = store.windows.filter(item => item.minimized)
    expect(visible).toHaveLength(MAX_OPEN_WINDOWS)
    expect(minimized.map(item => item.key)).toEqual(['today']) // opened first, so lowest z
  })

  it('openForAnchor opens every panel in the manifest that declares that anchor, and does nothing for null', () => {
    const store = useImmersiveStore()
    store.openForAnchor('academy', panels)
    expect(store.windows.map(item => item.key)).toEqual(['insights'])
    store.openForAnchor(null, panels)
    expect(store.windows).toHaveLength(1)
  })

  it('hydrate() restores run mode and previously open windows, skipping panels no longer in the manifest', () => {
    saved.set('better-self:town-immersive', JSON.stringify({
      runMode: true,
      openPanels: ['today', 'retired-panel'],
      positions: { today: { x: 5, y: 9, minimized: true } },
    }))
    const store = useImmersiveStore()
    store.hydrate(panels)
    expect(store.runMode).toBe(true)
    expect(store.windows).toEqual([{ key: 'today', x: 5, y: 9, z: 1, minimized: true }])
    // A second hydrate() call is a no-op even if storage changes underneath it.
    store.hydrate(panels)
    expect(store.windows).toHaveLength(1)
  })

  it('setRunMode flips the flag and persists it', () => {
    const store = useImmersiveStore()
    store.setRunMode(true)
    expect(store.runMode).toBe(true)
    expect(JSON.parse(saved.get('better-self:town-immersive') ?? '{}').runMode).toBe(true)
  })
})

describe('dock 收起状态（M5-4）', () => {
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
    setActivePinia(createPinia())
  })

  it('默认是收起的——dock 常驻会把世界压成壁纸（plan §1 诊断 3）', () => {
    const store = useImmersiveStore()
    expect(store.dockCollapsed).toBe(true)
  })

  it('toggleDock 来回切换并持久化，hydrate 时能恢复', () => {
    const store = useImmersiveStore()
    store.toggleDock()
    expect(store.dockCollapsed).toBe(false)

    setActivePinia(createPinia())
    const restored = useImmersiveStore()
    restored.hydrate(panels)
    expect(restored.dockCollapsed).toBe(false)
  })
})
