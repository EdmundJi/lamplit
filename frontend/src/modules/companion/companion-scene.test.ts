import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('phaser', () => ({ default: { Scene: class {} } }))
import { residentPosition, scenePlace, visibleActivity, conversationPosition, conversationPositionInRoom, rainFallsOutside, isSpeaking, visibleSceneLabels } from './companion-scene'
import type { SceneLabel } from './companion-scene'
import { shift } from './town-layout'
// Points authored against the original west block (cafe, street, garden, first homes) move with it.
const west = (x: number, y: number) => shift('street', x, y)
const rain = (x: number, y: number) => { const p = west(x, y); return rainFallsOutside(p.x, p.y) }

describe('authoritative activity presentation', () => {
  it('keeps rain outdoors while retaining it on the street and in the garden', () => {
    expect(rain(130, 220)).toBe(false) // owner home
    expect(rain(560, 220)).toBe(false) // cafe interior
    expect(rain(500, 420)).toBe(true) // public street
    expect(rain(850, 440)).toBe(true) // outdoor beside the window wing
    expect(rain(910, 480)).toBe(false) // the long wing is roofed
    expect(rain(700, 480)).toBe(true) // L-shape courtyard is not its bounding box
  })
  it('places a focused resident at a visible desk and a gardener next to a planted bed', () => {
    expect(residentPosition('cafe', 0, 'focus')).toEqual(west(438, 176))
    expect(residentPosition('home-owner', 0, 'read')).toEqual(west(136, 276))
    const garden = residentPosition('garden', 4, 'garden')
    expect(garden.x).toBeGreaterThan(west(790, 0).x)
    expect(garden.y).toBeLessThan(west(0, 445).y)
    expect(garden.x + 54).toBeLessThan(west(1220, 0).x)
  })
  it('gives four NPCs distinct sleeping corners and the avatar a living room seat', () => {
    const positions = Array.from({ length: 4 }, (_, index) => residentPosition('home', index + 1, 'sleep'))
    expect(new Set(positions.map(p => `${p.x}:${p.y}`)).size).toBe(4)
    expect(residentPosition('home-owner', 0, 'rest')).toEqual(west(170, 312))
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
    expect(residentPosition('cafe', 1, 'rest')).toEqual(west(522, 176))
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
    expect(first).toEqual(west(438, 176)) // falls through to the same heuristic as before
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
    expect(residentPosition('home-owner', 3, 'sleep', '', 'home-owner-bed')).toEqual(west(112, 252))
    expect(residentPosition('anything', 99, 'idle', '', 'home-student-bed')).toEqual(west(248, 252))
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
    expect(residentPosition('cafe', 0, '', '', 'cafe-window-seat', 7)).toEqual(west(964, 292))
  })
  it('falls back to the place+index heuristic for an unrecognised positionId (old save, or unplaced position)', () => {
    expect(residentPosition('cafe', 0, 'focus', '', 'some-future-position-id')).toEqual(west(438, 176))
    expect(residentPosition('cafe', 0, 'focus', '', null)).toEqual(west(438, 176))
    expect(residentPosition('cafe', 0, 'focus')).toEqual(west(438, 176))
  })
})

describe('room-aware home placement', () => {
  it('keeps flatmates in their server-described rooms even before either holds a named position', () => {
    const artist = residentPosition('home-artist', 0, 'observe', '', undefined, 0, 'artist', [], 'home-artist-room-artist')
    const weaver = residentPosition('home-artist', 0, 'observe', '', undefined, 0, 'weaver', [], 'home-artist-room-weaver')
    const common = residentPosition('home-artist', 0, 'observe', '', undefined, 0, 'artist', [], 'home-artist-common')
    expect(artist).not.toEqual(weaver)
    expect(common).not.toEqual(artist)
    expect(common).not.toEqual(weaver)
  })
  it('keeps a room-level home conversation in its own house instead of the generic home centre', () => {
    const artistHome = conversationPositionInRoom('home-artist', 0, 'home-artist-common')
    const fixerHome = conversationPositionInRoom('home-fixer', 0, 'home-fixer-room-fixer')
    expect(artistHome).not.toEqual(fixerHome)
    expect(artistHome.x).toBeLessThan(west(300, 0).x)
    expect(fixerHome.x).toBeGreaterThan(1000)
  })
  it('spreads non-positioned residents in one common room across its real table slots', () => {
    const first = residentPosition('home-artist', 0, 'observe', '', undefined, 0, 'artist', [], 'home-artist-common')
    const second = residentPosition('home-artist', 1, 'observe', '', undefined, 0, 'weaver', [first], 'home-artist-common')
    expect(first).not.toEqual(second)
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

describe('populated-town scene labels', () => {
  it('keeps only one unselected offscreen representative per edge while retaining the selected resident', () => {
    const label = (id: string, direction: SceneLabel['direction'], selected = false): SceneLabel => ({ id, name: id, x: 0, y: 0, selected, speechOffset: 0, action: '', role: '', emoji: '', bodyX: 0, bodyY: 0, bodyHeight: 0, offscreen: true, direction })
    const shown = visibleSceneLabels([label('a', '‹'), label('b', '‹'), label('c', '›'), label('d', '⌃'), label('e', '⌄'), label('selected', '‹', true)])
    expect(shown.map(item => item.id)).toEqual(['a', 'c', 'd', 'e', 'selected'])
  })
})
