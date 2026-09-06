import { describe, expect, it, vi, afterEach } from 'vitest'
import { createDefaultController, type ControllerContext } from './interior.scene'
import { collidesAt, type RoomMapData } from './map-loader'

function harness(collisions: RoomMapData['collisions'] = []) {
  const callbacks = new Map<string, Function>()
  const canvas = document.createElement('canvas')
  const keys: Record<string, { isDown: boolean }> = {}
  const input = {
    on: (name: string, fn: Function) => callbacks.set(name, fn),
    off: (name: string) => callbacks.delete(name),
    keyboard: {
      createCursorKeys: () => Object.fromEntries(['left', 'right', 'up', 'down'].map(key => [key, keys[key] = { isDown: false }])),
      addKey: (key: string) => keys[key] = { isDown: false },
    },
  }
  const sprite = { x: 64, y: 128, scene: {}, anims: {}, play: vi.fn(), setDepth: vi.fn() }
  const room: RoomMapData = {
    id: 'test', title: 'Test', tileSize: 32, rows: 10, cols: 10, backgroundColor: '#000',
    spawn: { x: 64, y: 128 }, layers: { floor: [], walls: [] }, collisions, doors: [], furniture: [], slots: [], seats: [],
  }
  const scene = { input, game: { canvas }, events: { once: vi.fn(), off: vi.fn() }, cameras: { main: { getWorldPoint: (x: number, y: number) => ({ x, y }) } } }
  const onDoor = vi.fn()
  const controller = createDefaultController({ scene, sprite, room, sheet: 'char_1', speed: 180, onDoor,
    isBlocked: (x: number, y: number) => collidesAt(room, x, y, 14, 10),
  } as unknown as ControllerContext)
  const tick = (count = 100) => { for (let i = 0; i < count; i++) controller.update(16) }
  const click = (x: number, y: number, target: EventTarget = canvas, over: unknown[] = []) => {
    callbacks.get('pointerdown')?.({ x, y, event: { target } })
    callbacks.get('pointerup')?.({ x, y, event: { target } }, over)
  }
  return { controller, sprite, keys, room, callbacks, tick, click, onDoor, canvas }
}

afterEach(() => document.body.replaceChildren())

describe('interior controller interactions', () => {
  it('fires only after arriving, once, and never for an unreachable destination', () => {
    const h = harness([{ x: 180, y: 0, w: 20, h: 320 }])
    const arrived = vi.fn()
    expect(h.controller.moveTo?.({ x: 120, y: 128 }, arrived)).toBe(true)
    expect(arrived).not.toHaveBeenCalled()
    h.tick()
    expect(h.sprite.x).toBeCloseTo(120)
    expect(arrived).toHaveBeenCalledTimes(1)
    expect(h.controller.moveTo?.({ x: 240, y: 128 }, arrived)).toBe(false)
    h.tick()
    expect(arrived).toHaveBeenCalledTimes(1)
  })

  it.each(['keyboard', 'ground', 'cancel', 'dialog', 'destroy'])('%s cancels a pending furniture action', method => {
    const h = harness()
    const arrived = vi.fn()
    h.controller.moveTo?.({ x: 240, y: 128 }, arrived)
    if (method === 'keyboard') h.keys.A!.isDown = true
    if (method === 'ground') h.click(64, 240)
    if (method === 'cancel') expect(h.controller.cancel?.()).toBe(true)
    if (method === 'dialog') document.body.innerHTML = '<div role="dialog"></div>'
    if (method === 'destroy') h.controller.destroy?.()
    h.tick()
    document.body.replaceChildren()
    h.keys.A!.isDown = false
    h.tick()
    expect(arrived).not.toHaveBeenCalled()
  })

  it('resting keeps its existing seat pose and returns to the legal approach point before walking', () => {
    const h = harness()
    h.controller.sitAt?.({ x: 64, y: 110 })
    h.tick()
    expect(h.controller.isResting?.()).toBe(true)
    expect(h.sprite.play).toHaveBeenLastCalledWith('char_1-seat-idle', true)
    h.keys.D!.isDown = true
    h.controller.update(16)
    expect(h.controller.isResting?.()).toBe(false)
    expect(h.sprite.y).toBe(128)
    expect(h.sprite.x).toBeGreaterThan(64)
  })

  it('does not interpret clicks on DOM controls or interactive furniture as floor destinations', () => {
    const h = harness()
    h.click(240, 200, document.createElement('button'))
    h.tick()
    expect(h.sprite.x).toBe(64)
    h.click(240, 200, h.canvas, [{ input: { enabled: true } }])
    h.tick()
    expect(h.sprite.x).toBe(64)
  })

  it('prevents a press begun on DOM from turning into a world release', () => {
    const h = harness()
    h.callbacks.get('pointerdown')?.({ event: { target: document.createElement('button') } })
    h.callbacks.get('pointerup')?.({ x: 200, y: 128, event: { target: h.canvas } }, [])
    h.tick()
    expect(h.sprite.x).toBe(64)
  })

  it('removes controller listeners exactly once and ignores subsequent updates', () => {
    const h = harness()
    h.controller.destroy?.()
    h.controller.destroy?.()
    expect(h.callbacks.size).toBe(0)
    h.keys.D!.isDown = true
    h.tick()
    expect(h.sprite.x).toBe(64)
  })

  it('a long frame cannot tunnel through a narrow obstacle', () => {
    const h = harness([{ x: 88, y: 100, w: 2, h: 60 }])
    h.keys.D!.isDown = true
    h.controller.update(2000)
    expect(h.sprite.x).toBeLessThanOrEqual(81)
  })
})
