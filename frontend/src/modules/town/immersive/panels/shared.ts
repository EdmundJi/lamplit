import { router } from '../../../../app/router'
import type { WorldBridge, WorldPanelKey } from '../panel.types'

/** Avatar initial: Chinese name keeps its first character, everything else upper-cases. */
export function initialOf(name: string) {
  const characters = Array.from(name.trim())
  if (!characters.length) return '好'
  if (/\p{Script=Han}/u.test(characters[0])) return characters[0]
  return characters[0].toUpperCase()
}

/** Same-day messages show a clock time; anything older shows month/day too. */
export function timeLabel(value?: string | null) {
  if (!value) return ''
  const date = new Date(value)
  const now = new Date()
  const sameDay = date.getFullYear() === now.getFullYear() && date.getMonth() === now.getMonth() && date.getDate() === now.getDate()
  return new Intl.DateTimeFormat('zh-CN', sameDay
    ? { hour: '2-digit', minute: '2-digit' }
    : { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}

export function localDateKey(value = new Date()) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** Mirrors TodayView's default deferred time: tomorrow, same local minute, as an ISO instant. */
export function tomorrowIso() {
  const value = Date.now() + 86400000
  const local = new Date(value - new Date(value).getTimezoneOffset() * 60000)
  return new Date(local.toISOString().slice(0, 16)).toISOString()
}

/** "打开完整页面": leave the world window and land on the same feature's full route. */
export function openFullPage(bridge: WorldBridge | undefined, path: string) {
  bridge?.emit({ type: 'close' })
  void router.push(path)
}

/** Ask the world to switch to a sibling panel without leaving the town. */
export function openPanel(bridge: WorldBridge | undefined, panel: WorldPanelKey) {
  bridge?.emit({ type: 'open', panel })
}
