import { EventEmitter } from 'node:events'
import { readFileSync } from 'node:fs'
import type Phaser from 'phaser'
import { describe, expect, it, vi } from 'vitest'
import { canStand } from './collision'
import { furnitureApproach, roomNavigation } from './interior-interaction'
import { homeObjectDefinitions, createHomeObjects, type HomeObjectState } from './home-objects'
import { defaultInteractionHit, parseRoomMap, type RoomRect } from './map-loader'

const room = parseRoomMap(JSON.parse(readFileSync('public/assets/town/maps/home-living-room.json', 'utf8')))
const overlaps = (a: RoomRect, b: RoomRect) => a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y

function fixture() {
  const events = new EventEmitter()
  const input = Object.assign(new EventEmitter(), { keyboard: new EventEmitter() })
  const canvas = document.createElement('canvas')
  const zones: ReturnType<typeof graphic>[] = []
  function graphic() {
    const value = new EventEmitter() as EventEmitter & Record<string, any>
    for (const method of ['setOrigin', 'setDepth', 'setVisible', 'setText', 'setPosition', 'setInteractive', 'clear', 'fillStyle', 'fillRect', 'lineStyle', 'beginPath', 'moveTo', 'lineTo', 'strokePath', 'destroy']) value[method] = vi.fn(() => value)
    return value
  }
  const scene = { events, input, game: { canvas }, add: { graphics: graphic, text: graphic, zone: () => { const zone = graphic(); zones.push(zone); return zone } } }
  let player = { ...room.spawn }
  let state: HomeObjectState = { hasPet: true, outing: false, unread: 2 }
  let blocked = false
  const arrivals: (() => void)[] = []
  const onInteract = vi.fn()
  const api = createHomeObjects(scene as unknown as Phaser.Scene, {
    room, player: () => player, state: () => state, inputBlocked: () => blocked, onInteract,
    approach(piece, action) {
      player = furnitureApproach(room, player, piece)!
      arrivals.push(action)
    },
  })
  const tick = (count = 5) => { for (let i = 0; i < count; i++) events.emit('update', 0, 100) }
  return { api, events, input, canvas, zones, onInteract, arrivals, tick,
    move: () => { player = { x: player.x + 10, y: player.y } },
    state: (next: HomeObjectState) => { state = next }, block: () => { blocked = true },
  }
}

describe('home object layout', () => {
  it('keeps every approach reachable and every new hot area clear of the door and other furniture interactions', () => {
    const objects = homeObjectDefinitions(room)
    expect(objects.map(object => object.id)).toEqual(['journal', 'mailbox', 'leash'])
    for (const object of objects) {
      const target = furnitureApproach(room, room.spawn, object.piece)
      expect(target, object.id).not.toBeNull()
      expect(canStand(target!, roomNavigation(room))).toBe(true)
      for (const door of room.doors) expect(overlaps(object.hit, door.rect), object.id).toBe(false)
      for (const piece of room.furniture.filter(piece => piece.interactive && piece.id !== 'desk')) {
        expect(overlaps(object.hit, piece.interactive!.hit ?? defaultInteractionHit(piece, room.tileSize)), `${object.id}/${piece.id}`).toBe(false)
      }
      for (const other of objects.filter(other => other !== object)) expect(overlaps(object.hit, other.hit)).toBe(false)
    }
  })
  it('creates nothing in other rooms', () => {
    expect(homeObjectDefinitions({ ...room, id: 'cafe-interior' })).toEqual([])
  })
})

describe('home object use lifecycle', () => {
  it('waits for arrival and a short action, and ignores duplicate arrivals', () => {
    const f = fixture()
    f.api.interact('journal')
    f.tick(8)
    expect(f.onInteract).not.toHaveBeenCalled()
    f.arrivals[0]!()
    f.tick(3)
    expect(f.onInteract).not.toHaveBeenCalled()
    f.arrivals[0]!()
    f.tick(2)
    expect(f.onInteract).toHaveBeenCalledExactlyOnceWith('home.read-journal', 'home-journal')
    f.arrivals[0]!()
    f.tick()
    expect(f.onInteract).toHaveBeenCalledTimes(1)
  })
  it('new interactions invalidate previous pending arrival callbacks', () => {
    const f = fixture()
    f.api.interact('journal')
    f.api.interact('mailbox')
    f.arrivals[0]!()
    f.tick()
    expect(f.onInteract).not.toHaveBeenCalled()
    f.arrivals[1]!()
    f.tick()
    expect(f.onInteract).toHaveBeenCalledExactlyOnceWith('home.open-mailbox', 'home-mailbox')
  })
  it.each(['move', 'block', 'escape', 'press', 'cancel'] as const)('cancels the short action on %s', (reason) => {
    const f = fixture()
    f.api.interact('journal')
    f.arrivals[0]!()
    if (reason === 'move') f.move()
    if (reason === 'block') f.block()
    if (reason === 'escape') f.input.keyboard.emit('keydown', new KeyboardEvent('keydown', { key: 'Escape' }))
    if (reason === 'press') f.input.emit('pointerdown', { event: { target: f.canvas } })
    if (reason === 'cancel') expect(f.api.cancel()).toBe(true)
    f.tick()
    expect(f.onInteract).not.toHaveBeenCalled()
    expect(f.api.snapshot().active).toBeNull()
  })
  it('rejects DOM-origin and DOM-blocked pointer sequences', () => {
    const f = fixture()
    f.input.emit('pointerdown', { event: { target: document.createElement('button') } })
    f.zones[0]!.emit('pointerup', { event: { target: f.canvas } })
    expect(f.arrivals).toHaveLength(0)
    f.input.emit('pointerdown', { event: { target: f.canvas } })
    f.zones[0]!.emit('pointerup', { event: { target: document.createElement('button') } })
    expect(f.arrivals).toHaveLength(0)
    f.block()
    f.input.emit('pointerdown', { event: { target: f.canvas } })
    f.zones[0]!.emit('pointerup', { event: { target: f.canvas } })
    expect(f.arrivals).toHaveLength(0)
  })
  it('accepts one canvas-origin object press', () => {
    const f = fixture()
    f.input.emit('pointerdown', { event: { target: f.canvas } })
    f.zones[1]!.emit('pointerup', { event: { target: f.canvas } })
    f.input.emit('pointerup')
    f.arrivals[0]!()
    f.tick()
    expect(f.onInteract).toHaveBeenCalledExactlyOnceWith('home.open-mailbox', 'home-mailbox')
  })
  it('reflects real mail/outing/empty state and cancels stale leash actions', () => {
    const f = fixture()
    f.api.interact('leash')
    f.arrivals[0]!()
    f.state({ hasPet: false, outing: false, unread: 0 })
    f.tick()
    expect(f.onInteract).not.toHaveBeenCalled()
    expect(f.api.snapshot().objects.find(item => item.id === 'leash')?.label).toBe('选择伙伴')
    expect(f.api.snapshot().objects.find(item => item.id === 'mailbox')?.label).toBe('看看信箱')
    f.state({ hasPet: true, outing: true, unread: 1 })
    f.api.interact('leash')
    f.arrivals[1]!()
    f.tick()
    expect(f.onInteract).toHaveBeenCalledExactlyOnceWith('home.stow-leash', 'home-leash')
  })
  it('shutdown invalidates late callbacks and removes all owned listeners', () => {
    const f = fixture()
    f.api.interact('journal')
    f.events.emit('shutdown')
    f.arrivals[0]!()
    f.tick()
    f.api.destroy()
    expect(f.onInteract).not.toHaveBeenCalled()
    expect(f.api.interact('mailbox')).toBe(false)
    expect(f.events.listenerCount('update')).toBe(0)
    expect(f.events.listenerCount('shutdown')).toBe(0)
    expect(f.input.listenerCount('pointerdown')).toBe(0)
    expect(f.input.listenerCount('pointerup')).toBe(0)
    expect(f.input.keyboard.listenerCount('keydown')).toBe(0)
  })
})
