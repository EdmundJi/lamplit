import { describe, expect, it } from 'vitest'
import { GUIDE_CODE, bubbleTierFor, canInitiate, consume, pointsToPlay } from './talking-bubbles'
import type { InitiativeBudget, NpcTalkingPoint } from './town-npc.types'

const POINTS: NpcTalkingPoint[] = [
  { factId: 'f1', text: '听说...', hops: 1, salience: 0.8 },
  { factId: 'f2', text: '好像...', hops: 2, salience: 0.5 },
  { factId: 'f3', text: '不知道谁说的...', hops: 3, salience: 0.2 },
]

describe('bubbleTierFor', () => {
  it('is high at and above 0.6', () => {
    expect(bubbleTierFor(0.6)).toBe('high')
    expect(bubbleTierFor(1)).toBe('high')
  })

  it('is mid between 0.3 (inclusive) and 0.6 (exclusive)', () => {
    expect(bubbleTierFor(0.3)).toBe('mid')
    expect(bubbleTierFor(0.59)).toBe('mid')
  })

  it('is low below 0.3', () => {
    expect(bubbleTierFor(0.29)).toBe('low')
    expect(bubbleTierFor(0)).toBe('low')
  })
})

describe('pointsToPlay', () => {
  it('high plays up to 3 of the (salience-sorted) points', () => {
    expect(pointsToPlay('high', POINTS)).toEqual(POINTS.slice(0, 3))
  })

  it('high plays fewer than 3 when fewer are available', () => {
    expect(pointsToPlay('high', POINTS.slice(0, 1))).toEqual(POINTS.slice(0, 1))
    expect(pointsToPlay('high', [])).toEqual([])
  })

  it('mid plays exactly the top 1', () => {
    expect(pointsToPlay('mid', POINTS)).toEqual([POINTS[0]])
  })

  it('mid plays nothing when there is nothing to play', () => {
    expect(pointsToPlay('mid', [])).toEqual([])
  })

  it('low plays none — just a greeting', () => {
    expect(pointsToPlay('low', POINTS)).toEqual([])
  })
})

describe('initiative budget (护栏 A)', () => {
  const fresh: InitiativeBudget = { limit: 3, used: 0 }

  it('allows any NPC to initiate while slots remain and it is not the last one', () => {
    expect(canInitiate(fresh, 'KE_YUN')).toBe(true)
    expect(canInitiate(fresh, GUIDE_CODE)).toBe(true)
  })

  it('reserves the very last remaining slot for GUIDE over any other NPC', () => {
    const oneLeft: InitiativeBudget = { limit: 3, used: 2 }
    expect(canInitiate(oneLeft, 'KE_YUN')).toBe(false)
    expect(canInitiate(oneLeft, 'LU_XIA')).toBe(false)
    expect(canInitiate(oneLeft, GUIDE_CODE)).toBe(true)
  })

  it('once the budget is fully exhausted, no NPC — not even GUIDE — may initiate (M2-5 acceptance)', () => {
    const exhausted: InitiativeBudget = { limit: 3, used: 3 }
    expect(canInitiate(exhausted, GUIDE_CODE)).toBe(false)
    expect(canInitiate(exhausted, 'KE_YUN')).toBe(false)
    expect(canInitiate(exhausted, 'POSTMAN')).toBe(false)
    expect(canInitiate(exhausted, 'TOWNIE_01')).toBe(false)
  })

  it('a zero-limit budget lets nobody initiate, including GUIDE', () => {
    const none: InitiativeBudget = { limit: 0, used: 0 }
    expect(canInitiate(none, GUIDE_CODE)).toBe(false)
    expect(canInitiate(none, 'KE_YUN')).toBe(false)
  })

  it('consume increments used without mutating the input budget', () => {
    const before: InitiativeBudget = { limit: 3, used: 0 }
    const after = consume(before, 'KE_YUN')
    expect(before).toEqual({ limit: 3, used: 0 }) // untouched
    expect(after).toEqual({ limit: 3, used: 1 })
    expect(after).not.toBe(before)
  })

  it('consume is a no-op (returns the same values) when the NPC is not allowed to initiate', () => {
    const oneLeft: InitiativeBudget = { limit: 3, used: 2 }
    const after = consume(oneLeft, 'KE_YUN')
    expect(after).toEqual(oneLeft)
  })

  it('draining the whole budget via consume ends with nobody able to initiate any further', () => {
    let budget: InitiativeBudget = { limit: 3, used: 0 }
    budget = consume(budget, 'KE_YUN')
    budget = consume(budget, 'LU_XIA')
    // Last slot: only GUIDE may take it. A non-GUIDE attempt must not consume it.
    budget = consume(budget, 'WEN_QING')
    expect(budget).toEqual({ limit: 3, used: 2 })
    budget = consume(budget, GUIDE_CODE)
    expect(budget).toEqual({ limit: 3, used: 3 })

    for (const code of ['KE_YUN', 'LU_XIA', 'WEN_QING', GUIDE_CODE, 'ANY_OTHER_NPC']) {
      expect(canInitiate(budget, code)).toBe(false)
    }
  })
})
