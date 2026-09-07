import type { CollisionWorld, Point } from '../town/collision'
import { findPath } from '../town/pathfinding'
/** Feet navigation mirrors the cutaway floors, open doorways and actual furniture footprint. */
export const COMPANION_COLLISION: CollisionWorld = {
  walkable: [
    { x: 88, y: 180, width: 240, height: 149 }, { x: 188, y: 319, width: 32, height: 83 },
    { x: 392, y: 183, width: 336, height: 146 }, { x: 519, y: 319, width: 32, height: 83 },
    { x: 40, y: 364, width: 884, height: 98 }, { x: 746, y: 230, width: 184, height: 240 },
  ],
  obstacles: [
    ...[108, 148].map(x => ({ x: x - 14, y: 138, width: 28, height: 84 })),
    ...[252, 296].map(x => ({ x: x - 14, y: 124, width: 28, height: 84 })),
    { x: 184, y: 142, width: 7, height: 78 },
    { x: 95, y: 255, width: 66, height: 36 }, { x: 172, y: 269, width: 35, height: 17 },
    { x: 245, y: 245, width: 59, height: 43 }, { x: 258, y: 300, width: 25, height: 20 },
    { x: 411, y: 185, width: 58, height: 69 }, { x: 499, y: 185, width: 58, height: 69 }, { x: 587, y: 185, width: 58, height: 69 },
    { x: 428, y: 262, width: 24, height: 18 }, { x: 516, y: 262, width: 24, height: 18 }, { x: 604, y: 262, width: 24, height: 18 },
    { x: 798, y: 248, width: 49, height: 40 }, { x: 859, y: 248, width: 49, height: 40 },
    { x: 798, y: 328, width: 49, height: 40 }, { x: 859, y: 328, width: 49, height: 40 },
  ],
}
export function companionPath(from: Point, to: Point) { return findPath(from, to, COMPANION_COLLISION) ?? [] }
