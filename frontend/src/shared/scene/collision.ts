/**
 * Pure geometry for the self avatar's free 8-directional movement (task: 八向自由移动 + 碰撞).
 * No Phaser import so it can be unit tested directly; town.engine.ts builds a CollisionWorld
 * from its own layout constants (plot/academy footprints) and calls into these helpers every frame.
 */

export type Point = { x: number; y: number }
export type Rect = { x: number; y: number; width: number; height: number }

/** Walkable ground (street/sidewalk/plaza rectangles) minus obstacles (building footprints). A
 * point only counts as standable when it is inside the walkable union and outside every obstacle. */
export type CollisionWorld = {
  walkable: Rect[]
  obstacles: Rect[]
}

function rectContains(rect: Rect, point: Point): boolean {
  return point.x >= rect.x && point.x <= rect.x + rect.width && point.y >= rect.y && point.y <= rect.y + rect.height
}

function containedByAny(rects: Rect[], point: Point): boolean {
  return rects.some(rect => rectContains(rect, point))
}

/** Nearest point to `point` that lies inside `rect` (identity if already inside). */
function clampToRect(point: Point, rect: Rect): Point {
  return {
    x: Math.min(Math.max(point.x, rect.x), rect.x + rect.width),
    y: Math.min(Math.max(point.y, rect.y), rect.y + rect.height),
  }
}

/** rectContains treats both edges as inside, so landing exactly on a wall still reads as "inside
 * it" — nudge a hair past the edge instead of onto it. */
const EDGE_EPSILON = 1

/** Pushes a point that is (assumed) inside `rect` out through whichever edge is closest. */
function pushOutOfRect(point: Point, rect: Rect): Point {
  const left = point.x - rect.x
  const right = rect.x + rect.width - point.x
  const top = point.y - rect.y
  const bottom = rect.y + rect.height - point.y
  const min = Math.min(left, right, top, bottom)
  if (min === left) return { x: rect.x - EDGE_EPSILON, y: point.y }
  if (min === right) return { x: rect.x + rect.width + EDGE_EPSILON, y: point.y }
  if (min === top) return { x: point.x, y: rect.y - EDGE_EPSILON }
  return { x: point.x, y: rect.y + rect.height + EDGE_EPSILON }
}

function distance(a: Point, b: Point): number {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

/** Whether `point` is on walkable ground and clear of every obstacle. */
export function canStand(point: Point, world: CollisionWorld): boolean {
  return containedByAny(world.walkable, point) && !containedByAny(world.obstacles, point)
}

/**
 * Resolves one movement attempt from `from` to `to`. When `to` is blocked, slides along the
 * obstacle by retrying with only the x move or only the y move applied (whichever still stands),
 * so walking diagonally into a wall keeps the free axis moving instead of stopping dead. Falls
 * back to `from` when neither axis helps (e.g. a corner on both sides).
 */
export function resolveMove(from: Point, to: Point, world: CollisionWorld): Point {
  if (canStand(to, world)) return to
  const slideX = { x: to.x, y: from.y }
  if (canStand(slideX, world)) return slideX
  const slideY = { x: from.x, y: to.y }
  if (canStand(slideY, world)) return slideY
  return from
}

/** Closest standable point to `point` — used to correct a click-to-walk destination that landed
 * inside a building or off the walkable map. */
export function nearestStandable(point: Point, world: CollisionWorld): Point {
  if (canStand(point, world)) return point
  let best: Point | null = null
  let bestDistance = Infinity
  for (const rect of world.walkable) {
    const candidate = clampToRect(point, rect)
    const pushed = world.obstacles.reduce(
      (current, obstacle) => (rectContains(obstacle, current) ? pushOutOfRect(current, obstacle) : current),
      candidate,
    )
    const resolved = containedByAny(world.walkable, pushed) ? pushed : candidate
    const measured = distance(point, resolved)
    if (measured < bestDistance) { bestDistance = measured; best = resolved }
  }
  return best ?? point
}

export type BuildingFootprint = { x: number; width: number; topY: number }

export type FurnitureObstacle = {
  x: number
  y: number
  width: number
  height: number
}

export type TownWorldConfig = {
  /** Paved street/sidewalk span the self avatar may walk (already excludes the far edges). */
  groundMinX: number
  groundMaxX: number
  /** Row the buildings' ground floor sits on; obstacles and the street band are anchored to it. */
  baselineY: number
  /** How far north of the baseline an ordinary shop front's sidewalk apron reaches. */
  apron: number
  /** Southern edge of the walkable street/sidewalk/park band. */
  bottomY: number
  /** Extra walkable square in front of the academy/yard, reaching further north than the apron. */
  plaza: { minX: number; maxX: number; topY: number }
  /** One footprint per building (plots + the academy); carved out of the walkable area as obstacles. */
  buildings: BuildingFootprint[]
  /** 街道家具碰撞矩形（长椅/邮筒/花坛/垃圾桶等），追加到 obstacles */
  furniture?: FurnitureObstacle[]
}

/** Builds the walkable-ground model from town.engine.ts's own layout constants. */
export function buildTownCollisionWorld(config: TownWorldConfig): CollisionWorld {
  const streetBand: Rect = {
    x: config.groundMinX,
    y: config.baselineY - config.apron,
    width: config.groundMaxX - config.groundMinX,
    height: config.bottomY - (config.baselineY - config.apron),
  }
  const plaza: Rect = {
    x: config.plaza.minX,
    y: config.plaza.topY,
    width: config.plaza.maxX - config.plaza.minX,
    height: config.baselineY - config.apron - config.plaza.topY,
  }
  const obstacles: Rect[] = config.buildings.map(building => ({
    x: building.x,
    y: building.topY,
    width: building.width,
    height: config.baselineY - building.topY,
  }))
  // 追加街道家具碰撞
  if (config.furniture) {
    obstacles.push(...config.furniture)
  }
  return { walkable: [streetBand, plaza], obstacles }
}
