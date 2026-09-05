/**
 * Pure helpers for the self avatar's click-to-walk and the "passing greeting" behaviour
 * (task 6, docs/成长小镇-接口约定.md §4). Kept free of any Phaser import so they can be
 * unit tested directly; town.engine.ts wires them into the actual walker sprites.
 */

export type WalkDirection = 'left' | 'right'

/** Self-avatar movement speeds in px/s. Running is a burst mode: hold Shift on desktop, or
 * flip the 「奔跑」 toggle in the town HUD on touch. */
export const WALK_SPEED = 56
export const RUN_SPEED = 132
/** The walk cycle plays faster while running so the stride reads as a run, not a slide. */
export const RUN_ANIM_SCALE = 1.6

export function moveSpeed(running: boolean): number {
  return running ? RUN_SPEED : WALK_SPEED
}

/** Advance toward a target at `speed`, never overshooting it. */
export function stepToward(current: number, target: number, speed: number, deltaMs: number): number {
  const distance = target - current
  if (distance === 0) return current
  return current + Math.sign(distance) * Math.min(Math.abs(distance), (speed * deltaMs) / 1000)
}

export type Point = { x: number; y: number }

/** 2D counterpart of stepToward, for the self avatar's free-roam walk target (a click on the
 * ground, or an NPC to approach) — moves straight at `speed` px/s without overshooting. */
export function stepTowardPoint(current: Point, target: Point, speed: number, deltaMs: number): Point {
  const dx = target.x - current.x
  const dy = target.y - current.y
  const distance = Math.hypot(dx, dy)
  if (distance === 0) return current
  const travel = Math.min(distance, (speed * deltaMs) / 1000)
  const ratio = travel / distance
  return { x: current.x + dx * ratio, y: current.y + dy * ratio }
}

export type Direction4 = 'up' | 'down' | 'left' | 'right'

/** Turns raw WASD/arrow input (-1/0/1 per axis) into a movement step: normalized so diagonal
 * movement covers the same ground per second as a straight cardinal move, then scaled to `speed`. */
export function movementDelta(inputX: number, inputY: number, speed: number, deltaMs: number): Point {
  if (inputX === 0 && inputY === 0) return { x: 0, y: 0 }
  const length = Math.hypot(inputX, inputY)
  const distance = (speed * deltaMs) / 1000
  return { x: (inputX / length) * distance, y: (inputY / length) * distance }
}

/** Which of the 4 walk animations best matches a movement vector; keeps facing `fallback` when
 * the vector is zero (blocked, or no input) instead of snapping to a default. */
export function dominantDirection(dx: number, dy: number, fallback: Direction4): Direction4 {
  if (dx === 0 && dy === 0) return fallback
  return Math.abs(dx) >= Math.abs(dy) ? (dx < 0 ? 'left' : 'right') : (dy < 0 ? 'up' : 'down')
}

/** Keeps a walk target within the street's walkable span. */
export function clampWalkX(x: number, min: number, max: number): number {
  if (min > max) return min
  return Math.min(max, Math.max(min, x))
}

/** Order-independent key for a pair of walker ids, so a→b and b→a share one cooldown entry. */
export function pairKey(a: string, b: string): string {
  return a < b ? `${a}|${b}` : `${b}|${a}`
}

/** Walker-id pair -> epoch ms the greeting cooldown lasts until. */
export type GreetCooldowns = Map<string, number>

const GREET_COOLDOWN_MS = 30_000
const GREET_DISTANCE_PX = 24

export function canGreet(cooldowns: GreetCooldowns, a: string, b: string, now: number): boolean {
  const until = cooldowns.get(pairKey(a, b))
  return until === undefined || now >= until
}

export function registerGreet(cooldowns: GreetCooldowns, a: string, b: string, now: number, cooldownMs = GREET_COOLDOWN_MS): void {
  cooldowns.set(pairKey(a, b), now + cooldownMs)
}

/** True when two walkers moving in opposite directions are closing on each other rather than
 * moving apart (e.g. both walking away from a spot they already share). */
export function isPassingPair(ax: number, aDir: WalkDirection, bx: number, bDir: WalkDirection): boolean {
  if (aDir === bDir) return false
  const [leftDir, rightDir] = ax <= bx ? [aDir, bDir] : [bDir, aDir]
  return leftDir === 'right' && rightDir === 'left'
}

export type GreetCandidate = { id: string; x: number; dir: WalkDirection }

/** Whether two moving walkers should pause for a greeting right now: close enough, walking
 * toward each other, and not still cooling down from their last greeting. */
export function shouldGreet(a: GreetCandidate, b: GreetCandidate, cooldowns: GreetCooldowns, now: number, distancePx = GREET_DISTANCE_PX): boolean {
  if (a.id === b.id) return false
  if (Math.abs(a.x - b.x) > distancePx) return false
  if (!isPassingPair(a.x, a.dir, b.x, b.dir)) return false
  return canGreet(cooldowns, a.id, b.id, now)
}
