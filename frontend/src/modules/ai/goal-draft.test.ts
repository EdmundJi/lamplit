import { beforeEach, describe, expect, it } from 'vitest'
import { saveGoalDraft, takeGoalDraft, type GoalDraft } from './goal-draft'

const draft: GoalDraft = {
  sourceSessionPublicId: 'session-1',
  title: '四周复习计划',
  description: '每周完成三次复习',
  dimensionCode: 'KNOWLEDGE',
  durationDays: 28,
  weeklyFocus: '先稳定频率',
  starterTasks: [{ title: '复习一节', estimatedMinutes: 25, difficulty: 2 }],
}

describe('AI goal draft handoff', () => {
  beforeEach(() => window.sessionStorage.clear())

  it('stores a draft for one-time use on the goal page', () => {
    saveGoalDraft(draft)
    expect(takeGoalDraft()).toEqual(draft)
    expect(takeGoalDraft()).toBeNull()
  })
})
