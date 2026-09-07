import { defineStore } from 'pinia'
import type { WorldAnchor, WorldPanelDef, WorldPanelKey } from './panel.types'
import { loadWorldPrefs, saveWorldPrefs, type WorldWindowPref } from './world-prefs'

/** 同时最多开这么多个窗口；再开新的会把最久没被点过的那个收进最小化，而不是关掉它。 */
export const MAX_OPEN_WINDOWS = 4

/**
 * 把小镇引擎回传的 selection（town.engine.ts 的 TownSelection）映射到面板契约里的 WorldAnchor。
 * 用 string 而不是引入 TownSelection 类型，是为了不让这个 store 反过来依赖 town.engine。
 * gym/cafe/park 目前的引擎选中值还产生不了，先留着，等引擎支持了自然就会命中。
 */
export function anchorForSelection(selection: string | null, selfPublicId: string | null): WorldAnchor | null {
  if (selection && ['home', 'academy', 'gym', 'cafe', 'park', 'plaza', 'street'].includes(selection)) return selection as WorldAnchor
  if (selection === 'npc:assistant') return 'npc:assistant'
  if (selection === 'npc:postman') return 'npc:postman'
  if (selfPublicId && selection === selfPublicId) return 'home'
  return null
}

type WorldWindow = { key: WorldPanelKey; x: number; y: number; z: number; minimized: boolean }

function defaultPosition(index: number): WorldWindowPref {
  return { x: 64 + (index % 4) * 32, y: 64 + (index % 4) * 32 }
}

export const useImmersiveStore = defineStore('town-immersive', {
  state: () => ({
    hydrated: false,
    compact: false,
    runMode: false,
    windows: [] as WorldWindow[],
    nextZ: 1,
    /** M5-4：dock 默认收起。展开状态跟着 prefs 走，hydrate 时恢复。 */
    dockCollapsed: true,
  }),
  getters: {
    isOpen: state => (key: WorldPanelKey) => state.windows.some(item => item.key === key),
    /** 当前置顶（未最小化）的窗口，Esc/面板自己发出的 close 事件关的就是它。 */
    topmost: state => {
      const visible = state.windows.filter(item => !item.minimized)
      if (!visible.length) return null
      return visible.reduce((a, b) => (b.z > a.z ? b : a)).key
    },
  },
  actions: {
    setCompact(value: boolean) { this.compact = value; this.enforceLimit() },
    /** 从 localStorage 恢复上次开着的窗口和跑步模式；只在外壳挂载时调用一次。 */
    hydrate(panels: WorldPanelDef[]) {
      if (this.hydrated) return
      const prefs = loadWorldPrefs()
      this.runMode = prefs.runMode
      this.dockCollapsed = prefs.dockCollapsed
      const known = new Set(panels.map(item => item.key))
      let index = 0
      for (const key of prefs.openPanels) {
        if (!known.has(key)) continue // 面板清单变了，跳过已经不存在的面板
        const pos = prefs.positions[key] ?? defaultPosition(index)
        this.windows.push({ key, x: pos.x, y: pos.y, z: this.nextZ++, minimized: Boolean(pos.minimized) })
        index += 1
      }
      this.enforceLimit()
      this.hydrated = true
    },
    /** 走到某个锚点时，把清单里挂了这个 anchor 的面板都开起来；清单没写的锚点什么也不做。 */
    openForAnchor(anchor: WorldAnchor | null, panels: WorldPanelDef[]) {
      if (!anchor) return
      for (const panel of panels) if (panel.anchor === anchor) this.openPanel(panel.key)
    },
    openPanel(key: WorldPanelKey) {
      const existing = this.windows.find(item => item.key === key)
      if (existing) { this.focusPanel(key); return }
      const prefs = loadWorldPrefs()
      const pos = prefs.positions[key] ?? defaultPosition(this.windows.length)
      this.windows.push({ key, x: pos.x, y: pos.y, z: this.nextZ++, minimized: false })
      this.enforceLimit()
      this.persist()
    },
    closePanel(key: WorldPanelKey) {
      this.windows = this.windows.filter(item => item.key !== key)
      this.persist()
    },
    /** 面板内部发出不带具体目标的 close 事件时，关的是当前置顶的那扇窗。 */
    closeTopmost() {
      const key = this.topmost
      if (key) this.closePanel(key)
    },
    minimizePanel(key: WorldPanelKey) {
      const win = this.windows.find(item => item.key === key)
      if (!win) return
      win.minimized = true
      this.persist()
    },
    focusPanel(key: WorldPanelKey) {
      const win = this.windows.find(item => item.key === key)
      if (!win) return
      win.minimized = false
      win.z = this.nextZ++
      this.enforceLimit()
      this.persist()
    },
    movePanel(key: WorldPanelKey, x: number, y: number) {
      const win = this.windows.find(item => item.key === key)
      if (!win) return
      win.x = x
      win.y = y
      this.persist()
    },
    /** 展开的窗口超过上限时，把最久没被聚焦过的收进最小化——不丢状态，只是先让路。 */
    enforceLimit() {
      const visible = this.windows.filter(item => !item.minimized).sort((a, b) => a.z - b.z)
      while (visible.length > (this.compact ? 1 : MAX_OPEN_WINDOWS)) {
        const oldest = visible.shift()
        if (!oldest) break
        oldest.minimized = true
      }
    },
    setDockCollapsed(collapsed: boolean) {
      this.dockCollapsed = collapsed
      this.persist()
    },
    toggleDock() {
      this.setDockCollapsed(!this.dockCollapsed)
    },
    setRunMode(enabled: boolean) {
      this.runMode = enabled
      this.persist()
    },
    persist() {
      const positions: Record<string, { x: number; y: number; minimized?: boolean }> = {}
      for (const win of this.windows) positions[win.key] = { x: win.x, y: win.y, minimized: win.minimized }
      saveWorldPrefs({
        runMode: this.runMode,
        openPanels: this.windows.map(item => item.key),
        positions,
        dockCollapsed: this.dockCollapsed,
      })
    },
  },
})
