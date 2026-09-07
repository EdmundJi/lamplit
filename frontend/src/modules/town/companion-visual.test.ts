import { EventEmitter } from 'node:events'
import type Phaser from 'phaser'
import { describe, expect, it, vi } from 'vitest'
import { companionAppearance, COMPANION_PIXELS, createCompanionVisual, type CompanionPet } from './companion-visual'
const pet: CompanionPet = { publicId: 'pet-a', speciesCode: 'CAT', name: '团子', breed: 'tabby', furColor: 'orange' }
function fixture(native = false) {
  const textures = new Set<string>(), animations = new Map<string, unknown>()
  const draw = vi.fn(), destroyGraphics = vi.fn()
  const sprites: any[] = []
  const scene = {
    events: new EventEmitter(),
    textures: { exists: (key: string) => key === 'town' ? native : textures.has(key), get: () => ({ has: () => native }), remove: vi.fn((key: string) => textures.delete(key)) },
    anims: { exists: (key: string) => animations.has(key), create: (config: { key: string }) => animations.set(config.key, config), remove: vi.fn((key: string) => animations.delete(key)) },
    add: {
      graphics: () => { const graphics = { fillStyle: () => graphics, fillRect: (...args: unknown[]) => { draw(...args); return graphics }, generateTexture: (key: string) => textures.add(key), destroy: destroyGraphics }; return graphics },
      sprite: (x: number, y: number, texture: string) => {
        const sprite = { x, y, texture, active: true, setOrigin: () => sprite, setDepth: () => sprite, setScale: () => sprite, setFlipX: vi.fn(() => sprite), play: vi.fn(() => sprite), destroy: vi.fn(() => { sprite.active = false }) }
        sprites.push(sprite); return sprite
      },
    },
  }
  return { scene: scene as unknown as Phaser.Scene, textures, animations, sprites, draw, destroyGraphics }
}
describe('shared companion appearance', () => {
  it('keeps appearance stable across home/street and renaming; honors fur color', () => {
    expect(companionAppearance(pet)).toEqual(companionAppearance({ ...pet, name: '新名字' }))
    expect(companionAppearance(pet).color).not.toBe(companionAppearance({ ...pet, furColor: 'white' }).color)
    const silhouettes = ['CAT', 'HAMSTER', 'SNAKE', 'TURTLE', 'FOX'].map(species => COMPANION_PIXELS[species]!.join('\n'))
    expect(new Set(silhouettes).size).toBe(5)
  })
  it('registers indoor-compatible walk/idle for all species and frees generated textures', () => {
    for (const speciesCode of Object.keys(COMPANION_PIXELS)) {
      const f = fixture(), visual = createCompanionVisual(f.scene, { ...pet, speciesCode }, { x: 50, y: 80 })
      expect(f.animations.has(`${visual.framePrefix}-walk`)).toBe(true)
      expect(f.animations.has(`${visual.framePrefix}-idle`)).toBe(true)
      expect(f.textures.size).toBe(5); expect(f.draw).toHaveBeenCalled()
      visual.update({ moving: true, dx: -2, dy: 1 })
      expect(f.sprites[0].setFlipX).toHaveBeenCalledWith(true)
      visual.destroy(); visual.destroy()
      expect(f.textures.size).toBe(0); expect(f.animations.size).toBe(0)
      expect(f.sprites[0].destroy).toHaveBeenCalledTimes(1)
      expect(f.scene.events.listenerCount('shutdown')).toBe(0)
    }
  })
  it('shares generated textures until the final scene owner releases them', () => {
    const f = fixture(), a = createCompanionVisual(f.scene, pet, { x: 0, y: 0 }), b = createCompanionVisual(f.scene, pet, { x: 0, y: 0 })
    a.destroy(); expect(f.textures.size).toBe(5)
    f.scene.events.emit('shutdown'); expect(f.textures.size).toBe(0)
    expect(f.sprites[1].destroy).toHaveBeenCalledTimes(1); b.destroy()
  })
  it('reuses atlas dog/rabbit/bird assets without drawing or removing atlas textures', () => {
    for (const speciesCode of ['DOG', 'RABBIT', 'BIRD']) {
      const f = fixture(true), visual = createCompanionVisual(f.scene, { ...pet, speciesCode }, { x: 0, y: 0 })
      expect(f.sprites[0].texture).toBe('town'); expect(f.draw).not.toHaveBeenCalled()
      visual.destroy(); expect(f.scene.textures.remove).not.toHaveBeenCalled()
    }
  })
})
