import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { COMPANION_WORLD_SIZE, HOME_ROOMS, POSITION_SLOTS, RESIDENT_ART, STAGE_PLACES, resolveStagePlace, sleepSpriteOffset } from './companion-art'

describe('stage place registry', () => {
  it('provides one distinct appearance for 25 residents and the player avatar', () => {
    expect(RESIDENT_ART).toHaveLength(26)
    expect(new Set(RESIDENT_ART).size).toBe(26)
    expect(RESIDENT_ART).toEqual(expect.arrayContaining([21, 22, 23, 24, 25, 'postman']))
  })
  it('resolves a ready place to its own registered target', () => {
    expect(resolveStagePlace('cafe')).toEqual(STAGE_PLACES.cafe)
    expect(resolveStagePlace('garden')).toEqual(STAGE_PLACES.garden)
  })
  it('resolves every new public place to its own real geometry, not a street fallback', () => {
    for (const id of ['board', 'academy', 'gym', 'shop']) {
      const resolved = resolveStagePlace(id)
      expect(resolved.status).toBe('ready')
      expect(resolved).toEqual(STAGE_PLACES[id])
      expect(resolved.target).not.toEqual(STAGE_PLACES.street!.target)
      expect(resolved.frame).toBeDefined()
    }
  })
  it('every ready place keeps its camera target inside the world bounds', () => {
    for (const place of Object.values(STAGE_PLACES)) {
      expect(place.target.x).toBeGreaterThanOrEqual(0)
      expect(place.target.x).toBeLessThanOrEqual(COMPANION_WORLD_SIZE.width)
      expect(place.target.y).toBeGreaterThanOrEqual(0)
      expect(place.target.y).toBeLessThanOrEqual(COMPANION_WORLD_SIZE.height)
    }
  })
  it('every ready place\'s frame stays within the world bounds too', () => {
    for (const place of Object.values(STAGE_PLACES)) {
      if (!place.frame) continue
      expect(place.frame.x).toBeGreaterThanOrEqual(0)
      expect(place.frame.y).toBeGreaterThanOrEqual(0)
      expect(place.frame.x + place.frame.w).toBeLessThanOrEqual(COMPANION_WORLD_SIZE.width)
      expect(place.frame.y + place.frame.h).toBeLessThanOrEqual(COMPANION_WORLD_SIZE.height)
    }
  })
  it('falls back an unknown id entirely to street', () => {
    expect(resolveStagePlace('unknown-place')).toEqual(STAGE_PLACES.street)
    expect(resolveStagePlace(undefined)).toEqual(STAGE_PLACES.street)
  })
  describe('sleepSpriteOffset: lands the sleep sprite\'s own foot on the bed image\'s foot', () => {
    it('uses the solo-home gap (15px) for a hand-placed bed, not the shared-household one', () => {
      expect(sleepSpriteOffset('home-fixer-bed')).toBe(-15)
      expect(sleepSpriteOffset('home-owner-bed')).toBe(-15)
      // 阿满 shares 知夏's room as a flat-mate but is not a HOUSEHOLD_MEMBERS entry - her bed was
      // hand-placed the same way the solo homes were (companion-stage.ts's `id === 'artist'`
      // branch), so she takes the same 15px gap, not the household one.
      expect(sleepSpriteOffset('home-weaver-bed')).toBe(-15)
    })
    it('uses the shared-household gap (4px) for a HOUSEHOLD_MEMBERS bed', () => {
      expect(sleepSpriteOffset('home-trainer-bed')).toBe(-4)
      expect(sleepSpriteOffset('home-scholar-bed')).toBe(-4)
    })
    it('falls back to the (majority) solo gap for anything that is not a recognised bed positionId', () => {
      expect(sleepSpriteOffset(undefined)).toBe(-15)
      expect(sleepSpriteOffset(null)).toBe(-15)
      expect(sleepSpriteOffset('cafe-counter')).toBe(-15)
      expect(sleepSpriteOffset('home-unknown-resident-bed')).toBe(-15)
    })
    it('lands the sleeping sprite\'s own 64px-tall frame close to the bed image\'s real footprint, for a solo and a shared home alike', () => {
      // companion-stage.ts draws the solo bed's own image bottom at `y + 129`, 15px above the
      // occupancy pixel (`homeRoom()`'s `bed: { y: y + 144 }`) - and the sleep sprite's own foot
      // should now land there too (`occupancy.y + sleepSpriteOffset(...)`).
      const fixerRoom = HOME_ROOMS.fixer!
      const fixerBed = POSITION_SLOTS['home-fixer-bed']![0]!
      expect(fixerBed.y + sleepSpriteOffset('home-fixer-bed')).toBe(fixerRoom.y + 129)
      // The shared-household loop draws each member's bed image bottom at `bed.y - 4`.
      const trainerBed = POSITION_SLOTS['home-trainer-bed']![0]!
      expect(trainerBed.y + sleepSpriteOffset('home-trainer-bed')).toBe(trainerBed.y - 4)
    })
  })
  describe('dev-only warning', () => {
    beforeEach(() => { vi.spyOn(console, 'warn').mockImplementation(() => undefined) })
    afterEach(() => { vi.restoreAllMocks() })
    it('warns at most once per id, only in dev', () => {
      // board/academy/gym are all 'ready' now (no placeholder left in the registry), so this test
      // exercises the other branch of the same guard - unknown ids - instead.
      resolveStagePlace('unknown-place-a')
      resolveStagePlace('unknown-place-a')
      resolveStagePlace('unknown-place-b')
      // import.meta.env.DEV is true under vitest, so every distinct placeholder/unknown id warns
      // exactly once - repeat calls for the same id must not spam the console.
      expect(console.warn).toHaveBeenCalledTimes(2)
    })
  })
})
