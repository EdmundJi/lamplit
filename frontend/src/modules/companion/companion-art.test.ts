import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { COMPANION_WORLD_SIZE, RESIDENT_ART, STAGE_PLACES, resolveStagePlace } from './companion-art'

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
