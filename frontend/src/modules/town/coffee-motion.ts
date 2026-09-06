import type Phaser from 'phaser'

export const COFFEE_DURATION = 5400
export const COFFEE_CONTACT_RADIUS = 10
export type CoffeePhase = 'settle' | 'reach' | 'lift' | 'sip' | 'breathe' | 'lower' | 'replace' | 'release'

/** Frame-local hand anchors measured against the existing premade character's lift strip.
 * row 11, left columns 32/33/34; col35 lifts above the mouth and is deliberately excluded. */
const hands = { 4: { x: 6, y: 54 }, 5: { x: 7, y: 48 }, 6: { x: 11, y: 44 } } as const
export function coffeePoseAt(ms: number): { phase: CoffeePhase; step: number | null; held: boolean; tilt: number } {
  if (ms < 300) return { phase: 'settle', step: null, held: false, tilt: 0 }
  if (ms < 750) return { phase: 'reach', step: Math.min(4, Math.floor((ms-300)/90)), held: false, tilt: 0 }
  if (ms < 1050) return { phase: 'lift', step: ms < 850 ? 4 : ms < 950 ? 5 : 6, held: true, tilt: 0 }
  if (ms < 2000) return { phase: 'sip', step: 6, held: true, tilt: -10 }
  if (ms < 2500) return { phase: 'breathe', step: 5, held: true, tilt: 0 }
  if (ms < 3400) return { phase: 'sip', step: 6, held: true, tilt: -10 }
  if (ms < 4000) return { phase: 'lower', step: ms < 3650 ? 5 : 4, held: true, tilt: 0 }
  if (ms < 4400) return { phase: 'replace', step: 4, held: true, tilt: 0 }
  if (ms < 5100) return { phase: 'release', step: Math.max(0, 3-Math.floor((ms-4400)/175)), held: false, tilt: 0 }
  return { phase: 'settle', step: null, held: false, tilt: 0 }
}

export function coffeeHandCup(actor: { x: number; y: number }, step: number, scale = 1, left = true) {
  const hand = hands[step as keyof typeof hands] ?? hands[4]
  const dx = hand.x - 16 - 4
  return { x: Math.round(actor.x + (left ? dx : -dx) * scale), y: Math.round(actor.y + (hand.y - 64 - 2) * scale) }
}

/** Uses the same character texture throughout. The cup follows the native hand, never a drawn arm. */
export function createCoffeeMotion(scene: Phaser.Scene, actor: Phaser.GameObjects.Sprite, table: { x: number; y: number }) {
  const frame = actor.frame?.name ?? 0
  const animationKey = actor.anims?.currentAnim?.key
  const wasPlaying = actor.anims?.isPlaying === true
  const source = actor.texture?.getSourceImage?.() as HTMLImageElement | undefined
  const columns = source?.width ? Math.floor(source.width / 32) : 0
  const native = columns >= 42 && (source?.height ?? 0) >= 12 * 64
  const scale = typeof actor.scaleX === 'number' ? Math.abs(actor.scaleX) : 1
  const left = actor.x >= table.x
  const cup = scene.add.image(0, 0, 'interior', 'coffee_cup').setScale(.5 * scale).setOrigin(.5).setVisible(false)
  actor.anims?.stop?.()
  let current = coffeePoseAt(0)
  let position = coffeeHandCup(actor, 4, scale, left)
  let destroyed = false
  function update(ms: number) {
    if (destroyed) return
    current = coffeePoseAt(ms)
    if (native) actor.setFrame(current.step === null ? columns + (left ? 12 : 0) : columns * 11 + (left ? 28 : 0) + current.step)
    position = coffeeHandCup(actor, current.step ?? 4, scale, left)
    cup.setPosition(position.x, position.y).setDepth(actor.y + 4).setAngle(left ? current.tilt : -current.tilt).setVisible(current.held)
  }
  return {
    update,
    holdsCup: () => current.held,
    snapshot: () => ({ phase: current.phase, frame: actor.frame?.name, cup: { ...position }, held: current.held, nativePose: native }),
    destroy: () => {
      if (destroyed) return
      destroyed = true; cup.destroy()
      if (actor.active) {
        actor.setFrame(frame)
        if (wasPlaying && animationKey) actor.play(animationKey, true)
      }
    },
  }
}
