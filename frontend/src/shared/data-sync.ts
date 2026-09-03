export type DataArea =
  | 'goals'
  | 'tasks'
  | 'today'
  | 'insights'
  | 'attributes'
  | 'achievements'
  | 'profile'
  | 'partners'
  | 'social'
  | 'settings'
  | 'all'

export const DATA_CHANGED_EVENT = 'better-self:data-changed'

export type DataChangedDetail = {
  areas: DataArea[]
}

type DataChangedHandler = (areas: DataArea[]) => void | Promise<void>

function normalizeAreas(areas: DataArea | readonly DataArea[]) {
  const values = Array.isArray(areas) ? areas : [areas]
  return [...new Set(values)]
}

function matches(listenedAreas: DataArea[], changedAreas: DataArea[]) {
  return listenedAreas.includes('all')
    || changedAreas.includes('all')
    || listenedAreas.some(area => changedAreas.includes(area))
}

export function notifyDataChanged(areas: DataArea | readonly DataArea[]) {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent<DataChangedDetail>(DATA_CHANGED_EVENT, {
    detail: { areas: normalizeAreas(areas) },
  }))
}

export function onDataChanged(areas: DataArea | readonly DataArea[], handler: DataChangedHandler) {
  if (typeof window === 'undefined') return () => undefined
  const listenedAreas = normalizeAreas(areas)
  const listener = (event: Event) => {
    const detail = (event as CustomEvent<DataChangedDetail>).detail
    if (!Array.isArray(detail?.areas) || !matches(listenedAreas, detail.areas)) return
    void Promise.resolve(handler(detail.areas)).catch(() => undefined)
  }
  window.addEventListener(DATA_CHANGED_EVENT, listener)
  return () => window.removeEventListener(DATA_CHANGED_EVENT, listener)
}
