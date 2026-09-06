import { canStand, type CollisionWorld, type Point } from './collision'

/** A segment must stay on the same walkable floor, including between its endpoints. */
export function clearSegment(from: Point, to: Point, world: CollisionWorld): boolean {
  const steps = Math.max(1, Math.ceil(Math.hypot(to.x - from.x, to.y - from.y) / 4))
  for (let i = 0; i <= steps; i++) {
    if (!canStand({ x: from.x + (to.x - from.x) * i / steps, y: from.y + (to.y - from.y) * i / steps }, world)) return false
  }
  return true
}

/** Bounded A* on the town's 16px navigation grid. Null means unreachable, never "arrived". */
export function findPath(from: Point, to: Point, world: CollisionWorld): Point[] | null {
  if (!canStand(from, world) || !canStand(to, world)) return null
  if (clearSegment(from, to, world)) return [to]
  const step = 16
  type Node = { x: number; y: number; cost: number; score: number; parent?: Node }
  const key = (p: Point) => `${p.x},${p.y}`
  const heuristic = (p: Point) => Math.hypot(to.x - p.x, to.y - p.y)
  const open: Node[] = []
  const costs = new Map<string, number>()
  for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
    const point = { x: Math.round(from.x / step) * step + dx * step, y: Math.round(from.y / step) * step + dy * step }
    if (!clearSegment(from, point, world)) continue
    const cost = Math.hypot(point.x - from.x, point.y - from.y)
    open.push({ ...point, cost, score: cost + heuristic(point) })
    costs.set(key(point), cost)
  }
  let visited = 0
  while (open.length && visited++ < 16000) {
    open.sort((a, b) => b.score - a.score)
    const current = open.pop()!
    if (current.cost > costs.get(key(current))!) continue
    if (heuristic(current) < step * 2 && clearSegment(current, to, world)) {
      const path: Point[] = [to]
      for (let node: Node | undefined = current; node; node = node.parent) path.unshift({ x: node.x, y: node.y })
      const simplified: Point[] = []
      let anchor = from
      for (let i = 0; i < path.length;) {
        let next = i
        while (next + 1 < path.length && clearSegment(anchor, path[next + 1]!, world)) next++
        anchor = path[next]!
        simplified.push(anchor)
        i = next + 1
      }
      return simplified
    }
    for (const [dx, dy] of [[1,0],[-1,0],[0,1],[0,-1],[1,1],[-1,1],[1,-1],[-1,-1]]) {
      const point = { x: current.x + dx! * step, y: current.y + dy! * step }
      const cost = current.cost + Math.hypot(dx!, dy!) * step
      if (cost >= (costs.get(key(point)) ?? Infinity) || !clearSegment(current, point, world)) continue
      costs.set(key(point), cost)
      open.push({ ...point, cost, score: cost + heuristic(point), parent: current })
    }
  }
  return null
}
