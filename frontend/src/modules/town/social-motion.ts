import { canStand, type CollisionWorld, type Point } from './collision'
import { findPath } from './pathfinding'

export const SOCIAL_DISTANCE = 44
/** Choose a reachable place around a person, never their occupied feet position. */
export function socialApproach(from: Point, person: Point, world: CollisionWorld): Point | null {
  const angle = Math.atan2(from.y - person.y, from.x - person.x)
  const candidates: { point: Point; length: number }[] = []
  for (const offset of [0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2, Math.PI, 3 * Math.PI / 4, -3 * Math.PI / 4]) {
    const point = { x: person.x + Math.cos(angle + offset) * SOCIAL_DISTANCE, y: person.y + Math.sin(angle + offset) * SOCIAL_DISTANCE }
    if (!canStand(point, world)) continue
    const path = findPath(from, point, world)
    if (!path) continue
    let previous = from, length = 0
    for (const p of path) { length += Math.hypot(p.x - previous.x, p.y - previous.y); previous = p }
    candidates.push({ point, length })
  }
  return candidates.sort((a, b) => a.length - b.length)[0]?.point ?? null
}
export type SocialPhase = 'settle' | 'greet' | 'reply' | 'leave' | 'done'
export function socialPhase(elapsed: number, turnMs = 1300): SocialPhase {
  return elapsed < 350 ? 'settle' : elapsed < 350 + turnMs ? 'greet' : elapsed < 550 + turnMs * 2 ? 'reply' : elapsed < 950 + turnMs * 2 ? 'leave' : 'done'
}
/** Cancellation invalidates every later phase, including responses not yet shown. */
export function createSocialTimeline(startedAt: number, turnMs = 1300) {
  let cancelled = false, previous: SocialPhase | null = null
  return {
    until: startedAt + 950 + turnMs * 2,
    cancel: () => { cancelled = true },
    advance(now: number): SocialPhase | null {
      if (cancelled) return null
      const next = socialPhase(Math.max(0, now - startedAt), turnMs)
      if (next === previous) return null
      previous = next
      return next
    },
  }
}

/** A small same-character lift preparation, never the overhead lifting frames. */
export function socialGestureFrame(textureWidth: number, textureHeight: number, direction: 'right' | 'up' | 'left' | 'down', elapsedMs: number): number | null {
  if (elapsedMs < 0 || elapsedMs >= 600) return null
  const columns = Math.floor(textureWidth / 32), rows = Math.floor(textureHeight / 64)
  const offset = { right: 0, up: 14, left: 28, down: 42 }[direction]
  if (rows < 12 || columns < offset + 5) return null
  const step = [0, 2, 4, 2, 0][Math.min(4, Math.floor(elapsedMs / 120))]!
  return 11 * columns + offset + step
}
