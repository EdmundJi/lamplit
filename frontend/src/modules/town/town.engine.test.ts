/**
 * town.engine.ts is a Phaser scene factory, but M7-6/7/8/9 and M3-2's actual arithmetic lives in
 * a handful of exported pure functions (no Phaser touched) so it can be pinned down without ever
 * constructing a Phaser.Game. Importing the module itself is safe here: `import type PhaserNs from
 * 'phaser'` is erased at compile time, and the real `phaser` package is only ever pulled in via a
 * dynamic `await import('phaser')` inside `createTownGame`'s body — never at module load.
 */
import { describe, expect, it } from 'vitest'
import { isInteriorPetSpecies, minuteOfLocalDay, resolveNpcFrame } from './town.engine'
import type { TownLayout } from './npc-placement'
import type { NpcDayPlan } from './town-npc.types'

const LAYOUT: TownLayout = {
  academyDoorX: 1000,
  plotStartX: 1400,
  gymX: 1400,
  cafeX: 1736,
  parkX: 940,
  plazaMinX: 200,
  plazaMaxX: 800,
  worldWidth: 4000,
}
const STREET_Y = 900

const AT_HOME_PLAN: NpcDayPlan = {
  date: '2026-09-06',
  errands: [{ place: 'home', activity: 'idle', startMinute: 0, endMinute: 1440, priority: 1, origin: 'RHYTHM' }],
  legs: [],
}

// home (plotStartX=1400) and cafe (cafeX=1736) are distinct x's in LAYOUT — gym happens to sit
// at the same x as home in this fixture, which would make an interpolation test degenerate.
const COMMUTE_PLAN: NpcDayPlan = {
  date: '2026-09-06',
  errands: [
    { place: 'home', activity: 'idle', startMinute: 0, endMinute: 420, priority: 1, origin: 'RHYTHM' },
    { place: 'cafe', activity: 'idle', startMinute: 450, endMinute: 480, priority: 1, origin: 'RHYTHM' },
  ],
  legs: [{ fromPlace: 'home', toPlace: 'cafe', departMinute: 420, arriveMinute: 450 }],
}

describe('resolveNpcFrame (M7-6/7/8)', () => {
  it('AT: sits at placeFor(place) + the same horizontal spread venueOffset would give, jittered vertically, depth tracks y', () => {
    const frame = resolveNpcFrame(AT_HOME_PLAN, 12 * 60, 'npc-alice', LAYOUT, STREET_Y)
    expect(frame.walking).toBe(false)
    expect(frame.facing).toBeNull()
    expect(frame.activity).toBe('idle')
    expect(frame.depth).toBe(frame.y) // depthForY(y) === y
    expect(frame.targetX).toBe(frame.x) // AT: no direction, targetX is just itself
    // 站定的位置在 home 附近（placeFor('home')=1400）横向散开区间之内 — 不应等于门牌坐标本身
    // 除非哈希碰巧给出 0 偏移；改用一个更弱的不变量：始终是有限数，且落在合理的散开半径里。
    expect(Number.isFinite(frame.x)).toBe(true)
    expect(Math.abs(frame.x - LAYOUT.plotStartX)).toBeLessThanOrEqual(190)
  })

  it('AT: is deterministic — same (dayPlan, minute, npcCode, layout) always gives the same frame', () => {
    const a = resolveNpcFrame(AT_HOME_PLAN, 500, 'npc-bob', LAYOUT, STREET_Y)
    const b = resolveNpcFrame(AT_HOME_PLAN, 500, 'npc-bob', LAYOUT, STREET_Y)
    expect(a).toEqual(b)
  })

  it('AT: different npcCodes at the same place land at different offsets (no lined-up-in-a-row stacking)', () => {
    const a = resolveNpcFrame(AT_HOME_PLAN, 500, 'npc-a', LAYOUT, STREET_Y)
    const b = resolveNpcFrame(AT_HOME_PLAN, 500, 'npc-zzz', LAYOUT, STREET_Y)
    expect(a.x === b.x && a.y === b.y).toBe(false)
  })

  it('WALKING: interpolates linearly between fromPlace and toPlace by positionAt\'s progress', () => {
    // leg 420->450 (30min); at minute 435 progress is exactly 0.5.
    const frame = resolveNpcFrame(COMMUTE_PLAN, 435, 'npc-carol', LAYOUT, STREET_Y)
    expect(frame.walking).toBe(true)
    expect(frame.activity).toBe('walking')
    const fromX = LAYOUT.plotStartX // home
    const toX = LAYOUT.cafeX // cafe
    expect(frame.x).toBeCloseTo((fromX + toX) / 2, 5)
    expect(frame.y).toBe(STREET_Y) // 在路上不做纵向散开，只有站定才散
    expect(frame.depth).toBe(STREET_Y)
    expect(frame.targetX).toBe(toX)
    expect(frame.facing).toBe(toX >= fromX ? 'right' : 'left')
  })

  it('WALKING: position at the very start/end of a leg matches positionAt\'s clamped endpoints', () => {
    const start = resolveNpcFrame(COMMUTE_PLAN, 420, 'npc-dave', LAYOUT, STREET_Y)
    expect(start.x).toBeCloseTo(LAYOUT.plotStartX, 5)
    const end = resolveNpcFrame(COMMUTE_PLAN, 449.999, 'npc-dave', LAYOUT, STREET_Y)
    expect(end.x).toBeGreaterThan(start.x)
    expect(end.x).toBeLessThanOrEqual(LAYOUT.cafeX)
  })

  it('a fractional (continuous) minute produces a frame strictly between the two endpoints, not a jump', () => {
    const early = resolveNpcFrame(COMMUTE_PLAN, 425, 'npc-erin', LAYOUT, STREET_Y)
    const later = resolveNpcFrame(COMMUTE_PLAN, 425.5, 'npc-erin', LAYOUT, STREET_Y)
    // 半分钟的连续插值应该只挪了一点点，而不是整段瞬移——这正是 M7-7 的核心验收点。
    expect(Math.abs(later.x - early.x)).toBeGreaterThan(0)
    const fullLegSpan = Math.abs(LAYOUT.cafeX - LAYOUT.plotStartX)
    expect(Math.abs(later.x - early.x)).toBeLessThan(fullLegSpan * 0.05)
  })
})

describe('minuteOfLocalDay', () => {
  it('is a continuous (fractional) minute-of-day, not just whole minutes', () => {
    const d = new Date(2026, 0, 1, 6, 30, 30, 500)
    expect(minuteOfLocalDay(d.getTime())).toBeCloseTo(6 * 60 + 30 + 30 / 60 + 500 / 60000, 5)
  })

  it('midnight is 0, and it never reaches 1440 for a time within the same day', () => {
    const midnight = new Date(2026, 0, 1, 0, 0, 0, 0)
    expect(minuteOfLocalDay(midnight.getTime())).toBe(0)
    const almostMidnight = new Date(2026, 0, 1, 23, 59, 59, 999)
    expect(minuteOfLocalDay(almostMidnight.getTime())).toBeLessThan(1440)
  })
})

describe('isInteriorPetSpecies (M3-2 宠物数据校验)', () => {
  it('accepts every InteriorPetSpecies value', () => {
    for (const species of ['CAT', 'DOG', 'HAMSTER', 'SNAKE', 'RABBIT', 'BIRD', 'TURTLE', 'FOX']) {
      expect(isInteriorPetSpecies(species)).toBe(true)
    }
  })

  it('rejects anything else instead of crashing the caller', () => {
    expect(isInteriorPetSpecies('DRAGON')).toBe(false)
    expect(isInteriorPetSpecies('')).toBe(false)
    expect(isInteriorPetSpecies('cat')).toBe(false) // 大小写不匹配也算拒绝，不做隐式纠正
  })
})
