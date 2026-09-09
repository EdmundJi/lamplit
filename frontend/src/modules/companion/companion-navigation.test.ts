import { expect, it, vi } from 'vitest'
vi.mock('phaser', () => ({ default: { Scene: class {} } }))
import { companionPath, COMPANION_COLLISION, freeStandPosition } from './companion-navigation'
import { residentPosition } from './companion-scene'
import { canStand } from '../../shared/scene/collision'
import { clearSegment } from '../../shared/scene/pathfinding'
import { CAFE_SERVICE, CAFE_TABLES, CAFE_SEATS, CAFE_WINDOW_SEATS, CAFE_WINDOW_TABLES, POSITION_SLOTS } from './companion-art'

it('every customer route connects entry, order, pickup and all ten seats without entering the staff aisle', () => {
  const stops = [CAFE_SERVICE.entry, CAFE_SERVICE.order, CAFE_SERVICE.pickup, ...CAFE_SERVICE.waiting, ...CAFE_SEATS].map(({ x, y }) => ({ x, y }))
  for (const from of stops) for (const to of stops) {
    if (from === to) continue
    const path = companionPath(from, to)
    expect(path.length, `${JSON.stringify(from)} → ${JSON.stringify(to)}`).toBeGreaterThan(0)
    let previous = from
    for (const next of path) {
      expect(clearSegment(previous, next, COMPANION_COLLISION)).toBe(true)
      // Customers have no reason to cut through the preparation lane behind the counter.
      for (let t = 0; t <= 1; t += .05) {
        const x = previous.x + (next.x - previous.x) * t, y = previous.y + (next.y - previous.y) * t
        expect(x > 622 && x < 846 && y < 147, `customer path entered staff area at ${x},${y}`).toBe(false)
      }
      previous = next
    }
    expect(path.at(-1)).toEqual({ x: to.x, y: to.y })
  }
})

it('the window row has six independent single seats facing joined desktops, clear of the pickup route', () => {
  const ids = ['cafe-window-seat', ...[2, 3, 4, 5, 6].map(n => `cafe-window-${n}`)]
  expect(new Set(ids.flatMap(id => POSITION_SLOTS[id]!.map(point => `${point.x}:${point.y}`))).size).toBe(6)
  for (const [index, id] of ids.entries()) {
    expect(POSITION_SLOTS[id]).toHaveLength(1)
    expect(CAFE_WINDOW_SEATS[index]!.facing).toBe('right')
    expect(canStand({ x: 924, y: CAFE_WINDOW_SEATS[index]!.y }, COMPANION_COLLISION)).toBe(true)
    expect(clearSegment({ x: 924, y: CAFE_WINDOW_SEATS[index]!.y }, CAFE_WINDOW_SEATS[index]!, COMPANION_COLLISION)).toBe(true)
  }
  // Native vertical tables at .75 scale are 69 px deep and meet edge to edge.
  expect(CAFE_WINDOW_TABLES).toHaveLength(6)
  for (let index = 1; index < 6; index++) expect(CAFE_WINDOW_TABLES[index]!.y - CAFE_WINDOW_TABLES[index - 1]!.y).toBe(69)
  expect(clearSegment(CAFE_SERVICE.pickup, { x: 924, y: 215 }, COMPANION_COLLISION)).toBe(true)
})

it('staff reach the machine by going around the end of the real counter, never through it', () => {
  expect(clearSegment(CAFE_SERVICE.order, CAFE_SERVICE.operator, COMPANION_COLLISION)).toBe(false)
  const path = companionPath(CAFE_SERVICE.order, CAFE_SERVICE.operator)
  expect(path.length).toBeGreaterThan(1)
  expect(path.some(point => point.x < 622)).toBe(true)
  expect(path.at(-1)).toEqual(CAFE_SERVICE.operator)
  let previous: { x: number; y: number } = CAFE_SERVICE.order
  for (const next of path) { expect(clearSegment(previous, next, COMPANION_COLLISION)).toBe(true); previous = next }
})

it('two-person tables have opposing seated orientations with a table between their hands', () => {
  for (const table of CAFE_TABLES) {
    const [left, right] = table.seats
    expect(left.facing).toBe('right'); expect(right.facing).toBe('left')
    expect(left.x).toBeLessThan(table.x - 30); expect(right.x).toBeGreaterThan(table.x + 30)
    expect(left.y).toBe(right.y)
    // Leaving a chair requires a step into the aisle, not a straight walk through the tabletop.
    expect(clearSegment(left, right, COMPANION_COLLISION)).toBe(false)
    expect(canStand({ x: left.x, y: left.y + 24 }, COMPANION_COLLISION)).toBe(true)
    expect(canStand({ x: right.x, y: right.y + 24 }, COMPANION_COLLISION)).toBe(true)
  }
})

it('every life destination is reachable through doors without crossing furniture', () => {
  const entrance = { x: 535, y: 396 }
  for (const place of ['home', 'cafe', 'garden', 'street']) {
    for (const activity of ['study', 'garden', 'rest', 'sleep', 'create', 'drink']) {
      for (let index = 0; index < 5; index++) {
        const destination = residentPosition(place, index, activity)
        const label = `${place}/${activity}/${index} → ${JSON.stringify(destination)}`
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
  const positionIds = Object.keys(POSITION_SLOTS)
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
      const location = place === 'home' ? `home-${id === 'avatar' ? 'self' : id}` : place
      const destination = freeStandPosition(location, id)
      const label = `${location}/${id}`
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
      const location = place === 'home' ? `home-${id === 'avatar' ? 'self' : id}` : place
      expect(freeStandPosition(location, id)).toEqual(freeStandPosition(location, id))
      // A completely unrelated resident coming and going elsewhere in the same place must not
      // move this one, since their point never even factors in as `occupied`.
      expect(freeStandPosition(location, id, [])).toEqual(freeStandPosition(location, id))
    }
  }
})

it('free-standing spreads residents apart instead of stacking them on the same pixel', () => {
  for (const place of ['home', 'cafe', 'garden', 'street']) {
    if (place === 'home') {
      const points = RESIDENT_IDS.map(id => freeStandPosition(`home-${id === 'avatar' ? 'self' : id}`, id))
      expect(new Set(points.map(point => `${point.x}:${point.y}`)).size).toBe(RESIDENT_IDS.length)
      continue
    }
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
    const location = place === 'home' ? 'home-owner' : place
    const before = freeStandPosition(location, 'owner')
    const after = freeStandPosition(location, 'owner', [freeStandPosition(location, 'aaron')])
    expect(after).toEqual(before)
  }
})

it('residentPosition threads a resident id and occupied points into the free-standing case, keeping the named-slot cases exact', () => {
  // Untouched: a recognised furniture-anchored activity still gets its exact old pixel.
  expect(residentPosition('cafe', 0, 'focus', '', undefined, 0, 'owner')).toEqual({ x: 438, y: 176 })
  // New: two different resident ids idling ('idle' matches no furniture-anchored branch) in the
  // same place, one already occupying a point, land on distinct, spread pixels.
  const first = residentPosition('street', 0, 'idle', '', undefined, 0, 'owner', [])
  const second = residentPosition('street', 0, 'idle', '', undefined, 0, 'student', [first])
  expect(Math.hypot(first.x - second.x, first.y - second.y)).toBeGreaterThanOrEqual(32)
  // Calling without a residentId (an old caller, or these very unit tests above) still works.
  expect(canStand(residentPosition('street', 0, 'idle'), COMPANION_COLLISION)).toBe(true)
})
