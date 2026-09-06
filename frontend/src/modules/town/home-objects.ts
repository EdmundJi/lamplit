import type Phaser from 'phaser'
import type { Point } from './collision'
import { distanceToBox } from './interior-interaction'
import type { RoomFurniture, RoomMapData, RoomRect } from './map-loader'

export type HomeObjectId = 'journal' | 'mailbox' | 'leash'
export type HomeObjectState = { hasPet: boolean; outing: boolean; unread: number }
export type HomeObjectDefinition = {
  id: HomeObjectId
  piece: RoomFurniture
  hit: RoomRect
  x: number
  y: number
}
export type HomeObjectsOptions = {
  room: RoomMapData
  player: () => Point | null
  /** Use the room controller's legal furniture approach; invoke action only after arrival. */
  approach: (piece: RoomFurniture, action: () => void) => void
  onInteract: (actionId: string, id: string) => void
  state: () => HomeObjectState
  inputBlocked?: () => boolean
}

/** The desk's existing interactive wire must be omitted when these objects are installed. */
export function homeObjectDefinitions(room: RoomMapData): HomeObjectDefinition[] {
  if (room.id !== 'home-living-room') return []
  const desk = room.furniture.find(piece => piece.id === 'desk')
  const door = room.doors.find(item => item.id === 'front-door')
  const out: HomeObjectDefinition[] = []
  if (desk) {
    const hit = { x: desk.x - 22, y: desk.y - 50, w: 44, h: 38 }
    out.push({ id: 'journal', x: desk.x, y: desk.y - 33, hit,
      piece: { ...desk, id: 'home-journal', interactive: { actionId: 'home.read-journal', hit, label: '翻手账' } } })
  }
  if (door) {
    for (const [id, x] of [['mailbox', door.rect.x - 32], ['leash', door.rect.x + door.rect.w + 32]] as const) {
      const y = door.rect.y - 26
      const hit = { x: x - 18, y: y - 48, w: 36, h: 54 }
      out.push({ id, x, y, hit, piece: { id: `home-${id}`, frame: '', x, y,
        interactive: { actionId: id === 'mailbox' ? 'home.open-mailbox' : 'home.take-leash', hit } } })
    }
  }
  return out
}

function blockedByDom(): boolean {
  return typeof document !== 'undefined' && Boolean(document.querySelector('[role="dialog"], .resident-moment')
    || document.activeElement?.matches('input, textarea, select, [contenteditable="true"]'))
}

export function homeObjectAction(id: HomeObjectId, state: HomeObjectState): { actionId: string; label: string; working: string } {
  if (id === 'journal') return { actionId: 'home.read-journal', label: '翻手账', working: '翻开手账…' }
  if (id === 'mailbox') return { actionId: 'home.open-mailbox', label: state.unread > 0 ? '取信' : '看看信箱', working: '打开信箱…' }
  return state.outing
    ? { actionId: 'home.stow-leash', label: '收好牵引绳', working: '挂好牵引绳…' }
    : { actionId: 'home.take-leash', label: state.hasPet ? '拿绳出门' : '选择伙伴', working: state.hasPet ? '拿起牵引绳…' : '看看伙伴…' }
}

/** World objects own a cancellable short use action, never pet data, rewards or navigation. */
export function createHomeObjects(scene: Phaser.Scene, options: HomeObjectsOptions) {
  const definitions = homeObjectDefinitions(options.room)
  const blocked = options.inputBlocked ?? blockedByDom
  const visuals = new Map<HomeObjectId, Phaser.GameObjects.Graphics>()
  const zones: Phaser.GameObjects.Zone[] = []
  let destroyed = false
  let worldPress = false
  let generation = 0
  let pending: { id: HomeObjectId; generation: number; phase: 'approaching' | 'using'; elapsed: number; origin: Point | null; actionId: string; hasPet: boolean } | null = null
  let renderKey = ''
  const hint = definitions.length ? scene.add.text(0, 0, '', {
    fontFamily: 'system-ui, sans-serif', fontSize: '12px', color: '#fff4e8', backgroundColor: '#443b32',
    padding: { x: 7, y: 4 },
  }).setOrigin(.5, 1).setDepth(9900).setVisible(false) : null

  function cancel(): boolean {
    const hadPending = pending !== null
    generation += 1
    pending = null
    renderKey = ''
    return hadPending
  }

  function interact(id: HomeObjectId): boolean {
    if (destroyed || blocked() || !options.player()) return false
    const object = definitions.find(item => item.id === id)
    if (!object) return false
    cancel()
    const state = options.state()
    const ticket = generation
    const action = homeObjectAction(id, state)
    pending = { id, generation: ticket, phase: 'approaching', elapsed: 0, origin: null, actionId: action.actionId, hasPet: state.hasPet }
    const piece = { ...object.piece, interactive: { ...object.piece.interactive!, actionId: action.actionId } }
    options.approach(piece, () => {
      if (destroyed || blocked() || pending?.generation !== ticket || pending.phase !== 'approaching') return
      const player = options.player()
      if (!player || distanceToBox(player, object.hit) > 78) { cancel(); return }
      pending.phase = 'using'
      pending.origin = { x: player.x, y: player.y }
      renderKey = ''
    })
    return true
  }

  function draw(object: HomeObjectDefinition, state: HomeObjectState, working: boolean) {
    const g = visuals.get(object.id)!
    const { x, y } = object
    g.clear()
    const rect = (color: number, dx: number, dy: number, w: number, h: number) => g.fillStyle(color, 1).fillRect(x + dx, y + dy, w, h)
    if (object.id === 'journal') {
      // A small horizontal cloth-bound journal fits the authored 26px-wide desk surface.
      rect(0x453b35, -11, -6, 23, 16)
      rect(0x638775, -10, -7, 21, 14)
      rect(0xd1ba84, -8, -5, 2, 12)
      rect(0xf4e5bd, -5, -4, working ? 15 : 12, 9)
      rect(0xb69c76, -3, -1, 8, 1)
      rect(0xb69c76, -3, 2, 6, 1)
      rect(0xb45d4e, 5, 5, 3, 6)
      if (working) rect(0xfff2cd, -12, -4, 9, 10)
      return
    }
    // Entryway stands leave the middle of the doormat clear; muted wood matches the room.
    rect(0x5d4a3b, -12, 0, 24, 5)
    rect(0x967554, -3, -38, 6, 39)
    rect(0xc19a6b, -3, -38, 2, 37)
    if (object.id === 'mailbox') {
      rect(0x4a655d, -16, -37, 32, 26)
      rect(0x779082, -14, -35, 28, 21)
      rect(0x354a43, -11, -31, 22, 3)
      // A real unread count is represented by a visible envelope corner, never a fake badge.
      if (state.unread > 0 || working) {
        rect(0xf8e9c7, -7, -41, 17, 13)
        g.lineStyle(1, 0xbc9c73).beginPath().moveTo(x - 7, y - 41).lineTo(x + 1, y - 35).lineTo(x + 10, y - 41).strokePath()
      }
      rect(0x91a596, -13, -26, 26, 11)
      rect(0xd0b981, -2, -23, 4, 2)
      if (working) rect(0x536e61, -14, -15, 28, 6)
    } else {
      rect(0x745840, -12, -44, 24, 12)
      rect(0xba9165, -10, -42, 20, 7)
      g.lineStyle(3, 0xd5c3a0).beginPath().moveTo(x, y - 37).lineTo(x, y - 29).lineTo(x + 5, y - 29).lineTo(x + 5, y - 33).strokePath()
      if (!state.outing || working) {
        const lift = working ? 4 : 0
        g.lineStyle(3, 0x516f76).beginPath().moveTo(x + 3, y - 31 - lift).lineTo(x + 9, y - 17 - lift).lineTo(x + 8, y - 8 - lift).lineTo(x - 4, y - 8 - lift).lineTo(x - 7, y - 17 - lift).lineTo(x + 3, y - 31 - lift).strokePath()
        rect(0xbfae86, -5, -10 - lift, 4, 4)
      }
    }
  }

  for (const object of definitions) {
    visuals.set(object.id, scene.add.graphics().setDepth(object.id === 'journal' ? object.piece.y + 2 : object.y + 1))
    const box = object.hit
    const zone = scene.add.zone(box.x, box.y, box.w, box.h).setOrigin(0).setDepth(object.piece.y + 3).setInteractive({ useHandCursor: true })
    zone.on('pointerup', (pointer: Phaser.Input.Pointer) => {
      if (worldPress && pointer?.event?.target === scene.game.canvas && !blocked()) interact(object.id)
    })
    zones.push(zone)
  }

  function pointerDown(pointer: Phaser.Input.Pointer) {
    worldPress = pointer?.event?.target === scene.game.canvas && !blocked()
    // A fresh press also invalidates a previous arrival callback before navigation changes.
    cancel()
  }
  function pointerUp() { worldPress = false }
  function keyDown(event: KeyboardEvent) {
    if (event.defaultPrevented || blocked()) return
    if (['Escape', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'w', 'a', 's', 'd'].includes(event.key.length === 1 ? event.key.toLowerCase() : event.key)) cancel()
  }
  function update(_time: number, delta: number) {
    if (destroyed) return
    const state = options.state()
    const player = options.player()
    if (pending && (blocked() || !player || (pending.id === 'leash' && (homeObjectAction('leash', state).actionId !== pending.actionId || state.hasPet !== pending.hasPet)))) cancel()
    if (pending?.phase === 'using' && player) {
      if (!pending.origin || Math.hypot(player.x - pending.origin.x, player.y - pending.origin.y) > 4) cancel()
      else {
        pending.elapsed += Math.min(Math.max(0, delta), 100)
        if (pending.elapsed >= 420) {
          const finished = pending
          cancel()
          options.onInteract(finished.actionId, `home-${finished.id}`)
        }
      }
    }
    if (destroyed) return
    const key = `${state.outing}:${state.unread > 0}:${pending?.phase === 'using' ? pending.id : ''}`
    if (key !== renderKey) {
      renderKey = key
      for (const object of definitions) draw(object, state, pending?.phase === 'using' && pending.id === object.id)
    }
    hint?.setVisible(false)
    if (!player || blocked()) return
    const closest = definitions.map(object => ({ object, distance: distanceToBox(player, object.hit) }))
      .filter(item => item.distance < 68).sort((a, b) => a.distance - b.distance)[0]?.object
    if (!closest || !hint) return
    const action = homeObjectAction(closest.id, state)
    hint.setText(pending?.id === closest.id && pending.phase === 'using' ? action.working : action.label)
      .setPosition(closest.x, Math.max(110, Math.min(closest.hit.y - 10, player.y - 76))).setVisible(true)
  }
  function destroy() {
    if (destroyed) return
    destroyed = true
    cancel()
    scene.events.off('update', update)
    scene.events.off('shutdown', destroy)
    scene.input.off('pointerdown', pointerDown)
    scene.input.off('pointerup', pointerUp)
    scene.input.keyboard?.off('keydown', keyDown)
    hint?.destroy()
    zones.forEach(zone => zone.destroy())
    visuals.forEach(visual => visual.destroy())
  }
  if (definitions.length) {
    scene.input.on('pointerdown', pointerDown)
    scene.input.on('pointerup', pointerUp)
    scene.input.keyboard?.on('keydown', keyDown)
    scene.events.on('update', update)
    scene.events.once('shutdown', destroy)
    update(0, 0)
  }
  return {
    cancel, interact, destroy,
    snapshot: () => ({
      objects: definitions.map(object => ({ id: object.id, x: object.x, y: object.y, hit: { ...object.hit }, ...homeObjectAction(object.id, options.state()) })),
      active: pending ? { id: pending.id, phase: pending.phase, elapsed: pending.elapsed } : null,
      destroyed,
    }),
  }
}
