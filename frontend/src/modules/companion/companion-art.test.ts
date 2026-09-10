import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { COMPANION_WORLD_SIZE, STAGE_PLACES, resolveStagePlace } from './companion-art'

describe('stage place registry', () => {
  it('resolves a ready place to its own registered target', () => {
    expect(resolveStagePlace('cafe')).toEqual(STAGE_PLACES.cafe)
    expect(resolveStagePlace('garden')).toEqual(STAGE_PLACES.garden)
  })
  it('every ready place keeps its camera target inside the world bounds', () => {
    for (const place of Object.values(STAGE_PLACES)) {
      expect(place.target.x).toBeGreaterThanOrEqual(0)
      expect(place.target.x).toBeLessThanOrEqual(COMPANION_WORLD_SIZE.width)
      expect(place.target.y).toBeGreaterThanOrEqual(0)
      expect(place.target.y).toBeLessThanOrEqual(COMPANION_WORLD_SIZE.height)
    }
  })
  it('falls back a placeholder place\'s camera target to street, but keeps its own label/status', () => {
    const resolved = resolveStagePlace('board')
    expect(resolved.target).toEqual(STAGE_PLACES.street!.target)
    expect(resolved.label).toBe('公告板')
    expect(resolved.status).toBe('placeholder')
  })
  it('falls back an unknown id entirely to street', () => {
    expect(resolveStagePlace('unknown-place')).toEqual(STAGE_PLACES.street)
    expect(resolveStagePlace(undefined)).toEqual(STAGE_PLACES.street)
  })
  describe('dev-only warning', () => {
    beforeEach(() => { vi.spyOn(console, 'warn').mockImplementation(() => undefined) })
    afterEach(() => { vi.restoreAllMocks() })
    it('warns at most once per id, only in dev', () => {
      resolveStagePlace('academy')
      resolveStagePlace('academy')
      resolveStagePlace('gym')
      // import.meta.env.DEV is true under vitest, so every distinct placeholder/unknown id warns
      // exactly once - repeat calls for the same id must not spam the console.
      expect(console.warn).toHaveBeenCalledTimes(2)
    })
  })
})
