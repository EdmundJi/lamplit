import type Phaser from 'phaser'
import { canStand, nearestStandable, type CollisionWorld, type Point } from './collision'
import { findPath } from './pathfinding'
import { roomNavigation } from './interior-interaction'
import type { RoomMapData } from './map-loader'

/** The same companion stays in the room; greeting and resting never swap its breed or sheet. */
export function createInteriorCompanion(scene: Phaser.Scene, options: {
  room: RoomMapData
  sprite: Phaser.GameObjects.Sprite
  framePrefix: string
  home: Point
  player: () => Phaser.GameObjects.Sprite | null
  isResting: () => boolean
}) {
  const { sprite, room, framePrefix } = options
  const world: CollisionWorld = roomNavigation(room)
  // Older maps authored only the largest blockers. Pets also respect the feet of actual
  // furniture, including the little coffee table, without treating floor rugs as walls.
  for (const piece of room.furniture) {
    if ((piece.depth !== undefined && piece.depth <= 2) || /^(rug|doormat|painting|wall_|frame_|board_|notice_|curtain)/.test(piece.frame)) continue
    const frame = scene.textures.getFrame('interior', piece.frame)
    if (!frame) continue
    const width = piece.displayWidth ?? frame.realWidth
    const height = piece.displayHeight ?? frame.realHeight
    const footWidth = Math.max(12, width * .68)
    const footHeight = Math.min(15, height * .24)
    world.obstacles.push({ x: piece.x - footWidth / 2 - 5, y: piece.y - footHeight, width: footWidth + 10, height: footHeight + 5 })
  }
  const home = nearestStandable(options.home, world)
  sprite.setPosition(home.x, home.y)
  let path: Point[] = []
  let phase: 'settling' | 'greeting' | 'together' | 'returning' | 'home' = 'settling'
  let age = 0
  let pauseUntil = 650
  let nextPlanAt = 650
  let previousResting = false
  let pausedForInteraction = 0
  let destroyed = false
  const shadow = scene.add.ellipse(sprite.x, sprite.y - 2, 28, 8, 0x4d493b, .14).setDepth(sprite.y - 1)

  function routeTo(target: Point): boolean {
    if (!canStand(target, world)) return false
    const route = findPath({ x: sprite.x, y: sprite.y }, target, world)
    if (!route) return false
    path = route
    return true
  }

  function approachPlayer(resting: boolean): boolean {
    const player = options.player()
    if (!player) return false
    // Keep the doorway, walking feet, and the front of the seat free. The pet comes to a
    // reachable side of the player rather than sliding through a chair to a fixed marker.
    const offsets = resting ? [[-38, 23], [38, 23], [-44, 8], [44, 8]] : [[-34, 7], [34, 7], [-30, -16], [30, -16]]
    const candidates = offsets.map(([dx, dy]) => ({ x: player.x + dx!, y: player.y + dy! }))
    candidates.sort((a, b) => Math.hypot(a.x - sprite.x, a.y - sprite.y) - Math.hypot(b.x - sprite.x, b.y - sprite.y))
    return candidates.some(routeTo)
  }

  function idle() {
    sprite.play(`${framePrefix}-idle`, true)
    const player = options.player()
    if (player && Math.abs(player.x - sprite.x) > 8) sprite.setFlipX(player.x < sprite.x)
  }

  function update(_time: number, deltaMs: number) {
    if (destroyed || !sprite.active) return
    age += Math.min(deltaMs, 100)
    if (age < pausedForInteraction) { idle(); return }
    const player = options.player()
    if (!player) return
    const resting = options.isResting()
    if (resting !== previousResting) {
      previousResting = resting
      path = []
      if (resting && approachPlayer(true)) { phase = 'together'; pauseUntil = age; nextPlanAt = age + 3000 }
      else { phase = 'returning'; routeTo(home); nextPlanAt = age + 5000 }
    }
    if (phase === 'settling' && age >= nextPlanAt) {
      if (approachPlayer(false)) { phase = 'greeting'; pauseUntil = age }
      else nextPlanAt = age + 900
    }
    if (phase === 'greeting' && !path.length && age > pauseUntil + 1600) {
      phase = 'together'; pauseUntil = age + 2500; nextPlanAt = age + 2500
    }
    if (phase === 'together' && !path.length && age >= nextPlanAt) {
      nextPlanAt = age + 3500
      const distance = Math.hypot(player.x - sprite.x, player.y - sprite.y)
      if (resting) {
        if (distance > 62) approachPlayer(true)
      } else if (distance > 80 || age > 11000) {
        phase = 'returning'; routeTo(home)
      }
    }
    if (phase === 'returning' && !path.length) { phase = 'home'; pauseUntil = age + 6000 }
    if (phase === 'home' && resting && age > pauseUntil && approachPlayer(true)) phase = 'together'

    const next = path[0]
    if (next && age >= pauseUntil) {
      const dx = next.x - sprite.x, dy = next.y - sprite.y
      const distance = Math.hypot(dx, dy)
      const step = Math.min(distance, Math.min(deltaMs, 80) / 1000 * (phase === 'greeting' ? 88 : 56))
      if (distance < 1) {
        path.shift()
        if (!path.length) { idle(); pauseUntil = age }
      } else {
        const destination = { x: sprite.x + dx / distance * step, y: sprite.y + dy / distance * step }
        if (canStand(destination, world)) {
          sprite.setPosition(destination.x, destination.y)
          if (Math.abs(dx) > 1) sprite.setFlipX(dx < 0)
          sprite.play(`${framePrefix}-walk`, true)
        } else { path = []; idle() }
      }
    } else idle()
    sprite.setDepth(sprite.y + 1)
    shadow.setPosition(sprite.x, sprite.y - 2).setDepth(sprite.y - 1)
  }
  scene.events.on('update', update)
  const destroy = () => {
    if (destroyed) return
    destroyed = true
    scene.events.off('update', update)
    shadow.destroy()
  }
  scene.events.once('shutdown', destroy)
  return {
    /** Pet-panel interactions pause locomotion, so it does not walk away mid-contact. */
    pause(ms = 2500) { pausedForInteraction = age + ms; path = []; idle() },
    destroy,
  }
}
