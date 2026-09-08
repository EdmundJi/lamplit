import { expect, it, vi } from 'vitest'
vi.mock('phaser', () => ({ default: { Scene: class {} } }))
import { companionPath, COMPANION_COLLISION, freeStandPosition } from './companion-navigation'
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

// "能站的地方都能去" (docs/04-decisions.md): once a resident's positionId is null - the normal
// case for someone merely standing, chatting or passing through - the frontend free-stands them
// instead of cramming everyone onto the same one or two named slots.
const RESIDENT_IDS = ['owner', 'student', 'artist', 'gardener', 'avatar']

it('free-stands every resident on standable, reachable ground in every place', () => {
  const entrance = { x: 535, y: 396 }
  for (const place of ['home', 'cafe', 'garden', 'street']) {
    for (const id of RESIDENT_IDS) {
      const destination = freeStandPosition(place, id)
      const label = `${place}/${id}`
      expect(canStand(destination, COMPANION_COLLISION), label).toBe(true)
      const path = companionPath(entrance, destination)
      expect(path.length, label).toBeGreaterThan(0)
      expect(path.at(-1), label).toEqual(destination)
    }
  }
})

it('free-standing is stable: the same resident id in the same place always lands on the same pixel', () => {
  for (const place of ['home', 'cafe', 'garden', 'street']) {
    for (const id of RESIDENT_IDS) {
      expect(freeStandPosition(place, id)).toEqual(freeStandPosition(place, id))
      // A completely unrelated resident coming and going elsewhere in the same place must not
      // move this one, since their point never even factors in as `occupied`.
      expect(freeStandPosition(place, id, [])).toEqual(freeStandPosition(place, id))
    }
  }
})

it('free-standing spreads residents apart instead of stacking them on the same pixel', () => {
  for (const place of ['home', 'cafe', 'garden', 'street']) {
    const occupied: { x: number; y: number }[] = []
    const points = RESIDENT_IDS.map(id => {
      const point = freeStandPosition(place, id, occupied)
      occupied.push(point)
      return point
    })
    for (let i = 0; i < points.length; i++) {
      for (let j = i + 1; j < points.length; j++) {
        const distance = Math.hypot(points[i]!.x - points[j]!.x, points[i]!.y - points[j]!.y)
        expect(distance, `${place}: ${RESIDENT_IDS[i]} vs ${RESIDENT_IDS[j]}`).toBeGreaterThanOrEqual(32)
      }
    }
  }
})

it('an existing resident keeps their exact spot when a newcomer with a lexicographically earlier id arrives', () => {
  // "aaron" was not part of the earlier scenes and sorts before every id above - it must not
  // retroactively displace anyone who was already there.
  for (const place of ['home', 'cafe', 'garden', 'street']) {
    const before = freeStandPosition(place, 'owner')
    const after = freeStandPosition(place, 'owner', [freeStandPosition(place, 'aaron')])
    expect(after).toEqual(before)
  }
})

it('residentPosition threads a resident id and occupied points into the free-standing case, keeping the named-slot cases exact', () => {
  // Untouched: a recognised furniture-anchored activity still gets its exact old pixel.
  expect(residentPosition('cafe', 0, 'focus', '', undefined, 0, 'owner')).toEqual({ x: 440, y: 289 })
  // New: two different resident ids idling ('idle' matches no furniture-anchored branch) in the
  // same place, one already occupying a point, land on distinct, spread pixels.
  const first = residentPosition('street', 0, 'idle', '', undefined, 0, 'owner', [])
  const second = residentPosition('street', 0, 'idle', '', undefined, 0, 'student', [first])
  expect(Math.hypot(first.x - second.x, first.y - second.y)).toBeGreaterThanOrEqual(32)
  // Calling without a residentId (an old caller, or these very unit tests above) still works.
  expect(canStand(residentPosition('street', 0, 'idle'), COMPANION_COLLISION)).toBe(true)
})
