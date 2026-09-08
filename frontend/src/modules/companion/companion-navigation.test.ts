import { expect, it, vi } from 'vitest'
vi.mock('phaser', () => ({ default: { Scene: class {} } }))
import { companionPath, COMPANION_COLLISION } from './companion-navigation'
import { residentPosition } from './companion-scene'
import { canStand } from '../../shared/scene/collision'
import { clearSegment } from '../../shared/scene/pathfinding'

it('every life destination is reachable through doors without crossing furniture', () => {
  const entrance = { x: 535, y: 396 }
  for (const place of ['home', 'cafe', 'garden', 'street']) {
    for (const activity of ['study', 'garden', 'rest', 'sleep', 'create', 'drink']) {
      for (let index = 0; index < 5; index++) {
        const destination = residentPosition(place, index, activity)
        const label = `${place}/${activity}/${index}`
        expect(canStand(destination, COMPANION_COLLISION), label).toBe(true)
        const path = companionPath(entrance, destination)
        expect(path.length, label).toBeGreaterThan(0)
        expect(path.at(-1), label).toEqual(destination)
        let previous = entrance
        for (const waypoint of path) {
          expect(clearSegment(previous, waypoint, COMPANION_COLLISION), label).toBe(true)
          previous = waypoint
        }
      }
    }
  }
})

it('changing desks inside the cafe does not send a resident out into the street', () => {
  const from = residentPosition('cafe', 0, 'study')
  const to = residentPosition('cafe', 2, 'study')
  const path = companionPath(from, to)
  expect(path.length).toBeGreaterThan(0)
  expect(path.every(point => point.y < 330)).toBe(true)
})

it('every TownPlaces positionId (backend two-layer place model) lands on standable, reachable ground', () => {
  const entrance = { x: 535, y: 396 }
  const positionIds = [
    'street-bench', 'cafe-worktable', 'cafe-window-seat', 'garden-bench', 'garden-plot',
    'home-owner-bed', 'home-student-bed', 'home-artist-bed', 'home-gardener-bed', 'home-self-bed',
  ]
  for (const positionId of positionIds) {
    // Exercise every occupant slot the position offers, not just the first.
    for (let occupant = 0; occupant < 4; occupant++) {
      const destination = residentPosition(positionId, occupant, '', '', positionId, occupant)
      const label = `${positionId}/${occupant}`
      expect(canStand(destination, COMPANION_COLLISION), label).toBe(true)
      const path = companionPath(entrance, destination)
      expect(path.length, label).toBeGreaterThan(0)
      expect(path.at(-1), label).toEqual(destination)
    }
  }
})

it('each of the five residents\' own home positions is a distinct spot, not the old shared bed', () => {
  const beds = ['home-owner-bed', 'home-student-bed', 'home-artist-bed', 'home-gardener-bed', 'home-self-bed']
    .map(id => residentPosition(id, 0, 'sleep', '', id))
  expect(new Set(beds.map(p => `${p.x}:${p.y}`)).size).toBe(5)
})
