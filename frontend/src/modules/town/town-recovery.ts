import { canStand, nearestStandable, type CollisionWorld, type Point } from './collision'

/** Never return NaN, a wall, or an arbitrary (0,0) when restoring a saved avatar. */
export function safeTownPoint(world: CollisionWorld, preferred: Point | null, fallback: Point): Point | null {
  const finite = (p: Point | null): p is Point => !!p && Number.isFinite(p.x) && Number.isFinite(p.y)
  if (finite(preferred) && canStand(preferred, world)) return { ...preferred }
  if (finite(fallback) && canStand(fallback, world)) return { ...fallback }
  const candidate = nearestStandable(fallback, world)
  if (finite(candidate) && canStand(candidate, world)) return candidate
  let best: Point | null = null, distance = Infinity
  for (const r of world.walkable) {
    for (let y = r.y + 2; y < r.y + r.height; y += 8) {
      for (let x = r.x + 2; x < r.x + r.width; x += 8) {
        const point = { x, y }, d = Math.hypot(x - fallback.x, y - fallback.y)
        if (d < distance && canStand(point, world)) { best = point; distance = d }
      }
    }
  }
  return best
}
