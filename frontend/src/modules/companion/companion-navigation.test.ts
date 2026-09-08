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
