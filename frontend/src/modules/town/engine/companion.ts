import type Phaser from 'phaser'
import type { CollisionWorld, Point, Rect } from '../collision'
import { companionClearSegment, companionSpawn, createCompanionMotion, inCompanionPark, type CompanionMode } from '../companion-motion'
import { createCompanionVisual, type CompanionPet } from '../companion-visual'
export type { CompanionPet } from '../companion-visual'
export type { CompanionMode } from '../companion-motion'

export function createTownCompanion(scene: Phaser.Scene, options: {
  player: () => Point | null
  world: () => CollisionWorld
  park: () => Rect
  onInteract?: (pet: CompanionPet) => void
  onApproach?: (point: Point) => void
  onModeChange?: (mode: 'following' | 'roaming') => void
}) {
  let pet: CompanionPet | null = null, mode: CompanionMode = 'home'
  let visual: ReturnType<typeof createCompanionVisual> | null = null
  let motion: ReturnType<typeof createCompanionMotion> | null = null
  let pendingInteraction = false, waiting = 0, pressOnCanvas = false, destroyed = false, moving = false
  const leash = scene.add.graphics()
  const cancelInteraction = () => { pendingInteraction = false; waiting = 0 }
  function drawLeash() {
    leash.clear()
    const player = options.player()
    if (destroyed || mode !== 'following' || !visual || !player || !scene.sys.isActive()) return
    const companion = visual.sprite
    const distance = Math.hypot(player.x - companion.x, player.y - companion.y)
    if (distance > 100 || !companionClearSegment(player, companion, options.world())) return
    const hand = { x: player.x + (companion.x < player.x ? -7 : 7), y: player.y - 27 }
    const collar = { x: companion.x, y: companion.y - 10 }
    const slack = Math.min(10, Math.max(4, distance * .15))
    leash.lineStyle(1, 0x826447, .7).setDepth(Math.max(player.y, companion.y) - .5)
    leash.beginPath().moveTo(hand.x, hand.y)
    // A lightly sagging cord, sampled explicitly so it works with the shared Phaser renderer.
    for (let i = 1; i <= 12; i++) {
      const t = i / 12
      leash.lineTo(hand.x + (collar.x - hand.x) * t, hand.y + (collar.y - hand.y) * t + 4 * t * (1 - t) * slack)
    }
    leash.strokePath()
  }
  function cancelFromPointer(pointer: Phaser.Input.Pointer) {
    if (pointer?.event?.target === scene.game.canvas) cancelInteraction()
  }
  function cancelFromKey(event: KeyboardEvent) {
    const target = event.target as HTMLElement | null
    if (target?.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target?.tagName ?? '')) return
    if (event.metaKey || event.ctrlKey || event.altKey) return
    if (['w', 'a', 's', 'd', 'arrowup', 'arrowdown', 'arrowleft', 'arrowright', 'escape'].includes(event.key.toLowerCase())) cancelInteraction()
  }
  function sleep() { cancelInteraction(); pressOnCanvas = false; leash.clear() }
  function release() {
    cancelInteraction(); leash.clear(); visual?.destroy(); visual = null; motion = null; moving = false
  }
  function interact(): boolean {
    const player = options.player()
    if (destroyed || !scene.sys.isActive() || !pet || !visual || !player || mode === 'home') return false
    if (Math.hypot(player.x - visual.sprite.x, player.y - visual.sprite.y) <= 70) {
      cancelInteraction(); options.onInteract?.(pet); return true
    } else if (options.onApproach) {
      pendingInteraction = true; waiting = 10000
      visual.update({ moving: false, dx: 0, dy: 0 })
      options.onApproach({ x: visual.sprite.x, y: visual.sprite.y }); return true
    }
    return false
  }
  function ensure() {
    if (destroyed || !pet || mode === 'home' || visual || !scene.sys.isActive()) return
    const player = options.player()
    if (!player) return
    const position = companionSpawn(player, options.world())
    if (!position) return
    motion = createCompanionMotion(position); motion.setMode(mode)
    visual = createCompanionVisual(scene, pet, position)
    visual.sprite.setInteractive({ useHandCursor: true })
    visual.sprite.on('pointerdown', (pointer: Phaser.Input.Pointer) => { pressOnCanvas = pointer?.event?.target === scene.game.canvas })
    visual.sprite.on('pointerup', (pointer: Phaser.Input.Pointer, _x: number, _y: number, event?: Phaser.Types.Input.EventData) => {
      const accepted = pressOnCanvas && pointer?.event?.target === scene.game.canvas
      pressOnCanvas = false
      if (!accepted || !scene.sys.isActive()) return
      event?.stopPropagation(); interact()
    })
  }
  const destroy = () => {
    if (destroyed) return
    destroyed = true; release(); pet = null; mode = 'home'
    leash.destroy()
    scene.input.off('pointerdown', cancelFromPointer)
    scene.input.keyboard?.off('keydown', cancelFromKey)
    scene.events.off('sleep', sleep)
    scene.events.off('shutdown', destroy)
  }
  scene.events.once('shutdown', destroy)
  scene.events.on('sleep', sleep)
  scene.input.on('pointerdown', cancelFromPointer)
  scene.input.keyboard?.on('keydown', cancelFromKey)
  return {
    setState(state: { pet: CompanionPet | null; mode: CompanionMode }) {
      if (destroyed) return
      const changed = pet?.publicId !== state.pet?.publicId || pet?.speciesCode !== state.pet?.speciesCode || pet?.breed !== state.pet?.breed || pet?.furColor !== state.pet?.furColor
      if (changed || state.mode === 'home' || !state.pet) release()
      if (mode !== state.mode) cancelInteraction()
      pet = state.pet ? { ...state.pet } : null; mode = state.pet ? state.mode : 'home'
      motion?.setMode(mode); ensure(); drawLeash()
    },
    update(delta: number) {
      if (destroyed || !scene.sys.isActive()) return
      ensure()
      const player = options.player()
      if (!visual || !motion || !player) { leash.clear(); return }
      if (mode === 'roaming' && !inCompanionPark(player, options.park())) {
        mode = 'following'; motion.setMode(mode); options.onModeChange?.(mode)
      }
      if (pendingInteraction) {
        waiting -= Math.max(0, Math.min(Number.isFinite(delta) ? delta : 0, 100))
        visual.update({ moving: false, dx: 0, dy: 0 }); moving = false
        if (Math.hypot(player.x - visual.sprite.x, player.y - visual.sprite.y) <= 70) interact()
        else if (waiting <= 0) cancelInteraction()
        drawLeash()
        return
      }
      const result = motion.update(delta, player, options.world(), options.park())
      mode = result.mode; moving = result.moving
      visual.sprite.setPosition(result.position.x, result.position.y)
      visual.update({ ...result, delta })
      if (result.modeChanged && mode !== 'home') options.onModeChange?.(mode)
      drawLeash()
    },
    interact,
    /** Cancel pending contact when the player chooses another destination or opens a room. */
    cancelInteraction,
    /** Explicit scene recovery only; ordinary follow movement never calls this. */
    resetPosition() { release(); ensure(); drawLeash() },
    snapshot: () => ({ petId: pet?.publicId ?? null, mode, position: motion?.snapshot().position ?? null, moving, pendingInteraction }),
    destroy,
  }
}
