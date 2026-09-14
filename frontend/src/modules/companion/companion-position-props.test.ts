import { describe, expect, it } from 'vitest'
import { positionUseProps } from './companion-position-props'
import { CAFE_SERVICE, POSITION_SLOTS } from './companion-art'
import type { SceneResident, SceneSnapshot } from './companion-scene'

function resident(overrides: Partial<SceneResident> & { id: string }): SceneResident {
  return { name: overrides.id, location: 'street', action: '', ...overrides }
}
function snapshot(residents: SceneResident[], extra: Partial<SceneSnapshot> = {}): SceneSnapshot {
  return { residents, weather: 'clear', minutes: 600, ...extra }
}

describe('positionUseProps: furniture-anchored "in use" props', () => {
  it('lays an open book on an academy desk while someone studies there', () => {
    const props = positionUseProps(snapshot([resident({ id: 'scholar', positionId: 'academy-desk-1', activity: 'study', location: 'academy' })]))
    const desk = POSITION_SLOTS['academy-desk-1']![0]!
    expect(props).toEqual([{ positionId: 'academy-desk-1', prop: 'book', x: desk.x, y: desk.y - 14 }])
  })
  it('does not duplicate the book at a cafe seat or a home desk - the seated hand-prop already covers those', () => {
    const cafeSeat = positionUseProps(snapshot([resident({ id: 'student', positionId: 'cafe-worktable', activity: 'read', location: 'cafe' })]))
    expect(cafeSeat).toEqual([])
    const cafeWindow = positionUseProps(snapshot([resident({ id: 'student', positionId: 'cafe-window-seat', activity: 'study', location: 'cafe' })]))
    expect(cafeWindow).toEqual([])
    const homeDesk = positionUseProps(snapshot([resident({ id: 'owner', positionId: 'home-owner-desk', activity: 'read', location: 'home-owner' })]))
    expect(homeDesk).toEqual([])
  })
  it('produces no book for a non-reading activity at a desk', () => {
    const props = positionUseProps(snapshot([resident({ id: 'scholar', positionId: 'academy-desk-1', activity: 'observe', location: 'academy' })]))
    expect(props).toEqual([])
  })
  it('marks a bed with a blanket only once the occupant is actually lying down asleep', () => {
    const bed = POSITION_SLOTS['home-owner-bed']![0]!
    const props = positionUseProps(snapshot([resident({ id: 'owner', positionId: 'home-owner-bed', activity: 'sleep', location: 'home-owner' })]))
    expect(props).toEqual([{ positionId: 'home-owner-bed', prop: 'blanket', x: bed.x, y: bed.y }])
  })
  it('draws no blanket for a daytime rest at the bed position - occupying it is not the same as lying down asleep', () => {
    // A resting resident claims the same `home-owner-bed` positionId (companion-scene.ts's
    // `knownPositionId` branch) but is drawn sitting, not lying - a cover under a sitting figure
    // reads as a stray mark, not a made bed.
    const props = positionUseProps(snapshot([resident({ id: 'owner', positionId: 'home-owner-bed', activity: 'rest', location: 'home-owner' })]))
    expect(props).toEqual([])
  })
  it('never confuses a bed for a reading desk even if activity happens to look like reading', () => {
    const props = positionUseProps(snapshot([resident({ id: 'owner', positionId: 'home-owner-bed', activity: 'read', location: 'home-owner' })]))
    expect(props).toEqual([])
  })
  it('assigns a stable, id-sorted slot when several residents share one position, and only props the reader', () => {
    const props = positionUseProps(snapshot([
      resident({ id: 'zed', positionId: 'academy-desk-2', activity: 'study', location: 'academy' }),
      resident({ id: 'amy', positionId: 'academy-desk-2', activity: 'observe', location: 'academy' }),
    ]))
    // Sorted by id: 'amy' takes slot 0, 'zed' slot 1 (clamped to the position's one slot) -
    // academy-desk-2 has a single slot, so both resolve to the same pixel; only 'zed' is reading.
    const desk = POSITION_SLOTS['academy-desk-2']![0]!
    expect(props).toEqual([{ positionId: 'academy-desk-2', prop: 'book', x: desk.x, y: desk.y - 14 }])
  })
  it('shows steam only while the cafe is open and someone is actually tending/preparing the machine', () => {
    const tending = snapshot([resident({ id: 'owner', positionId: 'cafe-counter', activity: 'tend', location: 'cafe' })], { cafeOpen: true })
    expect(positionUseProps(tending)).toEqual([{ positionId: 'cafe-counter', prop: 'steam', x: CAFE_SERVICE.machine.x, y: CAFE_SERVICE.machine.y }])
    const preparing = snapshot([resident({ id: 'owner', positionId: 'cafe-counter', activity: 'prepare', location: 'cafe' })], { cafeOpen: true })
    expect(positionUseProps(preparing)).toEqual([{ positionId: 'cafe-counter', prop: 'steam', x: CAFE_SERVICE.machine.x, y: CAFE_SERVICE.machine.y }])
  })
  it('omits steam when merely standing at the counter (serve/wait/handover/assist), not making the drink', () => {
    const props = positionUseProps(snapshot([resident({ id: 'owner', positionId: 'cafe-counter', activity: 'serve', location: 'cafe' })], { cafeOpen: true }))
    expect(props).toEqual([])
  })
  it('omits steam once the cafe is closed even if someone is still tending', () => {
    const props = positionUseProps(snapshot([resident({ id: 'owner', positionId: 'cafe-counter', activity: 'tend', location: 'cafe' })], { cafeOpen: false }))
    expect(props).toEqual([])
  })
  it('defaults cafeOpen to true when the snapshot omits it, matching the scene\'s own fallback', () => {
    const props = positionUseProps(snapshot([resident({ id: 'owner', positionId: 'cafe-counter', activity: 'tend', location: 'cafe' })]))
    expect(props).toEqual([{ positionId: 'cafe-counter', prop: 'steam', x: CAFE_SERVICE.machine.x, y: CAFE_SERVICE.machine.y }])
  })
  it('quietly skips residents with no positionId, or a positionId with no registered pixel', () => {
    expect(positionUseProps(snapshot([resident({ id: 'self', activity: 'read', location: 'street' })]))).toEqual([])
    expect(positionUseProps(snapshot([resident({ id: 'self', positionId: 'bathhouse-tub', activity: 'read', location: 'street' })]))).toEqual([])
  })
})
