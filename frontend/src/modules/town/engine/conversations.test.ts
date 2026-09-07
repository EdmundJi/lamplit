import { describe, expect, it, vi } from 'vitest'
import { conversationsMethods } from './conversations'
import type { TownSceneInstance } from './scene-core'
import type { Walker } from './shared'

function makeScene(withTalkingPoints = true) {
  const actor = (id: string, x: number, targetX: number) => ({
    id, sheet: id,
    // Spoken turns require acquainted NPCs with actual talking points. Background walkers
    // deliberately greet silently after the social-pacing change; they have no fallback prose.
    npc: withTalkingPoints ? { code: id, affinityToNpcs: { a: 0.4, b: 0.4 },
      talkingPoints: [{ text: id === 'a' ? '书架整理好了。' : '公园花开了。' }] } : null,
    resident: null, frozenUntil: 0, npcActivity: null,
    facing: 'right', state: 'walk', targetX, targetY: 100, speech: null, travelEmote: null,
    sprite: { x, y: 100, active: true, play: vi.fn() },
  }) as unknown as Walker
  const a = actor('a', 100, 200), b = actor('b', 144, 0)
  const scene = {
    ...conversationsMethods,
    runtime: {}, time: { now: 100 }, walkers: [a, b], greetCooldowns: new Map(), facilityNpcs: new Map(),
    facilities: undefined, conversation: null, restoreSheet: vi.fn(),
    saySomething: vi.fn((speaker: Walker) => { speaker.speech = { destroy: vi.fn() } as unknown as Walker['speech'] }),
  } as unknown as TownSceneInstance
  return { scene, a, b }
}

describe('paired in-world greeting', () => {
  it('faces both actors, emits a single turn at a time, then releases both without moving them', () => {
    const { scene, a, b } = makeScene()
    scene.triggerGreeting(a, b, 100)
    expect(a.sprite.play).toHaveBeenCalledWith('a-idle-right', true)
    expect(b.sprite.play).toHaveBeenCalledWith('b-idle-left', true)
    expect(scene.saySomething).not.toHaveBeenCalled()
    scene.time.now = 450; scene.detectGreetings(450)
    expect(scene.saySomething).toHaveBeenCalledTimes(1)
    expect(vi.mocked(scene.saySomething).mock.calls[0]?.[0]).toBe(a)
    const first = a.speech
    scene.time.now = 1750; scene.detectGreetings(1750)
    expect(first?.destroy).toHaveBeenCalled()
    expect(scene.saySomething).toHaveBeenCalledTimes(2)
    expect(vi.mocked(scene.saySomething).mock.calls[1]?.[0]).toBe(b)
    scene.time.now = 4000; scene.detectGreetings(4000)
    expect(a.frozenUntil).toBeLessThanOrEqual(4000)
    expect(b.frozenUntil).toBeLessThanOrEqual(4000)
    expect([a.sprite.x, b.sprite.x]).toEqual([100, 144])
  })
  it('cancels both actors and suppresses an upcoming reply when the player leaves', () => {
    const { scene, a, b } = makeScene()
    scene.triggerGreeting(a, b, 100)
    scene.time.now = 450; scene.detectGreetings(450)
    scene.time.now = 600; scene.cancelGreeting(a)
    expect(a.frozenUntil).toBe(600)
    expect(b.frozenUntil).toBe(600)
    expect(a.speech).toBeNull()
    scene.time.now = 1800; scene.detectGreetings(1800)
    expect(scene.saySomething).toHaveBeenCalledTimes(1)
  })
  it('lets background walkers greet silently without inventing ambient dialogue', () => {
    const { scene, a, b } = makeScene(false)
    scene.triggerGreeting(a, b, 100)
    expect(a.sprite.play).toHaveBeenCalledWith('a-idle-right', true)
    expect(b.sprite.play).toHaveBeenCalledWith('b-idle-left', true)
    scene.time.now = 450; scene.detectGreetings(450)
    scene.time.now = 1750; scene.detectGreetings(1750)
    scene.time.now = 4000; scene.detectGreetings(4000)
    expect(scene.saySomething).not.toHaveBeenCalled()
    expect(a.frozenUntil).toBeLessThanOrEqual(4000)
    expect(b.frozenUntil).toBeLessThanOrEqual(4000)
  })

  it('restores idle immediately when cancelling a same-character hand gesture', () => {
    const { scene, a, b } = makeScene()
    const stop = vi.fn(), setFrame = vi.fn()
    Object.assign(a.sprite, { texture: { getSourceImage: () => ({ width: 1854, height: 1280 }), has: () => true }, anims: { stop }, setFrame })
    scene.triggerGreeting(a, b, 100)
    scene.time.now = 450; scene.detectGreetings(450)
    expect(stop).toHaveBeenCalled()
    expect(setFrame).toHaveBeenCalledWith(11 * 57)
    vi.mocked(a.sprite.play).mockClear()
    scene.cancelGreeting(a)
    expect(a.sprite.play).toHaveBeenCalledWith('a-idle-right', true)
    expect(a.sprite.x).toBe(100)
  })

})
