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

export function saveGoalDraft(draft: GoalDraft) {
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(draft))
}

export function takeGoalDraft(): GoalDraft | null {
  const value = window.sessionStorage.getItem(STORAGE_KEY)
  if (!value) return null
  window.sessionStorage.removeItem(STORAGE_KEY)
  try {
    return JSON.parse(value) as GoalDraft
  } catch {
    return null
  }
}
