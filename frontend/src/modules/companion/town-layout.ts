import layout from './town-layout.json'

/**
 * Where every building, home and walkway sits: one data file shared with the map generator
 * (scripts/build-town-map.py), so the tiles it paints, the rooms the scene draws and the collision
 * the pathfinder walks can never be three hand-kept copies again.
 *
 * Interiors were authored in absolute pixels before this file existed. Rather than retype every
 * table, chair and counter, each building's interior keeps those numbers and is moved as a whole:
 * `shift(building, x, y)` subtracts the building's original origin and adds its current one. With
 * the layout at its original values every shift is the identity.
 */
export type Rect = { x: number; y: number; w: number; h: number }
export type Building = Rect & { doorX?: number }
export type BuildingId = keyof typeof layout.buildings

export const TOWN_LAYOUT = layout as {
  tileSize: number
  world: { width: number; height: number }
  buildings: Record<BuildingId, Building>
  homes: Record<string, Rect>
  homeFrame: Rect
  walkways: Rect[]
  paths: Rect[]
}

/** The origins interiors were authored against. Never edit - move buildings in the JSON instead. */
const AUTHORED_ORIGINS: Record<BuildingId, { x: number; y: number }> = {
  cafe: { x: 384, y: 12 }, garden: { x: 1036, y: 192 }, street: { x: 32, y: 344 },
  academy: { x: 1268, y: 40 }, gym: { x: 1268, y: 480 }, board: { x: 1268, y: 300 }, shop: { x: 1580, y: 448 },
}

export function shift(building: BuildingId, x: number, y: number) {
  const from = AUTHORED_ORIGINS[building], to = TOWN_LAYOUT.buildings[building]
  return { x: x - from.x + to.x, y: y - from.y + to.y }
}
export function shiftX(building: BuildingId, x: number) { return shift(building, x, 0).x }
export function shiftY(building: BuildingId, y: number) { return shift(building, 0, y).y }
export function shiftRect<T extends { x: number; y: number }>(building: BuildingId, rect: T): T {
  return { ...rect, ...shift(building, rect.x, rect.y) }
}
