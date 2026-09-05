import type { WorldPanelKey } from './panel.types'

const STORAGE_KEY = 'better-self:town-immersive'

export type WorldWindowPref = { x: number; y: number; minimized?: boolean }

export type WorldPrefs = {
  /** 跑步模式：与 TownGame.setRun 同步，下次进入沉浸小镇时恢复。 */
  runMode: boolean
  /** 退出沉浸模式时还留着的面板（含最小化的），下次进入原样恢复。 */
  openPanels: WorldPanelKey[]
  /** 每个面板窗口最后一次的位置/最小化状态。 */
  positions: Partial<Record<WorldPanelKey, WorldWindowPref>>
}

const DEFAULT_PREFS: WorldPrefs = { runMode: false, openPanels: [], positions: {} }

function storageAvailable() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined'
}

export function loadWorldPrefs(): WorldPrefs {
  if (!storageAvailable()) return { ...DEFAULT_PREFS }
  const raw = window.localStorage.getItem(STORAGE_KEY)
  if (!raw) return { ...DEFAULT_PREFS }
  try {
    const parsed = JSON.parse(raw) as Partial<WorldPrefs>
    return {
      runMode: Boolean(parsed.runMode),
      openPanels: Array.isArray(parsed.openPanels) ? parsed.openPanels : [],
      positions: parsed.positions && typeof parsed.positions === 'object' ? parsed.positions : {},
    }
  } catch {
    window.localStorage.removeItem(STORAGE_KEY)
    return { ...DEFAULT_PREFS }
  }
}

export function saveWorldPrefs(prefs: WorldPrefs) {
  if (!storageAvailable()) return
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs))
}
