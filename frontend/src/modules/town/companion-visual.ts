import type Phaser from 'phaser'
import type { Pet } from '../partners/partner.types'
import type { Point } from './collision'
import { hashString } from './building-kit'

export type CompanionPet = Pick<Pet, 'publicId' | 'speciesCode' | 'name' | 'breed' | 'furColor'>
const variants: Record<string, string[]> = {
  DOG: ['dog_basenji_brown', 'dog_basenji_gray', 'dog_basenji_orange', 'dog_german_shepherd_brown', 'dog_german_shepherd_dark_brown', 'dog_german_shepherd_gray', 'dog_labrador_brown', 'dog_labrador_dark_brown', 'dog_labrador_white'],
  RABBIT: ['rabbit_baby_brown', 'rabbit_baby_gray', 'rabbit_baby_white', 'rabbit_brown', 'rabbit_brown_dark_ears', 'rabbit_gray', 'rabbit_gray_and_white', 'rabbit_spotted', 'rabbit_white'],
  BIRD: ['duck_white', 'duck_brown', 'duck_green_head', 'duck_duckling_yellow', 'chicken_brown', 'chicken_white', 'chicken_golden', 'rooster_brown'],
}
/** Kept compatible with the existing petPixelSpecFor seed, shared by indoor/outdoor callers. */
export function companionAppearance(pet: CompanionPet) {
  const choices = variants[pet.speciesCode]
  const defaults: Record<string, number> = { CAT: 0xd9a25b, HAMSTER: 0xcaa978, SNAKE: 0x81a86a, TURTLE: 0x769b59, FOX: 0xd57a39, DOG: 0xb78a5a, RABBIT: 0xe6dbca, BIRD: 0xd9bd55 }
  const fur = pet.furColor.toLowerCase()
  const color = /^#[0-9a-f]{6}$/i.test(fur) ? parseInt(fur.slice(1), 16)
    : /white|白/.test(fur) ? 0xeee8da : /black|黑/.test(fur) ? 0x514b52 : /gr[ae]y|灰/.test(fur) ? 0x919398
      : /brown|棕|褐/.test(fur) ? 0xa57c57 : /orange|橘|橙/.test(fur) ? 0xd89048 : defaults[pet.speciesCode] ?? 0xd9a25b
  return { atlasPrefix: choices?.[hashString(pet.breed || pet.publicId) % choices.length] ?? null,
    generatedPrefix: `companion-${pet.speciesCode.toLowerCase()}-${hashString(`${pet.breed}:${pet.furColor}`)}`, color }
}
// A right-facing silhouette per species. b=fur, d=outline, w=cream, e=eye, p=ear/nose, s=shell.
export const COMPANION_PIXELS: Record<string, string[]> = {
  CAT: [
    '             d   d', '             bd db', '             bbbbb', '    d        bbeeb', '   db    ddddbbbbp', '   db   dbbbbbbbbw', '   dbbbbbbbbbbbbw', '    dbbbbbbbbbbbw', '      dbbbbbbbbb', '       bd    bd', '       dd    dd',
  ],
  FOX: [
    '              d  d', '             dbddb', '             bbbbb', ' dd          bbeeb', 'dbbd   ddddddbbbbwp', 'dbbbdddbbbbbbbwww', ' dwbbbbbbbbbbwww', '  dwwbbbbbbbbb', '    dd bb   bb', '       dd   dd',
  ],
  HAMSTER: [
    '           pp  pp', '          dbbddbbd', '        ddbbbbbbbbd', '       dbbbbbbbeebp', '      dbbbbbbbwbbbw', '      dbbbbbbbwwwww', '      dbbbbbbbwwwww', '       dbbbbbbbbbd', '        pp    pp',
  ],
  SNAKE: [
    '             bbbbd', '            bbbebbd', '            bbbbbbp', '             bb', '     ddddd   bb', '    dbbbbbd  bb', '   dbbddbbbbbbb', '  dbbbbbbbddbbd', '   ddddddddddd',
  ],
  TURTLE: [
    '        dssssd', '      dssssssssd', '     dsssdssdsssd', '    dssssddssssssd bbb', '    dsssdsssdssssd bbeb', '    dssssddssssssdbbbbp', '     dddddddddddd', '       bb    bb',
  ],
  DOG: ['             bb', '            bbbbbb', '   b        bbeebbb', '   bb   bbbbbbbbp', '    bbbbbbbbbbbbw', '     bbbbbbbbbbb', '      bb     bb', '      dd     dd'],
  RABBIT: ['             bb bb', '             bp bp', '             bb bb', '             bbbbb', '        bbbbbbbeebp', '     wbbbbbbbbbbbw', '      bbbbbbbbbbb', '       bb     bb'],
  BIRD: ['             bbbb', '            bbbebpp', '             bbbbp', '    bb    bbbbbbb', '     bbbbbbbwbbb', '      bbbbbwwwbb', '       bbbbbbbb', '         p  p', '         pp pp'],
}
type Resources = { count: number; textures: string[]; animations: string[] }
const generated = new WeakMap<Phaser.Textures.TextureManager, Map<string, Resources>>()

export function createCompanionVisual(scene: Phaser.Scene, pet: CompanionPet, point: Point) {
  const appearance = companionAppearance(pet)
  let prefix = appearance.atlasPrefix
  const native = Boolean(prefix && scene.textures.exists('town') && scene.textures.get('town').has(`${prefix}_idle_1`))
  let resources: Resources | undefined
  if (!native) {
    prefix = appearance.generatedPrefix
    let registry = generated.get(scene.textures)
    if (!registry) { registry = new Map(); generated.set(scene.textures, registry) }
    resources = registry.get(prefix)
    if (!resources) {
      resources = { count: 0, textures: [], animations: [] }; registry.set(prefix, resources)
      const pixels = COMPANION_PIXELS[pet.speciesCode] ?? COMPANION_PIXELS.CAT!
      const palette: Record<string, number> = { b: appearance.color, d: 0x514a45, w: 0xf1e5cb, e: 0x252c30, p: 0xd18f81, s: 0x527548 }
      for (const pose of ['idle_1', 'walk_1', 'walk_2', 'walk_3', 'walk_4']) {
        const key = `${prefix}_${pose}`, graphics = scene.add.graphics()
        // Pixel art is drawn into Phaser's texture cache, never downloaded or written as files.
        pixels.forEach((row, y) => [...row].forEach((pixel, x) => {
          if (!palette[pixel]) return
          const feet = y >= pixels.length - 2 && pose.startsWith('walk')
          const offset = feet && (pose === 'walk_2' || pose === 'walk_4') ? (x < 12 ? 1 : -1) : 0
          graphics.fillStyle(palette[pixel]!, 1).fillRect((x + offset) * 2, (y + 20 - pixels.length) * 2, 2, 2)
        }))
        graphics.generateTexture(key, 48, 40); graphics.destroy(); resources.textures.push(key)
      }
    }
    resources.count++
  }
  const framePrefix = prefix!
  for (const action of ['walk', 'idle'] as const) {
    const key = `${framePrefix}-${action}`
    if (!scene.anims.exists(key)) {
      const frames = Array.from({ length: action === 'walk' ? 4 : 1 }, (_, index) => native
        ? { key: 'town', frame: `${framePrefix}_${action}_${index + 1}` }
        : { key: `${framePrefix}_${action}_${index + 1}` })
      scene.anims.create({ key, frames, frameRate: action === 'walk' ? 8 : 1, repeat: action === 'walk' ? -1 : 0 })
      resources?.animations.push(key)
    }
  }
  const sprite = native ? scene.add.sprite(point.x, point.y, 'town', `${framePrefix}_idle_1`) : scene.add.sprite(point.x, point.y, `${framePrefix}_idle_1`)
  sprite.setOrigin(.5, 1).setDepth(point.y + 1)
  if (native && pet.speciesCode === 'DOG') sprite.setScale(.72)
  sprite.play(`${framePrefix}-idle`, true)
  let destroyed = false
  const destroy = () => {
    if (destroyed) return
    destroyed = true; scene.events.off('shutdown', destroy); sprite.destroy()
    if (resources && --resources.count === 0) {
      resources.animations.forEach(key => scene.anims.remove(key))
      resources.textures.forEach(key => scene.textures.remove(key))
      generated.get(scene.textures)?.delete(framePrefix)
    }
  }
  scene.events.once('shutdown', destroy)
  return { sprite, framePrefix,
    update(movement: { moving: boolean; dx: number; dy: number; delta?: number }) {
      if (destroyed || !sprite.active) return
      if (Math.abs(movement.dx) > .01) sprite.setFlipX(movement.dx < 0)
      sprite.play(`${framePrefix}-${movement.moving ? 'walk' : 'idle'}`, true)
      sprite.setDepth(sprite.y + 1)
    }, destroy }
}
