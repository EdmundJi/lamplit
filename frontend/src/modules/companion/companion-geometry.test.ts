import { describe, expect, it } from 'vitest'
import { isNight, litRooms } from './companion-geometry'
import { homeLightRoomIds, CAFE_LIGHT_ROOM } from './companion-art'

// task: 目前是房间的灯自动亮起，应该是让小人到房间可以操作开关 - litRooms() is the single source of
// truth every light rectangle in companion-scene.ts's sync() now reads from, replacing the old
// homeAwake/cafeOpen/night-only heuristics. Pure and Phaser-free, so it is exercised directly here
// rather than through the scene.
const DAY = 720 // 12:00
const NIGHT = 1200 // 20:00

function light(roomId: string, state: 'on' | 'off', overrides: Partial<{ kind: string }> = {}) {
  return { id: `light:${roomId}`, kind: overrides.kind ?? 'light', place: 'irrelevant', roomId, label: '灯', state, projectId: null }
}

describe('isNight', () => {
  it('matches the scene\'s own dusk-to-dawn window', () => {
    expect(isNight(0)).toBe(true)
    expect(isNight(359)).toBe(true)
    expect(isNight(360)).toBe(false)
    expect(isNight(1139)).toBe(false)
    expect(isNight(1140)).toBe(true)
    expect(isNight(1439)).toBe(true)
  })
})

describe('litRooms', () => {
  it('lights a room whose switch is on, after dark', () => {
    const rooms = litRooms([light('cafe-main', 'on')], NIGHT)
    expect(rooms.has('cafe-main')).toBe(true)
  })
  it('keeps a switched-on room dark in daylight - a light left on does not mean it is visible through a lit window in the afternoon sun', () => {
    const rooms = litRooms([light('cafe-main', 'on')], DAY)
    expect(rooms.has('cafe-main')).toBe(false)
  })
  it('keeps a switched-off room dark at night', () => {
    const rooms = litRooms([light('cafe-main', 'off')], NIGHT)
    expect(rooms.has('cafe-main')).toBe(false)
  })
  it('treats a room with no light object at all as dark - no fallback to "someone must be home"', () => {
    const rooms = litRooms([], NIGHT)
    expect(rooms.has('home-owner-room-owner')).toBe(false)
    // Also true when other, unrelated objects exist in the snapshot.
    const withOtherObjects = litRooms([{ id: 'worktable', kind: 'table', place: 'cafe', roomId: undefined, label: '桌子', state: 'available', projectId: null }], NIGHT)
    expect(withOtherObjects.has('cafe-main')).toBe(false)
  })
  it('ignores a non-light object that happens to share a roomId, and one missing a roomId entirely', () => {
    const rooms = litRooms([
      { id: 'shop-toolkit', kind: 'tool', place: 'shop', roomId: 'shop-workroom', label: '工具箱', state: 'on', projectId: null },
      { id: 'light:no-room', kind: 'light', place: 'shop', roomId: null, label: '灯', state: 'on', projectId: null },
    ], NIGHT)
    expect(rooms.size).toBe(0)
  })
  it('tracks multiple rooms independently - one on, one off, one missing', () => {
    const rooms = litRooms([
      light('home-owner-room-owner', 'on'),
      light('home-student-room-student', 'off'),
      light('cafe-main', 'on'),
    ], NIGHT)
    expect(rooms.has('home-owner-room-owner')).toBe(true)
    expect(rooms.has('home-student-room-student')).toBe(false)
    expect(rooms.has('academy-reading-room')).toBe(false) // no object for it at all
    expect(rooms.has('cafe-main')).toBe(true)
    expect(rooms.size).toBe(2)
  })
})

describe('homeLightRoomIds', () => {
  it('resolves a solo home to its own single bedroom room id', () => {
    expect(homeLightRoomIds('owner')).toEqual(['home-owner-room-owner'])
  })
  it('resolves a shared home to every flat-mate\'s own bedroom', () => {
    // 阿满 (weaver) shares 知夏's (artist) room as a flat-mate - not a HOME_ROOMS key of her own.
    expect(homeLightRoomIds('artist')).toEqual(['home-artist-room-artist', 'home-artist-room-weaver'])
  })
  it('resolves a 25-person household home to every one of its members\' bedrooms', () => {
    expect(homeLightRoomIds('barista')).toEqual(['home-barista-room-barista', 'home-barista-room-waiter', 'home-barista-room-tutor'])
  })
  it('lights the whole home box when any one flat-mate\'s own room is switched on', () => {
    const rooms = litRooms([light('home-artist-room-weaver', 'on')], NIGHT)
    expect(homeLightRoomIds('artist').some(id => rooms.has(id))).toBe(true)
  })
})

describe('the cafe\'s three light shapes share one switch', () => {
  it('is the same room id every cafe light rectangle in the scene checks', () => {
    expect(CAFE_LIGHT_ROOM).toBe('cafe-main')
    const rooms = litRooms([light(CAFE_LIGHT_ROOM, 'on')], NIGHT)
    expect(rooms.has(CAFE_LIGHT_ROOM)).toBe(true)
  })
})
