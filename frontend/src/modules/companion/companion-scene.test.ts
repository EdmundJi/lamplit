import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('phaser', () => ({ default: { Scene: class {} } }))
import { residentPosition, scenePlace, visibleActivity, conversationPosition, rainFallsOutside, isSpeaking } from './companion-scene'

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
    // Making the drink is now visible - it borrows the drink action sheet as its pour beat
    // (docs/01: "等待... 你能看见他在弄") instead of leaving him idle for the whole wait.
    expect(visibleActivity('tend', '回到吧台，照应一下柜台前的人')).toBe('drink')
    expect(visibleActivity('prepare', '磨豆子')).toBe('drink')
    expect(visibleActivity('serve', '把咖啡端过去')).toBe('idle')
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
    // 'weaver' has no separate home-<id> location of her own - she shares 'home-artist', already
    // covered by 'artist' here.
    for (const id of ['owner', 'student', 'artist', 'gardener', 'self', 'fixer']) expect(scenePlace(`home-${id}`)).toBe('home')
  })
})

describe('unknown places are loud, not silently redrawn as somewhere real (task: docs/05 wardrobe-as-table)', () => {
  beforeEach(() => { vi.spyOn(console, 'warn').mockImplementation(() => undefined) })
  afterEach(() => { vi.restoreAllMocks() })
  it('warns once per unrecognised location, still landing safely on the street', () => {
    expect(scenePlace('bathhouse')).toBe('street')
    expect(scenePlace('bathhouse')).toBe('street')
    expect(scenePlace('noodle-shop')).toBe('street')
    expect(console.warn).toHaveBeenCalledTimes(2)
    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('"bathhouse"'))
  })
  it('warns once per positionId that POSITION_SLOTS has no pixel for, but still returns a real, standable point', () => {
    const first = residentPosition('cafe', 0, 'focus', '', 'bathhouse-tub')
    residentPosition('cafe', 0, 'focus', '', 'bathhouse-tub')
    expect(first).toEqual({ x: 438, y: 176 }) // falls through to the same heuristic as before
    expect(console.warn).toHaveBeenCalledTimes(1)
    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('"bathhouse-tub"'))
  })
  it('warns once per home location with no registered room, cycling through the rooms that actually exist rather than a stale hard-coded name list', () => {
    residentPosition('home-newcomer', 0, 'sleep')
    residentPosition('home-newcomer', 0, 'sleep')
    expect(console.warn).toHaveBeenCalledTimes(1)
    expect(console.warn).toHaveBeenCalledWith(expect.stringContaining('"home-newcomer"'))
  })
  it('never warns for a place it actually knows, including cafe/garden/street calls that never touch home geometry', () => {
    residentPosition('cafe', 0, 'focus')
    residentPosition('garden', 0, 'garden')
    residentPosition('street', 0, 'idle')
    scenePlace('cafe'); scenePlace('garden'); scenePlace('street'); scenePlace('home-owner')
    expect(console.warn).not.toHaveBeenCalled()
  })
})

describe('positionId-authoritative placement (TownPlaces two-layer place model)', () => {
  it('prefers a recognised positionId outright, ignoring the place+index guess entirely', () => {
    expect(residentPosition('home-owner', 3, 'sleep', '', 'home-owner-bed')).toEqual({ x: 112, y: 252 })
    expect(residentPosition('anything', 99, 'idle', '', 'home-student-bed')).toEqual({ x: 248, y: 252 })
  })
  it('gives each resident\'s own bed a distinct, non-overlapping spot - including fixer\'s new house and weaver\'s bed inside artist\'s shared room', () => {
    const beds = ['home-owner-bed', 'home-student-bed', 'home-artist-bed', 'home-gardener-bed', 'home-self-bed', 'home-fixer-bed', 'home-weaver-bed']
      .map(id => residentPosition('home', 0, 'sleep', '', id))
    expect(new Set(beds.map(p => `${p.x}:${p.y}`)).size).toBe(7)
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

describe('a speech bubble is gated on its own speaker only', () => {
  // Regression for: giving one conversation participant a backend positionId sends them on a
  // short walk to their newly authoritative seat (their place+index guess no longer matches the
  // real slot). The old speech gate additionally required every OTHER actor sharing this
  // speaker's conversationId to have also finished walking, so that unrelated walk blanked the
  // stationary, currently-speaking partner's bubble too - see harness repro captured in
  // scratchpad/runs/t1 (before/after screenshots) for this exact scenario end to end.
  it('shows the current speaker once they themselves have arrived, regardless of a conversation partner still walking', () => {
    expect(isSpeaking('achuan', 'achuan', true)).toBe(true)
  })
  it('hides the bubble while the speaker themselves is still walking to their own target', () => {
    expect(isSpeaking('ahe', 'ahe', false)).toBe(false)
  })
  it('never shows a bubble for a resident whose turn it is not', () => {
    expect(isSpeaking('ahe', 'achuan', true)).toBe(false)
  })
})
