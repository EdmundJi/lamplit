import { describe, expect, it } from 'vitest'
import { backoffRemainingSeconds, memoriesByOwner, personalityDrift, relationshipMatrix, sharedMemoryTopics } from './companion-debug.presentation'
import type { DebugResidentState } from './companion-debug.types'

function state(overrides: Partial<DebugResidentState> = {}): DebugResidentState {
  return {
    id: 'owner', energy: 50, social: 50, curiosity: 50, mood: '', goal: '', thought: '',
    plan: null, relationships: {}, revision: 1,
    extroversion: 78, conscientiousness: 85, sensitivity: 48, volatility: 45,
    personalitySeeded: true, affectionExpressed: {},
    ...overrides,
  }
}

describe('personalityDrift', () => {
  it('reports no movement against the seed table when nothing changed', () => {
    const drift = personalityDrift(state())
    expect(drift).toHaveLength(4)
    expect(drift.every(d => d.delta === 0)).toBe(true)
    expect(drift.find(d => d.key === 'conscientiousness')).toEqual({ key: 'conscientiousness', label: '尽责', initial: 85, current: 85, delta: 0 })
  })
  it('shows a positive or negative delta once experience has moved a value - drift can go either way', () => {
    const up = personalityDrift(state({ conscientiousness: 91 }))
    expect(up.find(d => d.key === 'conscientiousness')?.delta).toBe(6)
    const down = personalityDrift(state({ conscientiousness: 72 }))
    expect(down.find(d => d.key === 'conscientiousness')?.delta).toBe(-13)
  })
  it('has no baseline for a resident outside the seed table, like the user\'s own avatar', () => {
    const drift = personalityDrift(state({ id: 'self', extroversion: 61 }))
    expect(drift.every(d => d.initial === null && d.delta === null)).toBe(true)
    expect(drift.find(d => d.key === 'extroversion')?.current).toBe(61)
  })
})

describe('memoriesByOwner', () => {
  it('splits memories by owner and orders each list newest first', () => {
    const grouped = memoriesByOwner([
      { id: 'm1', ownerId: 'owner', sourceId: 'owner', sourceType: 'seed', at: '2026-09-08T00:00:00Z', text: 'old' },
      { id: 'm2', ownerId: 'owner', sourceId: 'owner', sourceType: 'observed', at: '2026-09-08T02:00:00Z', text: 'new' },
      { id: 'm3', ownerId: 'student', sourceId: 'student', sourceType: 'observed', at: '2026-09-08T01:00:00Z', text: 'other' },
    ])
    expect(grouped.get('owner')!.map(m => m.id)).toEqual(['m2', 'm1'])
    expect(grouped.get('student')!.map(m => m.id)).toEqual(['m3'])
  })
})

describe('sharedMemoryTopics', () => {
  it('excludes a topic only one resident wrote a memory about', () => {
    const groups = sharedMemoryTopics([
      { id: 'm1', ownerId: 'owner', sourceId: 'owner', sourceType: 'observed', at: '2026-09-08T00:00:00Z', text: 'a', topicId: 'wish-1' },
    ])
    expect(groups).toHaveLength(0)
  })
  it('pairs up different owners\' versions of the same event under its topicId, newest topic first', () => {
    const groups = sharedMemoryTopics([
      { id: 'old1', ownerId: 'owner', sourceId: 'owner', sourceType: 'observed', at: '2026-09-08T00:00:00Z', text: '早一点的事·我这边', topicId: 'wish-1' },
      { id: 'old2', ownerId: 'student', sourceId: 'student', sourceType: 'observed', at: '2026-09-08T00:00:01Z', text: '早一点的事·她那边', topicId: 'wish-1' },
      { id: 'new1', ownerId: 'artist', sourceId: 'artist', sourceType: 'observed', at: '2026-09-08T05:00:00Z', text: '海报的事·我这边', topicId: 'wish-2' },
      { id: 'new2', ownerId: 'gardener', sourceId: 'gardener', sourceType: 'observed', at: '2026-09-08T05:00:01Z', text: '海报的事·他那边', topicId: 'wish-2' },
    ])
    expect(groups.map(g => g.topicId)).toEqual(['wish-2', 'wish-1'])
    expect(groups[0].entries.map(e => e.ownerId).sort()).toEqual(['artist', 'gardener'])
  })
})

describe('relationshipMatrix', () => {
  it('keeps A-about-B and B-about-A as separate, possibly different, numbers', () => {
    const states: DebugResidentState[] = [
      state({ id: 'owner', relationships: { student: 80 }, affectionExpressed: { student: true } }),
      state({ id: 'student', relationships: { owner: 20 }, affectionExpressed: {} }),
    ]
    const matrix = relationshipMatrix(states, ['owner', 'student'])
    expect(matrix.get('owner')!.get('student')).toEqual({ value: 80, expressed: true })
    expect(matrix.get('student')!.get('owner')).toEqual({ value: 20, expressed: false })
  })
  it('omits the diagonal and returns null for a pair with no recorded relationship yet', () => {
    const states: DebugResidentState[] = [state({ id: 'owner', relationships: {} })]
    const matrix = relationshipMatrix(states, ['owner', 'student'])
    expect(matrix.get('owner')!.has('owner')).toBe(false)
    expect(matrix.get('owner')!.get('student')).toEqual({ value: null, expressed: false })
  })
})

describe('backoffRemainingSeconds', () => {
  it('is zero with no retryAfter or once it has passed', () => {
    expect(backoffRemainingSeconds(null, Date.now())).toBe(0)
    expect(backoffRemainingSeconds('2026-09-08T00:00:00Z', Date.parse('2026-09-08T00:00:05Z'))).toBe(0)
  })
  it('counts down while a failure backoff is active', () => {
    expect(backoffRemainingSeconds('2026-09-08T00:00:10Z', Date.parse('2026-09-08T00:00:00Z'))).toBe(10)
  })
})
