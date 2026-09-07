import { canStand, type CollisionWorld, type Point, type Rect } from './collision'
import { findPath } from './pathfinding'
import { RUN_SPEED } from './walkers'

export type CompanionMode = 'home' | 'following' | 'roaming'
export const COMPANION_FOLLOW_DISTANCE = 30
export const COMPANION_RUN_SPEED = RUN_SPEED + 64
export const inCompanionPark = (point: Point, park: Rect) => point.x >= park.x && point.y >= park.y && point.x <= park.x + park.width && point.y <= park.y + park.height

/** Exact slab intersection also catches thin walls between sampled pathfinding points. */
function interval(from: Point, to: Point, rect: Rect): [number, number] | null {
  let low = 0, high = 1
  for (const [a, d, min, max] of [[from.x, to.x - from.x, rect.x, rect.x + rect.width], [from.y, to.y - from.y, rect.y, rect.y + rect.height]]) {
    if (Math.abs(d!) < 1e-10) { if (a! < min! || a! > max!) return null; continue }
    const t1 = (min! - a!) / d!, t2 = (max! - a!) / d!
    low = Math.max(low, Math.min(t1, t2)); high = Math.min(high, Math.max(t1, t2))
    if (low > high) return null
  }
  return [low, high]
}
export function companionClearSegment(from: Point, to: Point, world: CollisionWorld): boolean {
  if (world.obstacles.some(rect => interval(from, to, rect))) return false
  const covered = world.walkable.map(rect => interval(from, to, rect)).filter((v): v is [number, number] => v !== null).sort((a, b) => a[0] - b[0])
  let end = 0
  for (const [start, finish] of covered) {
    if (start > end + 1e-9) return false
    end = Math.max(end, finish)
    if (end >= 1) return true
  }
  return false
}
function parkWorld(world: CollisionWorld, park: Rect): CollisionWorld {
  return { obstacles: world.obstacles, walkable: world.walkable.map(rect => {
    const x = Math.max(rect.x, park.x), y = Math.max(rect.y, park.y)
    return { x, y, width: Math.min(rect.x + rect.width, park.x + park.width) - x, height: Math.min(rect.y + rect.height, park.y + park.height) - y }
  }).filter(rect => rect.width > 0 && rect.height > 0) }
}
function route(from: Point, to: Point, world: CollisionWorld): Point[] | null {
  const valid = (result: Point[] | null): result is Point[] => {
    if (!result) return false
    let previous = from
    for (const point of result) { if (!companionClearSegment(previous, point, world)) return false; previous = point }
    return true
  }
  const result = findPath(from, to, world)
  if (valid(result)) return result
  // The shared grid samples every 4px. Pad blockers on retry so even subpixel fences
  // participate in its route search, then validate the entire resulting polyline exactly.
  const padded = { ...world, obstacles: world.obstacles.map(r => ({ x: r.x - 3, y: r.y - 3, width: r.width + 6, height: r.height + 6 })) }
  const retry = findPath(from, to, padded)
  return valid(retry) ? retry : null
}
/** Only initial creation may choose a nearby spawn. Ongoing movement never teleports. */
export function companionSpawn(player: Point, world: CollisionWorld): Point | null {
  for (const radius of [30, 18, 8, 0]) for (const angle of [Math.PI, Math.PI / 2, 0, -Math.PI / 2]) {
    const p = { x: player.x + Math.cos(angle) * radius, y: player.y + Math.sin(angle) * radius }
    if (canStand(p, world) && companionClearSegment(player, p, world)) return p
  }
  return null
}
export function createCompanionMotion(initial: Point, random: () => number = Math.random) {
  let position = { ...initial }, mode: CompanionMode = 'following', path: Point[] = []
  let age = 0, nextPlan = 0, plannedTarget: Point | null = null, restUntil = 0
  return {
    setMode(next: CompanionMode) { if (next !== mode) { mode = next; path = []; nextPlan = 0; plannedTarget = null; restUntil = 0 } },
    snapshot: () => ({ position: { ...position }, mode, path: path.map(p => ({ ...p })) }),
    update(delta: number, player: Point, world: CollisionWorld, park: Rect) {
      const dt = Number.isFinite(delta) ? Math.max(0, Math.min(delta, 100)) : 0
      age += dt
      let modeChanged = false
      if (mode === 'roaming' && !inCompanionPark(player, park)) { mode = 'following'; path = []; nextPlan = 0; modeChanged = true }
      const before = { ...position }
      if (mode === 'home' || !dt || !canStand(position, world)) return { ...this.snapshot(), moving: false, dx: 0, dy: 0, modeChanged }
      // Until both have entered, continue following. Free movement is clipped to the park union.
      const roaming = mode === 'roaming' && inCompanionPark(position, park)
      const navigation = roaming ? parkWorld(world, park) : world
      if (roaming) {
        if (!path.length && age >= restUntil && age >= nextPlan) {
          nextPlan = age + 900
          for (let attempt = 0; attempt < 12; attempt++) {
            const target = { x: park.x + park.width * (.08 + random() * .84), y: park.y + park.height * (.08 + random() * .84) }
            const candidate = route(position, target, navigation)
            if (candidate) { path = candidate; break }
          }
        }
      } else {
        const distance = Math.hypot(player.x - position.x, player.y - position.y)
        if (distance <= COMPANION_FOLLOW_DISTANCE && companionClearSegment(position, player, world)) path = []
        else if (age >= nextPlan && (!plannedTarget || Math.hypot(player.x - plannedTarget.x, player.y - plannedTarget.y) > 12 || !path.length)) {
          nextPlan = age + 160
          plannedTarget = { ...player }
          const candidate = route(position, player, world)
          if (candidate) {
            // Trim only the last segment. Corners remain intact and the animal stops beside us.
            const last = candidate[candidate.length - 1]!, previous = candidate[candidate.length - 2] ?? position
            const length = Math.hypot(last.x - previous.x, last.y - previous.y)
            if (length > COMPANION_FOLLOW_DISTANCE) candidate[candidate.length - 1] = { x: last.x + (previous.x - last.x) * COMPANION_FOLLOW_DISTANCE / length, y: last.y + (previous.y - last.y) * COMPANION_FOLLOW_DISTANCE / length }
            path = candidate
          } else path = []
        }
      }
      let budget = dt / 1000 * (roaming ? 48 : COMPANION_RUN_SPEED)
      while (path.length && budget > 0) {
        const target = path[0]!, distance = Math.hypot(target.x - position.x, target.y - position.y)
        if (distance < .001) { path.shift(); continue }
        const step = Math.min(distance, budget), next = { x: position.x + (target.x - position.x) * step / distance, y: position.y + (target.y - position.y) * step / distance }
        if (!companionClearSegment(position, next, navigation)) { path = []; nextPlan = age + 160; break }
        position = next; budget -= step
        if (step === distance) { path.shift(); break } // Render each corner before advancing the next leg.
      }
      if (roaming && !path.length && (position.x !== before.x || position.y !== before.y)) restUntil = age + 1300 + random() * 2200
      const dx = position.x - before.x, dy = position.y - before.y
      return { ...this.snapshot(), moving: Math.hypot(dx, dy) > .01, dx, dy, modeChanged }
    },
  }
}
