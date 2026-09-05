import { describe, expect, it } from 'vitest'
import { attributeProgress, strongestAttribute, type AttributeRow } from './attributes.logic'

function row(overrides: Partial<AttributeRow>): AttributeRow {
  return {
    code: 'KNOWLEDGE', name: '智力', dimensionName: '知识', description: '', experience: 0, level: 1,
    radarScore: 0, currentLevelExperience: 0, nextLevelExperience: 100, experienceToNextLevel: 100, ...overrides,
  }
}

describe('attributeProgress', () => {
  it('computes percent progress toward the next level', () => {
    expect(attributeProgress(row({ experience: 50, currentLevelExperience: 0, nextLevelExperience: 100 }))).toBe(50)
  })

  it('is fully complete once there is no next level', () => {
    expect(attributeProgress(row({ nextLevelExperience: null }))).toBe(100)
  })

  it('clamps to [0, 100]', () => {
    expect(attributeProgress(row({ experience: 500, currentLevelExperience: 0, nextLevelExperience: 100 }))).toBe(100)
  })
})

describe('strongestAttribute', () => {
  it('picks the attribute with the most experience', () => {
    const attributes = [row({ code: 'KNOWLEDGE', experience: 10 }), row({ code: 'HEALTH', experience: 40 })]
    expect(strongestAttribute(attributes)?.code).toBe('HEALTH')
  })

  it('returns null for an empty list', () => {
    expect(strongestAttribute([])).toBeNull()
  })
})
