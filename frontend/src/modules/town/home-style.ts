export const HOME_STYLES = {
  original: { name: '原木日常', description: '保留原来的家具颜色。', rug: '#ffffff', chair: '#ffffff', lamp: '#ffffff' },
  meadow: { name: '草木清晨', description: '鼠尾草绿地毯、浅绿扶手椅与暖灯。', rug: '#9bbf9f', chair: '#c5dfb9', lamp: '#ffe3ad' },
  dusk: { name: '暮色阅读', description: '蓝灰地毯、淡紫扶手椅与柔和暖灯。', rug: '#a6bad4', chair: '#d1bee1', lamp: '#ffd29b' },
} as const
export type HomeStyle = keyof typeof HOME_STYLES
export type HomePreference = { style: HomeStyle; hiddenMementos: string[] }
const defaults = (): HomePreference => ({ style: 'original', hiddenMementos: [] })
const listeners = new Map<string, Set<(value: HomePreference) => void>>()
const previews = new Map<string, HomePreference>()
const key = (id: string) => `town-home:v1:${encodeURIComponent(id)}`
export function readHomePreference(id: string): HomePreference {
  if (!id) return defaults()
  try {
    const raw = JSON.parse(localStorage.getItem(key(id)) ?? 'null')
    return { style: raw && Object.hasOwn(HOME_STYLES, raw.style) ? raw.style : 'original', hiddenMementos: Array.isArray(raw?.hiddenMementos) ? raw.hiddenMementos.filter((v: unknown) => typeof v === 'string').slice(0, 500) : [] }
  } catch { return defaults() }
}
export function currentHomePreference(id: string) { return previews.get(id) ?? readHomePreference(id) }
function publish(id: string) { listeners.get(id)?.forEach(listener => listener(currentHomePreference(id))) }
export function previewHomeStyle(id: string, style: HomeStyle) {
  if (!id) return
  previews.set(id, { ...readHomePreference(id), style }); publish(id)
}
export function cancelHomePreview(id: string) { previews.delete(id); publish(id) }
export function saveHomePreference(id: string, value: HomePreference): boolean {
  if (!id) return false
  try { localStorage.setItem(key(id), JSON.stringify(value)) } catch { return false }
  previews.delete(id); publish(id); return true
}
export function observeHomePreference(id: string, listener: (value: HomePreference) => void) {
  let group = listeners.get(id)
  if (!group) { group = new Set(); listeners.set(id, group) }
  group.add(listener); listener(currentHomePreference(id))
  return () => { group!.delete(listener); if (!group!.size) listeners.delete(id) }
}
