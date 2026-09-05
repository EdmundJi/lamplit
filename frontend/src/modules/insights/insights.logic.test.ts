import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computeChangeSummary, confirmedAtLabel, useWeeklyReview, type TrendRow } from './insights.logic'

const api = vi.hoisted(() => ({ get: vi.fn(), patch: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

describe('computeChangeSummary', () => {
  const reference = new Date('2026-08-04T08:00:00+08:00')

  it('states facts without a conclusion when there are no records at all', () => {
    const summary = computeChangeSummary([], reference)
    expect(summary.hasBaseline).toBe(false)
    expect(summary.headline).toContain('还没有可以对比的记录')
  })

  it('states the recent count but draws no growth conclusion without a prior week to compare', () => {
    const trends: TrendRow[] = [{ date: '2026-08-03', effectiveActions: 2, experience: 10 }]
    const summary = computeChangeSummary(trends, reference)
    expect(summary.hasBaseline).toBe(false)
    expect(summary.headline).toBe('最近 7 天有 2 次有效行动')
    expect(summary.detail).toContain('还没有上一周的记录')
  })

  it('compares against the prior week once both windows have records', () => {
    const trends: TrendRow[] = [
      { date: '2026-08-03', effectiveActions: 3, experience: 10 },
      { date: '2026-07-27', effectiveActions: 1, experience: 5 },
    ]
    const summary = computeChangeSummary(trends, reference)
    expect(summary.hasBaseline).toBe(true)
    expect(summary.headline).toContain('比上一周多 2 次')
  })
})

describe('confirmedAtLabel', () => {
  it('returns an empty string when nothing is confirmed yet', () => {
    expect(confirmedAtLabel(null)).toBe('')
    expect(confirmedAtLabel(undefined)).toBe('')
  })
})

describe('useWeeklyReview draft/confirm state machine', () => {
  const review = {
    publicId: 'review-1',
    planPublicId: 'plan-1',
    facts: { plannedActions: 4, effectiveActions: 3, fulfillmentRate: 0.75 },
    userReflection: '',
    proposedAdjustments: {},
    confirmedAdjustments: {},
    confirmedAt: null,
  }

  beforeEach(() => {
    api.get.mockReset()
    api.patch.mockReset()
    api.post.mockReset()
  })

  afterEach(() => vi.restoreAllMocks())

  it('loads a draft and fills the form from proposed adjustments', async () => {
    api.get.mockResolvedValue({ ...review, userReflection: '还不错', proposedAdjustments: { steadyAction: '晨跑' } })
    const state = useWeeklyReview()
    await state.load('plan-1')
    await flushPromises()
    expect(state.review.value?.publicId).toBe('review-1')
    expect(state.form.userReflection).toBe('还不错')
    expect(state.form.steadyAction).toBe('晨跑')
    expect(state.confirmed.value).toBe(false)
  })

  it('does nothing when asked to load an empty plan id', async () => {
    const state = useWeeklyReview()
    await state.load('')
    expect(api.get).not.toHaveBeenCalled()
    expect(state.review.value).toBeNull()
  })

  it('saves a draft, staying unconfirmed, and can be saved repeatedly', async () => {
    api.get.mockResolvedValue(review)
    api.patch.mockResolvedValue({ ...review, userReflection: '更新后的反思' })
    const state = useWeeklyReview()
    await state.load('plan-1')
    state.form.userReflection = '更新后的反思'
    const ok = await state.save('plan-1')
    expect(ok).toBe(true)
    expect(state.confirmed.value).toBe(false)
    expect(state.feedback.value).toBe('复盘草稿已保存')
    expect(api.patch).toHaveBeenCalledWith('/reviews/weekly/plan-1', expect.objectContaining({ userReflection: '更新后的反思' }))
  })

  it('reports a readable error and keeps the draft editable when saving fails', async () => {
    api.get.mockResolvedValue(review)
    api.patch.mockRejectedValue(new Error('network'))
    const state = useWeeklyReview()
    await state.load('plan-1')
    const ok = await state.save('plan-1')
    expect(ok).toBe(false)
    expect(state.error.value).toContain('暂时无法保存')
    expect(state.confirmed.value).toBe(false)
  })

  it('confirms after saving the draft, moving the review into a confirmed, archived state', async () => {
    api.get.mockResolvedValue(review)
    api.patch.mockResolvedValue(review)
    api.post.mockResolvedValue({ ...review, confirmedAt: '2026-08-04T09:30:00Z', confirmedAdjustments: { steadyAction: '晨跑' } })
    const state = useWeeklyReview()
    await state.load('plan-1')
    const ok = await state.confirm('plan-1')
    expect(ok).toBe(true)
    expect(api.patch).toHaveBeenCalled()
    expect(api.post).toHaveBeenCalledWith('/reviews/weekly/plan-1/confirm')
    expect(state.confirmed.value).toBe(true)
    expect(state.feedback.value).toBe('本周复盘已确认')
  })

  it('refuses to save once a review is already confirmed', async () => {
    api.get.mockResolvedValue({ ...review, confirmedAt: '2026-08-04T09:30:00Z' })
    const state = useWeeklyReview()
    await state.load('plan-1')
    expect(state.confirmed.value).toBe(true)
    const ok = await state.save('plan-1')
    expect(ok).toBe(false)
    expect(api.patch).not.toHaveBeenCalled()
  })
})
