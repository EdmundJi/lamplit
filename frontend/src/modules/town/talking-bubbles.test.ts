import { wrapSpeech, peerBubbleTier } from './talking-bubbles'
import { describe, expect, it } from 'vitest'
import { GUIDE_CODE, bubbleTierFor, canInitiate, consume, layoutBubbles, pointsToPlay, type BubbleBox, type CameraRect } from './talking-bubbles'
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

describe('layoutBubbles (M7-8 气泡排版兜底)', () => {
  const CAMERA: CameraRect = { x: 0, y: 0, width: 800, height: 600 }

  function box(id: string, x: number, y: number, width = 120, height = 40): BubbleBox {
    return { id, x, y, width, height }
  }

  function overlaps(a: BubbleBox, b: BubbleBox): boolean {
    return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y
  }

  function withinCamera(b: BubbleBox, camera: CameraRect): boolean {
    return b.x >= camera.x && b.y >= camera.y && b.x + b.width <= camera.x + camera.width && b.y + b.height <= camera.y + camera.height
  }

  it('leaves already-fine bubbles untouched (no conflict, already on-screen)', () => {
    const bubbles = [box('a', 50, 50), box('b', 400, 300)]
    const result = layoutBubbles(bubbles, CAMERA)
    expect(result).toEqual(bubbles)
  })

  it('two overlapping bubbles no longer overlap after resolving', () => {
    const bubbles = [box('a', 100, 100), box('b', 110, 105)] // same-ish spot, clearly overlapping
    const [a, b] = layoutBubbles(bubbles, CAMERA)
    expect(overlaps(a, b)).toBe(false)
  })

  it('stacks the later bubble upward rather than sideways when resolving overlap', () => {
    const bubbles = [box('a', 100, 300), box('b', 100, 300)]
    const [a, b] = layoutBubbles(bubbles, CAMERA)
    expect(a.x).toBe(b.x) // still horizontally aligned...
    expect(b.y).toBeLessThan(a.y) // ...but the second one moved up, not sideways
  })

  it('pulls a bubble poking off every edge fully back inside the camera', () => {
    const bubbles = [
      box('left', -50, 100),
      box('right', 780, 100),
      box('top', 300, -30),
      box('bottom', 300, 590),
    ]
    const result = layoutBubbles(bubbles, CAMERA)
    for (const b of result) {
      expect(withinCamera(b, CAMERA)).toBe(true)
    }
  })

  it('resolves a chain of three mutually overlapping bubbles into three non-overlapping ones', () => {
    const bubbles = [box('a', 200, 200), box('b', 205, 205), box('c', 210, 210)]
    const result = layoutBubbles(bubbles, CAMERA)
    for (let i = 0; i < result.length; i++) {
      for (let j = i + 1; j < result.length; j++) {
        expect(overlaps(result[i], result[j])).toBe(false)
      }
      expect(withinCamera(result[i], CAMERA)).toBe(true)
    }
  })

  it('never mutates the input array or its boxes', () => {
    const bubbles = [box('a', 100, 100), box('b', 105, 105)]
    const snapshot = JSON.parse(JSON.stringify(bubbles))
    layoutBubbles(bubbles, CAMERA)
    expect(bubbles).toEqual(snapshot)
  })
})


describe('中文气泡换行', () => {
  it('breaks long CJK sentences without dropping text', () => {
    const text = '听说公园的花开了，等会儿路过时去看看。'
    const lines = wrapSpeech(text, 8)
    expect(lines.join('')).toBe(text)
    expect(lines.every(line => Array.from(line).length <= 8)).toBe(true)
  })
  it('preserves explicit line breaks and unicode characters', () => {
    expect(wrapSpeech('早安\n今天也慢慢走🌳')).toEqual(['早安', '今天也慢慢走🌳'])
  })
})


describe('NPC peer familiarity', () => {
  it('distinguishes strangers, acquaintances and close peers without using player affinity', () => {
    expect(peerBubbleTier({ code: 'A', affinityToNpcs: { B: .8 } }, { code: 'B' })).toBe('high')
    expect(peerBubbleTier({ code: 'A' }, { code: 'B', affinityToNpcs: { A: .4 } })).toBe('mid')
    expect(peerBubbleTier({ code: 'A' }, { code: 'B' })).toBe('low')
  })
})
