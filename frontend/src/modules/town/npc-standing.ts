import type { Point } from './collision'

/** Stable, staggered café standing places: no hashing collisions or crowded door threshold. */
export function cafeStandingPoint(code: string, peerCodes: string[], centerX: number, streetY: number): Point {
  const index = Math.max(0, [...new Set(peerCodes)].sort().indexOf(code))
  const positions = [[-106, -8], [-32, 8], [63, -12], [140, 5], [-70, 30], [92, 33], [-162, 16], [190, 26]] as const
  const [dx, dy] = positions[index % positions.length]!
  return { x: centerX + dx, y: streetY + dy + Math.floor(index / positions.length) * 58 }
}
