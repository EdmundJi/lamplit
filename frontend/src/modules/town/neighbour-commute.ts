import { findPath } from './pathfinding'
import { canStand, nearestStandable, type CollisionWorld, type Point } from './collision'
import { pointAlongRoute } from './town-spaces'

const paths = new WeakMap<CollisionWorld, Map<string, Point[]>>()

/** Keep scheduled home journeys on the new lanes instead of cutting through houses. */
export function neighbourCommute(world: CollisionWorld, from: Point, to: Point, progress: number): { point: Point; ahead: Point } {
  let cache = paths.get(world)
  if (!cache) { cache = new Map(); paths.set(world, cache) }
  const key = `${from.x},${from.y}:${to.x},${to.y}`
  let route = cache.get(key)
  if (!route) {
    const start = canStand(from, world) ? from : nearestStandable(from, world)
    const end = canStand(to, world) ? to : nearestStandable(to, world)
    const waypoints = findPath(start, end, world)
    route = waypoints ? [start, ...waypoints] : [start]
    cache.set(key, route)
  }
  return { point: pointAlongRoute(route, progress), ahead: pointAlongRoute(route, Math.min(1, progress + .001)) }
}
