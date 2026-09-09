import { describe, expect, it, vi } from 'vitest'
vi.mock('phaser', () => ({ default: { Scene: class {} } }))
import { residentPosition, scenePlace, visibleActivity, conversationPosition, rainFallsOutside } from './companion-scene'

describe('authoritative activity presentation', () => {
  it('keeps rain outdoors while retaining it on the street and in the garden', () => {
    expect(rainFallsOutside(130, 220)).toBe(false) // owner home
    expect(rainFallsOutside(560, 220)).toBe(false) // cafe interior
    expect(rainFallsOutside(500, 420)).toBe(true) // public street
    expect(rainFallsOutside(850, 440)).toBe(true) // outdoor beside the window wing
    expect(rainFallsOutside(910, 480)).toBe(false) // the long wing is roofed
    expect(rainFallsOutside(700, 480)).toBe(true) // L-shape courtyard is not its bounding box
  })
  it('places a focused resident at a visible desk and a gardener next to a planted bed', () => {
    expect(residentPosition('cafe', 0, 'focus')).toEqual({ x: 438, y: 176 })
    expect(residentPosition('home-owner', 0, 'read')).toEqual({ x: 136, y: 276 })
    const garden = residentPosition('garden', 4, 'garden')
    expect(garden.x).toBeGreaterThan(790)
    expect(garden.y).toBeLessThan(445)
    expect(garden.x + 54).toBeLessThan(1220)
  })
  it('gives four NPCs distinct sleeping corners and the avatar a living room seat', () => {
    const positions = Array.from({ length: 4 }, (_, index) => residentPosition('home', index + 1, 'sleep'))
    expect(new Set(positions.map(p => `${p.x}:${p.y}`)).size).toBe(4)
    expect(residentPosition('home-owner', 0, 'rest')).toEqual({ x: 170, y: 312 })
  })
  it('uses distinct visible actions and close conversation positions', () => {
    expect(visibleActivity('sleep', '睡着了')).toBe('sleep')
    expect(visibleActivity('create', '制作海报')).toBe('create')
    expect(visibleActivity('help', '帮忙')).toBe('create')
    expect(visibleActivity('drink', '喝水')).toBe('drink')
    expect(visibleActivity('garden', '浇花')).toBe('garden')
    expect(visibleActivity('tend', '回到吧台，照应一下柜台前的人')).toBe('idle')
    expect(visibleActivity('work', '把今天的账记完')).toBe('read')
    expect(visibleActivity('make', '收一收没画完的线稿')).toBe('create')
    expect(visibleActivity('idle', '回到吧台，照应一下柜台前的人')).toBe('idle')
    expect(visibleActivity('help', '帮忙准备读书小聚', 'poster')).toBe('create')
    expect(visibleActivity('create', '给花写一首诗', 'poster')).toBe('create')
    expect(visibleActivity('create', '带一株新芽回家', 'flowers')).toBe('garden')
    expect(visibleActivity('observe', '看看新开的花')).toBe('idle')
    expect(residentPosition('cafe', 1, 'rest')).toEqual({ x: 522, y: 176 })
    expect(conversationPosition('cafe', 1).x - conversationPosition('cafe', 0).x).toBe(42)
  })
  it('maps subplaces while keeping unknown server places safely on the shared street', () => {
    expect(scenePlace('cafe/window')).toBe('cafe')
    expect(scenePlace('home.desk')).toBe('home')
    expect(scenePlace('unavailable')).toBe('street')
  })
  it('maps every resident\'s own TownPlaces home ("home-<id>") to the home scene', () => {
    for (const id of ['owner', 'student', 'artist', 'gardener', 'self']) expect(scenePlace(`home-${id}`)).toBe('home')
  })
})

describe('positionId-authoritative placement (TownPlaces two-layer place model)', () => {
  it('prefers a recognised positionId outright, ignoring the place+index guess entirely', () => {
    expect(residentPosition('home-owner', 3, 'sleep', '', 'home-owner-bed')).toEqual({ x: 112, y: 252 })
    expect(residentPosition('anything', 99, 'idle', '', 'home-student-bed')).toEqual({ x: 248, y: 252 })
  })
  it('gives each of the five residents\' own beds a distinct, non-overlapping spot', () => {
    const beds = ['home-owner-bed', 'home-student-bed', 'home-artist-bed', 'home-gardener-bed', 'home-self-bed']
      .map(id => residentPosition('home', 0, 'sleep', '', id))
    expect(new Set(beds.map(p => `${p.x}:${p.y}`)).size).toBe(5)
    for (const p of beds) { expect(p.x).toBeGreaterThanOrEqual(0); expect(p.x).toBeLessThanOrEqual(960); expect(p.y).toBeGreaterThanOrEqual(0); expect(p.y).toBeLessThanOrEqual(640) }
  })
  it('spreads multiple occupants of one shared position (e.g. the 4-seat cafe worktable) across distinct slots', () => {
    const seats = [0, 1, 2, 3].map(occupant => residentPosition('cafe', 0, '', '', 'cafe-worktable', occupant))
    expect(new Set(seats.map(p => `${p.x}:${p.y}`)).size).toBe(4)
  })
  it('clamps an occupant index beyond capacity instead of returning an undefined slot', () => {
    expect(residentPosition('cafe', 0, '', '', 'cafe-window-seat', 7)).toEqual({ x: 964, y: 292 })
  })
  it('falls back to the place+index heuristic for an unrecognised positionId (old save, or unplaced position)', () => {
    expect(residentPosition('cafe', 0, 'focus', '', 'some-future-position-id')).toEqual({ x: 438, y: 176 })
    expect(residentPosition('cafe', 0, 'focus', '', null)).toEqual({ x: 438, y: 176 })
    expect(residentPosition('cafe', 0, 'focus')).toEqual({ x: 438, y: 176 })
  })
})
