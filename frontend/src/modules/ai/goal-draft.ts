export type GoalDraftTask = {
  title: string
  estimatedMinutes: number
  difficulty: number
}

export type GoalDraft = {
  sourceSessionPublicId: string
  title: string
  description: string
  dimensionCode: string
  durationDays: number
  weeklyFocus: string
  starterTasks: GoalDraftTask[]
  model?: string
  providerRequestId?: string
}

const STORAGE_KEY = 'better-self:ai-goal-draft'
const CHANGED_EVENT = 'better-self:ai-goal-draft-changed'

/** Same-tab storage writes do not emit a native storage event. */
export function onGoalDraftChanged(handler: () => void) {
  window.addEventListener(CHANGED_EVENT, handler)
  return () => window.removeEventListener(CHANGED_EVENT, handler)
}

export function peekGoalDraft(): GoalDraft | null {
  try {
    const value = window.sessionStorage.getItem(STORAGE_KEY)
    return value ? JSON.parse(value) as GoalDraft : null
  } catch { return null }
}

export function saveGoalDraft(draft: GoalDraft) {
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(draft))
  window.dispatchEvent(new Event(CHANGED_EVENT))
}

export function takeGoalDraft(): GoalDraft | null {
  const value = window.sessionStorage.getItem(STORAGE_KEY)
  if (!value) return null
  window.sessionStorage.removeItem(STORAGE_KEY)
  window.dispatchEvent(new Event(CHANGED_EVENT))
  try {
    return JSON.parse(value) as GoalDraft
  } catch {
    return null
  }
}
