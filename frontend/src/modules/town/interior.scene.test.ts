import { describe, expect, it, vi } from 'vitest'
import { createDefaultController, characterSheetFor, interiorSceneKey, PET_HOUSE_ACTION_ID, petPixelSpecFor } from './interior.scene'

// Phaser cannot run in jsdom (same limitation academy.scene.test.ts documents), so this file only
// covers this module's pure, Phaser-free helpers: the scene-key formula, characterSheetFor, and
// petPixelSpecFor's species -> pixel-asset lookup (M3-5). createInteriorScene()'s actual rendering
// (furniture click zones, pet wandering) is exercised visually.

describe('interiorSceneKey', () => {
  it('namespaces the scene key by room id so multiple rooms can be registered at once', () => {
    expect(interiorSceneKey('home-living-room')).toBe('interior:home-living-room')
    expect(interiorSceneKey('public-gym')).toBe('interior:public-gym')
  })
})

describe('characterSheetFor', () => {
  it('is deterministic and stays within the 1-20 character sheet range, same as town.engine.ts', () => {
    const a = characterSheetFor('user-1')
    const b = characterSheetFor('user-1')
    expect(a).toBe(b)
    expect(a).toBeGreaterThanOrEqual(1)
    expect(a).toBeLessThanOrEqual(20)
  })

  it('varies across different ids (not a constant)', () => {
    const sheets = new Set(['a', 'b', 'c', 'd', 'e', 'f'].map(characterSheetFor))
    expect(sheets.size).toBeGreaterThan(1)
  })
})

describe('petPixelSpecFor', () => {
  it('returns null for every species Modern Farm shipped no pixel sprite for (M3-5 placeholder path)', () => {
    expect(petPixelSpecFor('CAT', 'pet-1')).toBeNull()
    expect(petPixelSpecFor('HAMSTER', 'pet-1')).toBeNull()
    expect(petPixelSpecFor('SNAKE', 'pet-1')).toBeNull()
    expect(petPixelSpecFor('TURTLE', 'pet-1')).toBeNull()
    expect(petPixelSpecFor('FOX', 'pet-1')).toBeNull()
  })

  it('picks a dog frame prefix that actually starts with "dog_"', () => {
    const spec = petPixelSpecFor('DOG', 'my-dog')
    expect(spec?.framePrefix.startsWith('dog_')).toBe(true)
    expect(spec?.walkFrames).toBe(4)
  })

  it('picks a rabbit frame prefix that actually starts with "rabbit_"', () => {
    const spec = petPixelSpecFor('RABBIT', 'my-rabbit')
    expect(spec?.framePrefix.startsWith('rabbit_')).toBe(true)
  })

  it('falls back to a duck/chicken/rooster frame for BIRD (no generic bird sprite exists)', () => {
    const spec = petPixelSpecFor('BIRD', 'my-bird')
    expect(spec?.framePrefix).toMatch(/^(duck|chicken|rooster)_/)
  })

  it('only dogs can sleep — no other species has a doghouse_sleep-style pose', () => {
    expect(petPixelSpecFor('DOG', 'x')?.canSleep).toBe(true)
    expect(petPixelSpecFor('RABBIT', 'x')?.canSleep).toBe(false)
    expect(petPixelSpecFor('BIRD', 'x')?.canSleep).toBe(false)
  })

  it('is deterministic: the same seed always yields the same variant', () => {
    const a = petPixelSpecFor('DOG', 'stable-id')
    const b = petPixelSpecFor('DOG', 'stable-id')
    expect(a).toEqual(b)
  })

  it('can vary across seeds (not always the first variant)', () => {
    const prefixes = new Set(['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'].map(seed => petPixelSpecFor('DOG', seed)?.framePrefix))
    expect(prefixes.size).toBeGreaterThan(1)
  })
})

describe('PET_HOUSE_ACTION_ID', () => {
  it('is the world-actions.ts registry id the pet house furniture/pet is wired to', () => {
    // world-actions.ts imports this same constant for its "home.open-pet-house" action, so the
    // two can never silently drift apart — this pins the literal value itself.
    expect(PET_HOUSE_ACTION_ID).toBe('home.open-pet-house')
  })
})


describe('interior keyboard movement', () => {
  it('moves the same distance in one second at 30fps and 60fps', () => {
    for (const fps of [30, 60]) {
      const sprite = { x: 320, y: 300, scene: {}, anims: {}, setDepth: vi.fn(), play: vi.fn() }
      const controller = createDefaultController({
        scene: { input: { keyboard: { createCursorKeys: () => ({ up: { isDown: true }, down: { isDown: false }, left: { isDown: false }, right: { isDown: false } }), addKey: () => ({ isDown: false }) }, on: vi.fn(), off: vi.fn() }, events: { once: vi.fn() } },
        sprite, room: { tileSize: 32, cols: 20, rows: 12, collisions: [], doors: [] },
        sheet: 'char_1', speed: 180, isBlocked: () => false, onDoor: vi.fn(),
      } as any)
      for (let i = 0; i < fps; i++) controller.update(1000 / fps)
      expect(sprite.y).toBeCloseTo(120)
      expect(sprite.x).toBe(320)
    }
  })
})
