import { describe, expect, it } from 'vitest'
import { petSpeciesOptions } from './pet-options'

describe('pet species options', () => {
  it('provides three to five kinds and interactions for every animal', () => {
    expect(petSpeciesOptions).toHaveLength(8)

    for (const species of petSpeciesOptions) {
      expect(species.kinds.length, `${species.label} kinds`).toBeGreaterThanOrEqual(3)
      expect(species.kinds.length, `${species.label} kinds`).toBeLessThanOrEqual(5)
      expect(species.interactions.length, `${species.label} interactions`).toBeGreaterThanOrEqual(3)
      expect(species.interactions.length, `${species.label} interactions`).toBeLessThanOrEqual(5)

      expect(new Set(species.kinds.map(kind => kind.value)).size).toBe(species.kinds.length)
      expect(new Set(species.interactions.map(action => action.action)).size).toBe(species.interactions.length)
      expect(species.kinds.every(kind => kind.value && kind.defaultColor)).toBe(true)
    }
  })
})
