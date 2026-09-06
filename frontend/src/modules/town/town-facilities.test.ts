import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type Phaser from 'phaser'
import { createTownFacilities, type TownFacilityAnchors } from './town-facilities'

function drawable() {
  const state: Record<string, unknown> = { active: true, scene: {}, x: 100, y: 132, angle: 0 }
  const object = new Proxy(state, { get(target, key: string) {
    if (key in target) return target[key]
    if (key === 'destroy') return () => { target.active = false }
    if (key === 'setAngle') return (value: number) => { target.angle = value; return object }
    return () => object
  } })
  return object
}
const anchors = Object.fromEntries(['coffee', 'planter', 'records', 'books'].map(id => [id, { x: 100, y: 100, actionPoint: { x: 100, y: 132 } }])) as TownFacilityAnchors
const scene = { add: { graphics: drawable, zone: drawable, text: drawable, image: drawable } } as unknown as Phaser.Scene
const actor = () => drawable() as unknown as Phaser.GameObjects.Sprite

describe('town interactive facilities', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => vi.unstubAllGlobals())
  it('requires arrival and protects reserved facilities from another actor', () => {
    const f = createTownFacilities(scene, anchors), a = actor()
    a.x = 800
    expect(f.activate('coffee', a, 'self')).toBe(false)
    expect(f.reserve('coffee', 'npc')).toBe(true)
    a.x = 100
    expect(f.activate('coffee', a, 'self')).toBe(false)
    expect(f.activate('coffee', a, 'npc')).toBe(true)
    f.destroy()
  })
  it('does not drink from across the table using the old 52px activation allowance', () => {
    const f = createTownFacilities(scene, anchors), a = actor()
    a.x = 125
    expect(f.activate('coffee', a, 'self')).toBe(false)
    a.x = 107
    expect(f.activate('coffee', a, 'self')).toBe(true)
    f.destroy()
  })
  it('commits and persists only a completed activity, without changing other facilities', () => {
    const onComplete = vi.fn(), f = createTownFacilities(scene, anchors, { storageKey: 'town:test', onComplete })
    f.activate('coffee', actor(), 'self')
    f.update(3000)
    expect(f.snapshot().state.cup).toBe(false)
    f.update(2500)
    expect(f.snapshot().state).toEqual({ cup: true, watered: false, music: false, borrowed: false })
    expect(onComplete).toHaveBeenCalledWith('coffee', 'self')
    f.destroy()
    const restored = createTownFacilities(scene, anchors, { storageKey: 'town:test' })
    expect(restored.snapshot().state.cup).toBe(true)
    restored.destroy()
  })
  it('cancels on departure, restores actor pose, and releases the reservation', () => {
    const f = createTownFacilities(scene, anchors), a = actor()
    a.angle = 2
    f.activate('planter', a, 'self'); f.update(2000)
    expect(a.angle).not.toBe(2)
    a.x += 30; f.update(20)
    expect(a.angle).toBe(2)
    expect(f.isBusy('self')).toBe(false)
    expect(f.snapshot().state.watered).toBe(false)
    expect(f.reserve('planter', 'npc')).toBe(true)
    f.destroy()
  })
  it('allows concurrent different facilities and repeat toggles', () => {
    const f = createTownFacilities(scene, anchors)
    expect(f.activate('records', actor(), 'npc')).toBe(true)
    expect(f.activate('books', actor(), 'self')).toBe(true)
    f.update(6100)
    expect(f.snapshot().state).toMatchObject({ music: true, borrowed: true })
    expect(f.interactables().find(item => item.id === 'records')?.label).toBe('关闭唱片')
    expect(f.interactables().find(item => item.id === 'books')?.label).toBe('归还借阅的书')
    f.activate('records', actor(), 'npc'); f.activate('books', actor(), 'self'); f.update(6100)
    expect(f.snapshot().state).toMatchObject({ music: false, borrowed: false })
    expect(f.interactables().find(item => item.id === 'records')?.label).toBe('播放街角唱片')
    expect(f.interactables().find(item => item.id === 'books')?.label).toBe('借一本书看看')
    f.destroy()
  })
  it('destroy restores all actors and prevents new reservations', () => {
    const f = createTownFacilities(scene, anchors), a = actor()
    f.activate('planter', a, 'self'); f.update(1000); f.destroy()
    expect(a.angle).toBe(0)
    expect(f.reserve('coffee', 'self')).toBe(false)
    expect(f.snapshot().active).toHaveLength(0)
  })
  it('never autoplays persisted music and tolerates unavailable audio hardware', () => {
    const createAudio = vi.fn(function () { throw new Error('Audio unavailable') })
    vi.stubGlobal('AudioContext', createAudio)
    localStorage.setItem('town:audio', JSON.stringify({ music: true }))
    const f = createTownFacilities(scene, anchors, { storageKey: 'town:audio' })
    f.update(1000)
    expect(createAudio).not.toHaveBeenCalled()
    expect(() => f.unlockAudio()).not.toThrow()
    expect(createAudio).toHaveBeenCalledOnce()
    expect(f.snapshot().state.music).toBe(true)
    expect(() => { f.setMuted(true); f.setMuted(false); f.destroy() }).not.toThrow()
  })

  it('refreshes the owner reservation while walking and expires abandoned reservations', () => {
    const f = createTownFacilities(scene, anchors)
    f.reserve('coffee', 'self'); f.update(25000)
    expect(f.reserve('coffee', 'self')).toBe(true)
    f.update(10000)
    expect(f.reserve('coffee', 'npc')).toBe(false)
    f.update(21000)
    expect(f.reserve('coffee', 'npc')).toBe(true)
    f.destroy()
  })
  it('keeps only the latest twelve completed actions in ephemeral audit history', () => {
    const f = createTownFacilities(scene, anchors, { storageKey: 'town:history' })
    for (let i = 0; i < 14; i++) { f.activate('coffee', actor(), 'npc-' + i); f.update(5500) }
    expect(f.snapshot().recentCompleted).toHaveLength(12)
    expect(f.snapshot().recentCompleted[0]).toEqual({ id: 'coffee', actorId: 'npc-2', atMs: 16500 })
    expect(localStorage.getItem('town:history')).not.toContain('actorId')
    f.destroy()
    const fresh = createTownFacilities(scene, anchors, { storageKey: 'town:history' })
    expect(fresh.snapshot().recentCompleted).toEqual([])
    fresh.destroy()
  })

  it('lets NPCs read on site and leaves the shared book on its shelf', () => {
    const f = createTownFacilities(scene, anchors, { playerId: 'real-player' })
    expect(f.activate('books', actor(), 'resident-mei')).toBe(true)
    f.update(6100)
    expect(f.snapshot().state.borrowed).toBe(false)
    expect(f.snapshot().recentCompleted).toEqual([{ id: 'books', actorId: 'resident-mei', atMs: 6100 }])
    f.destroy()
  })
  it('prevents an NPC from returning the player book and permits its actual owner to return it', () => {
    const f = createTownFacilities(scene, anchors, { playerId: 'real-player' })
    expect(f.activate('books', actor(), 'real-player')).toBe(true)
    f.update(6100)
    expect(f.snapshot().state.borrowed).toBe(true)
    expect(f.reserve('books', 'resident-mei')).toBe(false)
    expect(f.activate('books', actor(), 'resident-mei')).toBe(false)
    f.update(6100)
    expect(f.snapshot().state.borrowed).toBe(true)
    expect(f.activate('books', actor(), 'real-player')).toBe(true)
    f.update(6100)
    expect(f.snapshot().state.borrowed).toBe(false)
    expect(f.reserve('books', 'resident-mei')).toBe(true)
    f.destroy()
  })

})
