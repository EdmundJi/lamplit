import type { Point, Rect } from './collision'

/** The east lane turns north, then west into a garden behind the cafés. */
export function gardenDistrict(worldWidth: number) {
  const branchX = worldWidth - 160
  const park = { x: branchX - 240, y: 464 }
  const walkable: Rect[] = [
    { x: branchX - 64, y: 400, width: 128, height: 600 },
    { x: branchX - 464, y: 352, width: 528, height: 224 },
  ]
  return { branchX, park, walkable }
}

/** Constant progress follows distance along a corner, never cuts across a building. */
export function pointAlongRoute(points: Point[], progress: number): Point {
  if (!points.length) return { x: 0, y: 0 }
  if (points.length === 1) return { ...points[0]! }
  const lengths = points.slice(1).map((point, i) => Math.hypot(point.x - points[i]!.x, point.y - points[i]!.y))
  let distance = lengths.reduce((sum, length) => sum + length, 0) * Math.min(1, Math.max(0, progress))
  for (let i = 0; i < lengths.length; i++) {
    const length = lengths[i]!
    if (distance <= length && length > 0) {
      const a = points[i]!, b = points[i + 1]!, t = distance / length
      return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t }
    }
    distance -= length
  }
  return { ...points.at(-1)! }
}
