import { EventEmitter } from 'node:events'
import type Phaser from 'phaser'
import { describe, expect, it, vi } from 'vitest'
const created = vi.hoisted(() => [] as any[])
vi.mock('../companion-visual', () => ({ createCompanionVisual: vi.fn((_scene, _pet, point) => {
  const sprite = Object.assign(new EventEmitter(), { ...point, active: true, setInteractive() { return this }, setPosition(x: number, y: number) { this.x = x; this.y = y; return this } })
  const visual = { sprite, update: vi.fn(), destroy: vi.fn(() => { sprite.active = false; sprite.removeAllListeners() }) }
  created.push(visual); return visual
}) }))
import { createTownCompanion } from './companion'
import type { CollisionWorld } from '../collision'
const pet = { publicId: 'a', speciesCode: 'CAT', name: '团子', breed: 'tabby', furColor: 'orange' }
function fixture() {
  let active = true
  const player = { x: 100, y: 100 }
  const line = {
    points: [] as { x: number; y: number }[], drawn: false,
    clear: vi.fn(() => { line.points = []; line.drawn = false; return line }),
    lineStyle: vi.fn(() => line), setDepth: vi.fn(() => line), beginPath: () => line,
    moveTo: (x: number, y: number) => { line.points.push({ x, y }); return line },
    lineTo: (x: number, y: number) => { line.points.push({ x, y }); return line },
    strokePath: () => { line.drawn = true; return line }, destroy: vi.fn(),
  }
  const input = Object.assign(new EventEmitter(), { keyboard: new EventEmitter() })
  const scene = { events: new EventEmitter(), input, add: { graphics: () => line }, sys: { isActive: () => active }, game: { canvas: {} } }
  const world: CollisionWorld = { walkable: [{ x: 0, y: 0, width: 1000, height: 500 }], obstacles: [] }
  const onInteract = vi.fn(), onApproach = vi.fn(), onModeChange = vi.fn()
  const controller = createTownCompanion(scene as unknown as Phaser.Scene, { player: () => player, world: () => world, park: () => ({ x: 50, y: 50, width: 160, height: 160 }), onInteract, onApproach, onModeChange })
  return { scene, line, world, player, controller, onInteract, onApproach, onModeChange, sleep: () => { active = false; scene.events.emit('sleep') }, wake: () => { active = true } }
}
describe('town companion lifecycle', () => {
  it('keeps one selected animal, replaces it on switch and removes it at home/empty', () => {
    const f = fixture(); f.controller.setState({ pet, mode: 'following' }); const original = created.at(-1)
    f.controller.setState({ pet: { ...pet, name: '新名' }, mode: 'following' }); expect(created.at(-1)).toBe(original)
    f.controller.setState({ pet: { ...pet, publicId: 'b' }, mode: 'following' }); expect(original.destroy).toHaveBeenCalledTimes(1)
    const switched = created.at(-1); f.controller.setState({ pet: null, mode: 'following' })
    expect(switched.destroy).toHaveBeenCalledTimes(1); expect(f.controller.snapshot()).toMatchObject({ petId: null, position: null, mode: 'home' })
    f.controller.setState({ pet, mode: 'home' }); expect(f.controller.snapshot().position).toBeNull()
    f.controller.destroy(); expect(f.scene.events.listenerCount('shutdown')).toBe(0)
  })
  it('preserves outdoor position while sleeping, resumes and emits recall once', () => {
    const f = fixture(); f.controller.setState({ pet, mode: 'roaming' })
    const before = f.controller.snapshot().position
    f.sleep(); f.player.x = 400; f.controller.update(1000)
    expect(f.controller.snapshot().position).toEqual(before); expect(f.onModeChange).not.toHaveBeenCalled()
    f.wake(); f.controller.update(16); f.controller.update(16)
    expect(f.onModeChange).toHaveBeenCalledExactlyOnceWith('following')
    f.scene.events.emit('shutdown'); f.controller.update(16)
    expect(f.controller.snapshot().position).toBeNull()
  })
  it('freezes distant pet until reachable contact and supports cancellation without late interaction', () => {
    const f = fixture(); f.controller.setState({ pet, mode: 'following' }); f.player.x = 400
    const before = f.controller.snapshot().position!
    expect(f.controller.interact()).toBe(true); expect(f.onApproach).toHaveBeenCalledWith(before); expect(f.onInteract).not.toHaveBeenCalled()
    f.controller.update(16); expect(f.controller.snapshot().position).toEqual(before)
    f.controller.cancelInteraction(); f.player.x = before.x; f.controller.update(16)
    expect(f.onInteract).not.toHaveBeenCalled()
    f.controller.interact(); expect(f.onInteract).toHaveBeenCalledExactlyOnceWith(pet)
    f.controller.destroy(); expect(f.controller.interact()).toBe(false)
  })
  it('ignores DOM-origin presses and clears pointer listeners at teardown', () => {
    const f = fixture(); f.controller.setState({ pet, mode: 'following' }); const visual = created.at(-1)
    visual.sprite.emit('pointerdown', { event: { target: {} } }); visual.sprite.emit('pointerup', { event: { target: f.scene.game.canvas } })
    expect(f.onInteract).not.toHaveBeenCalled()
    visual.sprite.emit('pointerdown', { event: { target: f.scene.game.canvas } }); visual.sprite.emit('pointerup', { event: { target: f.scene.game.canvas } })
    expect(f.onInteract).toHaveBeenCalledTimes(1)
    f.controller.destroy(); expect(visual.sprite.listenerCount('pointerup')).toBe(0)
  })

  it('draws a fine slack cord only for nearby following pets with a clear path', () => {
    const f = fixture(); f.controller.setState({ pet, mode: 'following' })
    expect(f.line.drawn).toBe(true)
    expect(f.line.lineStyle).toHaveBeenCalledWith(1, expect.any(Number), .7)
    const first = f.line.points[0]!, last = f.line.points.at(-1)!, middle = f.line.points[6]!
    expect(first).toEqual({ x: 93, y: 73 })
    expect(last).toEqual({ x: 70, y: 90 })
    expect(middle.y).toBeGreaterThan((first.y + last.y) / 2)
    f.player.x = 400; f.controller.update(0); expect(f.line.drawn).toBe(false)
    f.player.x = 100; f.world.obstacles.push({ x: 84, y: 98, width: 2, height: 4 })
    f.controller.update(0); expect(f.line.drawn).toBe(false)
    f.world.obstacles = []; f.controller.update(0); expect(f.line.drawn).toBe(true)
    f.controller.setState({ pet, mode: 'roaming' }); expect(f.line.drawn).toBe(false)
    f.controller.setState({ pet, mode: 'following' }); expect(f.line.drawn).toBe(true)
    f.sleep(); expect(f.line.drawn).toBe(false)
    f.wake(); f.controller.update(0); expect(f.line.drawn).toBe(true)
    f.controller.setState({ pet, mode: 'home' }); expect(f.line.drawn).toBe(false)
  })
  it.each(['ground', 'w', 'a', 's', 'd', 'Escape', 'sleep'])('cancels pending contact on %s without firing later', trigger => {
    const f = fixture(); f.controller.setState({ pet, mode: 'following' }); f.player.x = 400
    f.controller.interact(); expect(f.controller.snapshot().pendingInteraction).toBe(true)
    if (trigger === 'ground') f.scene.input.emit('pointerdown', { event: { target: f.scene.game.canvas } })
    else if (trigger === 'sleep') f.sleep()
    else f.scene.input.keyboard.emit('keydown', { key: trigger })
    expect(f.controller.snapshot().pendingInteraction).toBe(false)
    f.wake(); f.player.x = f.controller.snapshot().position!.x; f.controller.update(16)
    expect(f.onInteract).not.toHaveBeenCalled()
    f.controller.destroy()
  })
  it('ignores DOM typing and non-canvas presses and releases all added resources once', () => {
    const f = fixture(); f.controller.setState({ pet, mode: 'following' }); f.player.x = 400; f.controller.interact()
    f.scene.input.emit('pointerdown', { event: { target: {} } })
    f.scene.input.keyboard.emit('keydown', { key: 'w', target: { tagName: 'INPUT' } })
    f.scene.input.keyboard.emit('keydown', { key: 'a', ctrlKey: true })
    expect(f.controller.snapshot().pendingInteraction).toBe(true)
    expect(f.scene.input.listenerCount('pointerdown')).toBe(1)
    expect(f.scene.input.keyboard.listenerCount('keydown')).toBe(1)
    f.controller.destroy(); f.scene.events.emit('shutdown'); f.controller.destroy()
    expect(f.line.destroy).toHaveBeenCalledTimes(1)
    expect(f.scene.input.listenerCount('pointerdown')).toBe(0)
    expect(f.scene.input.keyboard.listenerCount('keydown')).toBe(0)
    expect(f.scene.events.listenerCount('sleep')).toBe(0)
    expect(f.scene.events.listenerCount('shutdown')).toBe(0)
  })
})
