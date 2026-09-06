/**
 * 通用室内场景: renders any `RoomMapData` (map-loader.ts) — floor/wall tile layers, static
 * furniture, data-driven slots, seated residents, and doors — instead of the one hand-built room
 * academy.scene.ts draws. Registering several rooms under different scene keys (see
 * `interiorSceneKey`) is how "多栋建筑都能进" gets built without a bespoke scene per building.
 *
 * Kept self-contained the same way academy.scene.ts is: no import from town.engine.ts or
 * academy.scene.ts, atlas/character conventions (32x64 sheets, row layout, `char_${n}` keys,
 * `interior-atlas`) reimplemented locally so this file has no dependency on files another agent is
 * actively editing. The only import from elsewhere in this module is map-loader.ts (this task's
 * own file) and building-kit.ts's `hashString`, which is a pure, stable one-liner.
 */
import type PhaserNs from 'phaser'
import { hashString } from './building-kit'
import { findPath } from './pathfinding'
import { canStand, nearestStandable, type Point } from './collision'
import { canUseFromHere, distanceToBox, furnitureApproach, restingFurniture, roomNavigation } from './interior-interaction'
import {
  assignSeats,
  clampToRoom,
  collidesAt,
  defaultInteractionHit,
  doorAt,
  evaluateSlots,
  type RoomDoor,
  type RoomMapData,
  type RoomMetrics,
  type RoomResident,
} from './map-loader'

const ASSETS = '/assets/town'
const FONT = '"PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif'

type Direction = 'right' | 'up' | 'left' | 'down'
const DIRECTION_INDEX: Record<Direction, number> = { right: 0, up: 1, left: 2, down: 3 }

/** `interior:${roomId}` — the scene key an orchestrator must pass to `game.scene.add`/`.get`/`.stop`.
 * Exported so it can register/tear down a room without instantiating the scene class first. */
export function interiorSceneKey(roomId: string): string {
  return `interior:${roomId}`
}

/** A free-roaming avatar (the player, or in principle a visiting NPC) distinct from seated
 * residents. Omit it entirely for a room where nobody walks around (e.g. the study room, whose
 * residents are all seated) — this is what keeps that room's behaviour identical to today's
 * academy.scene.ts. */
export type InteriorPlayer = {
  characterSheet: number
  x?: number
  y?: number
  /** px/s. Defaults to a comfortable walking pace. */
  speed?: number
}

/** Minimal surface a movement controller needs; `createDefaultController` below is the built-in
 * implementation, but any object satisfying `RoomController` can be passed as `createController`
 * to replace it (e.g. an 8-direction, animation-aware controller) without touching this file. */
export type RoomController = {
  update: (deltaMs: number) => void
  destroy?: () => void
  /** Queue a legal walk; arrival fires once, and only after actually reaching the destination. */
  moveTo?: (target: Point, onArrival: () => void) => boolean
  /** Cancel walking or get up. Returns whether an active interaction was consumed. */
  cancel?: () => boolean
  sitAt?: (point: Point) => void
  isResting?: () => boolean
}

export type ControllerContext = {
  scene: PhaserNs.Scene
  sprite: PhaserNs.GameObjects.Sprite
  room: RoomMapData
  sheet: string
  /** px/s, from `InteriorPlayer.speed` (defaults to a comfortable walking pace). */
  speed: number
  isBlocked: (x: number, y: number) => boolean
  onDoor: (door: RoomDoor) => void
  onPosition?: (x: number, y: number, facing: Direction) => void
}

export type InteriorOptions = {
  room: RoomMapData
  /** Numeric facts the room's data slots read (see map-loader.ts's `evaluateSlots`). Defaults to `{}`. */
  metrics?: RoomMetrics
  /** Residents seated at `room.seats`, in order — same shape/semantics as academy.scene.ts's
   * `AcademyResident[]` (state drives which seated animation row plays). */
  residents?: RoomResident[]
  player?: InteriorPlayer
  /** Fired when the player leaves through a door (click, or walking into it): the door's `target`
   * and its `id`, so the orchestrator decides what "town" or another room id means. */
  onExit: (target: string, doorId: string) => void
  /**
   * Fired after the player walks close to a clicked piece of interactive furniture (M3-3/M3-4, e.g. the desk, the
   * achievement wall, the pet house) or the pet itself: the furniture's `interactive.actionId`
   * (a world-actions.ts registry id) and the furniture/pet's own `id`. This scene never runs the
   * action itself — same "the map only says what, not how" split as `onExit` — the orchestrator is
   * expected to call `runWorldAction(actionId, ctx)`. Omit to leave interactive furniture inert
   * (clicking does nothing), which keeps this scene's default behaviour unchanged for callers that
   * haven't wired it up yet.
   */
  onInteract?: (actionId: string, id: string) => void
  onPosition?: (x: number, y: number, facing: Direction) => void
  /** The resident's pet, drawn at whichever furniture piece's `interactive.actionId` is
   * `PET_HOUSE_ACTION_ID` (see map-loader.ts docs on `RoomInteraction`). No such furniture piece in
   * the room, or no `pet` given at all, and nothing pet-shaped is drawn — same "optional, defaults
   * to today's behaviour" shape as every other new field here. */
  pet?: InteriorPet
  /** Replace the built-in keyboard/click mover. See `ControllerContext`. */
  createController?: (ctx: ControllerContext) => RoomController
}

/** `characters/c{nn}.png`-style sheet index a resident should render as, same numbering as
 * town.engine.ts's private characterSheet(). Re-derived here (not imported) for the same
 * self-containment reason as academy.scene.ts. */
export function characterSheetFor(publicId: string): number {
  return 1 + (hashString(`${publicId}:look`) % 20)
}

// ---------- pets (M3-5) ----------

export type InteriorPetSpecies = 'CAT' | 'DOG' | 'HAMSTER' | 'SNAKE' | 'RABBIT' | 'BIRD' | 'TURTLE' | 'FOX'

/**
 * A resident's pet. Deliberately has no x/y of its own: where it lives in the room is authored
 * data (whichever furniture piece's `interactive.actionId` is `PET_HOUSE_ACTION_ID`), not
 * something the caller needs to compute — see `drawPet()`.
 */
export type InteriorPet = {
  id: string
  species: InteriorPetSpecies
  /** Breed label (from partners/pet-options.ts); only used to pick a stable pixel variant, never
   * displayed — an unrecognised breed just hashes like any other string would. */
  breed?: string
}

/** world-actions.ts action id the room's pet spot is wired to (M3-4). Exported so a room map (or a
 * test) can check a furniture piece's `interactive.actionId` against the same constant this file
 * uses to find where to draw the pet. */
export const PET_HOUSE_ACTION_ID = 'home.open-pet-house'

/**
 * town-atlas.json frame prefixes for the three species Modern Farm actually shipped pixel art for
 * (grepped from the built atlas — see build-town-assets.py's `FARM_ANIMALS`, M0-2). Every other
 * species (cat/hamster/snake/turtle/fox) has no pixel sprite at all: §0.1's correction is that
 * pets are Rive vector animations, so those get a static placeholder instead of a fabricated walk
 * cycle. There is no generic "bird" sheet in the pack either — a duck/chicken/rooster is the
 * closest thing Modern Farm has to a small pet bird, so BIRD borrows from those.
 */
const DOG_FRAME_PREFIXES = [
  'dog_basenji_brown', 'dog_basenji_gray', 'dog_basenji_orange',
  'dog_german_shepherd_brown', 'dog_german_shepherd_dark_brown', 'dog_german_shepherd_gray',
  'dog_labrador_brown', 'dog_labrador_dark_brown', 'dog_labrador_white',
]
const RABBIT_FRAME_PREFIXES = [
  'rabbit_baby_brown', 'rabbit_baby_gray', 'rabbit_baby_white', 'rabbit_brown',
  'rabbit_brown_dark_ears', 'rabbit_gray', 'rabbit_gray_and_white', 'rabbit_spotted', 'rabbit_white',
]
const BIRD_FRAME_PREFIXES = ['duck_white', 'duck_brown', 'duck_green_head', 'duck_duckling_yellow', 'chicken_brown', 'chicken_white', 'chicken_golden', 'rooster_brown']

/** Matches build-town-assets.py's `FARM_WALK_FRAMES` — every animal sheet was cut into exactly
 * this many walk-cycle frames plus one idle pose. */
const FARM_WALK_FRAMES = 4

export type PetPixelSpec = {
  /** e.g. "dog_labrador_brown"; frames are `${framePrefix}_idle_1` / `_walk_1.._${walkFrames}`. */
  framePrefix: string
  walkFrames: number
  /** Only dogs have a ready-made "asleep" pose (`doghouse_sleep_*`, from the Doghouse sheet) — other
   * species fall back to standing still rather than a fabricated sleeping frame. */
  canSleep: boolean
}

/**
 * Pure species/breed -> pixel-asset lookup (no Phaser). Returns `null` for a species Modern Farm
 * shipped no sprite for at all — the caller's cue to draw the placeholder-house-and-Rive-panel
 * path (M3-5) instead. Otherwise picks one of that species' variants deterministically from
 * `seed` (a pet's own id/breed), so the same pet always looks the same across renders.
 */
export function petPixelSpecFor(species: InteriorPetSpecies, seed: string): PetPixelSpec | null {
  const table: Partial<Record<InteriorPetSpecies, string[]>> = { DOG: DOG_FRAME_PREFIXES, RABBIT: RABBIT_FRAME_PREFIXES, BIRD: BIRD_FRAME_PREFIXES }
  const prefixes = table[species]
  if (!prefixes) return null
  return { framePrefix: prefixes[hashString(seed) % prefixes.length], walkFrames: FARM_WALK_FRAMES, canSleep: species === 'DOG' }
}

export function createInteriorScene(Phaser: typeof PhaserNs, options: InteriorOptions): typeof Phaser.Scene {
  const room = options.room
  const metrics = options.metrics ?? {}
  const residents = options.residents ?? []
  const seatAssignments = assignSeats(room, residents)
  const slotInstances = evaluateSlots(room, metrics)
  const worldWidth = room.cols * room.tileSize
  const worldHeight = room.rows * room.tileSize
  const key = interiorSceneKey(room.id)

  class InteriorScene extends Phaser.Scene {
    escHandler: ((event: KeyboardEvent) => void) | null = null
    worldPress = false
    player: PhaserNs.GameObjects.Sprite | null = null
    controller: RoomController | null = null
    lastDoor: RoomDoor | null = null
    nearbyLabels: { piece: RoomMapData['furniture'][number]; text: PhaserNs.GameObjects.Text; rest: boolean }[] = []

    constructor() { super(key) }

    preload() {
      if (!this.textures.exists('interior')) {
        this.load.atlas('interior', `${ASSETS}/interior-atlas.png`, `${ASSETS}/interior-atlas.json`)
      }
      // The pet's pixel frames (and the placeholder doghouse frame) live in the outdoor town atlas
      // (build-town-assets.py merges Farm frames into town-atlas, not interior-atlas) — town.engine.ts
      // has almost certainly already loaded this under the same key by the time a room is entered, so
      // `exists` makes this a no-op in the common case rather than a duplicate fetch.
      if (options.pet && !this.textures.exists('town')) {
        this.load.atlas('town', `${ASSETS}/town-atlas.png`, `${ASSETS}/town-atlas.json`)
      }
      const sheets = new Set(residents.map(item => item.characterSheet))
      if (options.player) sheets.add(options.player.characterSheet)
      for (const index of sheets) {
        const sheetKey = `char_${index}`
        if (this.textures.exists(sheetKey)) continue
        this.load.spritesheet(sheetKey, `${ASSETS}/characters/c${String(index).padStart(2, '0')}.png`, { frameWidth: 32, frameHeight: 64 })
      }
    }

    create() {
      this.lastDoor = null
      this.worldPress = false
      this.nearbyLabels = []
      this.cameras.main.setBackgroundColor(room.backgroundColor)
      this.drawLayer(room.layers.floor, 0)
      this.drawLayer(room.layers.walls, 2)
      this.drawFurniture()
      this.drawRestSpots()
      this.drawPet()
      this.drawSlots()
      this.drawSeats()
      this.drawDoors()
      this.spawnPlayer()
      this.plate(worldWidth / 2, 8, room.title, '#fff4e8', '#35644f')
      this.setupCamera()
      this.setupInput()
      this.scale.on('resize', this.setupCamera, this)
      this.events.once('shutdown', () => this.scale.off('resize', this.setupCamera, this))
    }

    // ---------- world ----------

    drawLayer(grid: RoomMapData['layers']['floor'], baseDepth: number) {
      grid.forEach((rowTiles, row) => {
        rowTiles.forEach((frame, col) => {
          if (!frame) return
          this.add.image(col * room.tileSize, row * room.tileSize, 'interior', frame).setOrigin(0).setDepth(baseDepth)
        })
      })
    }

    placeFurniture(frame: string, x: number, y: number, originX = 0.5, originY = 1, depth?: number, displayWidth?: number, displayHeight?: number) {
      const image = this.add.image(x, y, 'interior', frame).setOrigin(originX, originY).setDepth(depth ?? y)
      if (displayWidth !== undefined || displayHeight !== undefined) {
        image.setDisplaySize(displayWidth ?? image.width, displayHeight ?? image.height)
      }
      return image
    }

    drawFurniture() {
      for (const piece of room.furniture) {
        this.placeFurniture(piece.frame, piece.x, piece.y, piece.originX, piece.originY, piece.depth, piece.displayWidth, piece.displayHeight)
        if (piece.interactive) this.wireInteractive(piece)
      }
    }

    /** M3-3/M3-4: a click zone over the furniture's hit-box, forwarding to `onInteract` — this
     * scene never decides what "打开书桌" means, same split as `drawDoors`' `onExit`. Uses
     * `defaultInteractionHit` (not a hand-rolled box) so the visible click target and map-loader's
     * pure `interactableAt` can never disagree about where the piece is clickable. */
    wireInteractive(piece: RoomMapData['furniture'][number]) {
      const interaction = piece.interactive
      if (!interaction) return
      const box = interaction.hit ?? defaultInteractionHit(piece, room.tileSize)
      if (interaction.label) this.addNearbyLabel(piece, interaction.label)
      const zone = this.add.zone(box.x, box.y, box.w, box.h).setOrigin(0).setInteractive({ useHandCursor: true })
      zone.on('pointerup', (pointer: PhaserNs.Input.Pointer) => {
        if (this.acceptPress(pointer)) this.approach(piece, () => options.onInteract?.(interaction.actionId, piece.id))
      })
    }

    acceptPress(pointer: PhaserNs.Input.Pointer) {
      return this.worldPress && pointer?.event?.target === this.game.canvas && !roomInputBlocked()
    }

    approach(piece: RoomMapData['furniture'][number], action: () => void, frontOnly = false) {
      if (!this.player) { action(); return }
      this.controller?.cancel?.()
      const target = furnitureApproach(room, this.player, piece, frontOnly)
      if (!target) return
      if (this.controller?.moveTo) this.controller.moveTo(target, action)
      else if (canUseFromHere(room, this.player, target)) action()
    }

    addNearbyLabel(piece: RoomMapData['furniture'][number], label: string, rest = false) {
      const box = piece.interactive?.hit ?? defaultInteractionHit(piece, room.tileSize)
      const text = this.plate(box.x + box.w / 2, box.y - 44, label, '#fff4e8', 'rgba(49,43,37,.85)')
      text.setVisible(!options.player)
      this.nearbyLabels.push({ piece, text, rest })
    }

    drawRestSpots() {
      if (!options.player) return
      for (const piece of restingFurniture(room, seatAssignments.map(item => item.seat))) {
        const box = defaultInteractionHit(piece, room.tileSize)
        this.addNearbyLabel(piece, '坐一会儿', true)
        const zone = this.add.zone(box.x, box.y, box.w, box.h).setOrigin(0).setInteractive({ useHandCursor: true })
        zone.on('pointerup', (pointer: PhaserNs.Input.Pointer) => {
          if (!this.acceptPress(pointer)) return
          if (this.controller?.isResting?.()) { this.controller.cancel?.(); return }
          this.approach(piece, () => {
            if (!options.player) return
            this.ensureSeatAnimations(`char_${options.player.characterSheet}`)
            this.controller?.sitAt?.({ x: piece.x, y: piece.y + 6 })
          }, true)
        })
      }
    }

    updateNearbyLabels() {
      if (!this.player) return
      let nearest: typeof this.nearbyLabels[number] | undefined
      let distance = 86
      for (const item of this.nearbyLabels) {
        item.text.setVisible(false)
        const box = item.piece.interactive?.hit ?? defaultInteractionHit(item.piece, room.tileSize)
        const measured = distanceToBox(this.player, box)
        if (measured < distance) { distance = measured; nearest = item }
      }
      if (nearest && !roomInputBlocked()) {
        nearest.text.setVisible(true)
        const box = nearest.piece.interactive?.hit ?? defaultInteractionHit(nearest.piece, room.tileSize)
        // Keep the short hint above both the prop and the avatar's head, including side approaches.
        nearest.text.setY(Math.min(box.y - 44, this.player.y - 76))
        if (nearest.rest) nearest.text.setText(this.controller?.isResting?.() ? '休息中 · 点击地面起身' : '坐一会儿')
      }
    }

    // ---------- pet (M3-5) ----------

    /** Finds the furniture piece that marks the pet's spot (its `interactive.actionId` is
     * `PET_HOUSE_ACTION_ID` — see map-loader.ts's `RoomInteraction`) and draws the resident's pet
     * there: an animated pixel critter for dog/rabbit/bird, or a static placeholder + Rive panel
     * hookup for every other species (§0.1's "宠物是 Rive 矢量动画" correction). Silently does
     * nothing if the room has no such furniture piece or the caller passed no `pet` — this keeps
     * every room that doesn't opt in exactly as it renders today. */
    drawPet() {
      const pet = options.pet
      if (!pet) return
      const spot = room.furniture.find(item => item.interactive?.actionId === PET_HOUSE_ACTION_ID)
      if (!spot) return
      const spec = petPixelSpecFor(pet.species, pet.breed ?? pet.id)
      if (!spec) this.drawPlaceholderPet(spot)
      else this.drawPixelPet(spec, spot)
    }

    drawPlaceholderPet(spot: RoomMapData['furniture'][number]) {
      const sprite = this.add.image(spot.x, spot.y, 'town', 'doghouse_sleep_1').setOrigin(0.5, 1).setDepth(spot.y + 1)
      sprite.setDisplaySize(40, 40)
      sprite.setInteractive({ useHandCursor: true })
      sprite.on('pointerup', (pointer: PhaserNs.Input.Pointer) => { if (this.worldPress && pointer?.event?.target === this.game.canvas) options.onInteract?.(PET_HOUSE_ACTION_ID, spot.id) })
    }

    drawPixelPet(spec: PetPixelSpec, spot: RoomMapData['furniture'][number]) {
      this.ensurePetAnimations(spec)
      const sprite = this.add.sprite(spot.x, spot.y, 'town', `${spec.framePrefix}_idle_1`).setOrigin(0.5, 1).setDepth(spot.y + 1)
      sprite.setInteractive({ useHandCursor: true })
      sprite.on('pointerup', (pointer: PhaserNs.Input.Pointer) => { if (this.worldPress && pointer?.event?.target === this.game.canvas) options.onInteract?.(PET_HOUSE_ACTION_ID, spot.id) })
      this.schedulePetWander(sprite, spec, spot)
    }

    ensurePetAnimations(spec: PetPixelSpec) {
      if (this.anims.exists(`${spec.framePrefix}-walk`)) return
      const walk = Array.from({ length: spec.walkFrames }, (_, i) => ({ key: 'town', frame: `${spec.framePrefix}_walk_${i + 1}` }))
      this.anims.create({ key: `${spec.framePrefix}-walk`, frames: walk, frameRate: 6, repeat: -1 })
      this.anims.create({ key: `${spec.framePrefix}-idle`, frames: [{ key: 'town', frame: `${spec.framePrefix}_idle_1` }], frameRate: 1 })
      if (spec.canSleep && !this.anims.exists('doghouse-sleep')) {
        const sleep = Array.from({ length: FARM_WALK_FRAMES }, (_, i) => ({ key: 'town', frame: `doghouse_sleep_${i + 1}` }))
        this.anims.create({ key: 'doghouse-sleep', frames: sleep, frameRate: 3, repeat: -1 })
      }
    }

    /** A short wander-then-rest loop, tween-driven rather than hooked into the per-frame `update`
     * loop the player/controller use — the pet doesn't need input handling or collision, just
     * something that reads as "alive" from across the room. Dogs occasionally walk back to their
     * house and play the ready-made `doghouse_sleep_*` cycle ("能睡" — M3-5); other species have no
     * sleeping pose to fall back on, so they just idle in place between wanders. */
    schedulePetWander(sprite: PhaserNs.GameObjects.Sprite, spec: PetPixelSpec, spot: RoomMapData['furniture'][number]) {
      const roam = () => {
        const goingToSleep = spec.canSleep && Math.random() < 0.25
        const targetX = goingToSleep ? spot.x : spot.x + Phaser.Math.Between(-24, 24)
        const targetY = goingToSleep ? spot.y : spot.y + Phaser.Math.Between(-10, 6)
        sprite.setFlipX(targetX < sprite.x)
        sprite.play(`${spec.framePrefix}-walk`, true)
        this.tweens.add({
          targets: sprite,
          x: targetX,
          y: targetY,
          duration: 1400,
          onComplete: () => {
            sprite.setDepth(sprite.y + 1)
            if (goingToSleep) {
              sprite.play('doghouse-sleep', true)
              this.time.delayedCall(5000 + Math.random() * 3000, () => {
                sprite.play(`${spec.framePrefix}-idle`, true)
                this.time.delayedCall(1200, roam)
              })
            } else {
              sprite.play(`${spec.framePrefix}-idle`, true)
              this.time.delayedCall(1500 + Math.random() * 1500, roam)
            }
          },
        })
      }
      this.time.delayedCall(500 + Math.random() * 1000, roam)
    }

    drawSlots() {
      for (const instance of slotInstances) {
        this.add.image(instance.x, instance.y, 'interior', instance.frame).setOrigin(instance.originX, instance.originY).setDepth(instance.depth)
      }
    }

    drawSeats() {
      for (const { seat, resident } of seatAssignments) {
        this.ensureSeatAnimations(`char_${resident.characterSheet}`)
        const sheet = `char_${resident.characterSheet}`
        const sprite = this.add.sprite(seat.x, seat.y, sheet).setOrigin(0.5, 1).setDepth(seat.y)
        sprite.play(`${sheet}-seat-${resident.state}`)
        const background = resident.isSelf ? 'rgba(200,95,71,.92)' : 'rgba(40,30,26,.72)'
        const label = resident.isSelf ? `${resident.displayName}（我）` : resident.displayName
        this.plate(seat.x, seat.y + 4, label, '#fff', background)
      }
    }

    drawDoors() {
      for (const door of room.doors) {
        const cx = door.rect.x + door.rect.w / 2
        if (door.label) this.plate(cx, door.rect.y - 14, door.label, '#fff4e8', '#3b312c')
        const zone = this.add.zone(door.rect.x, door.rect.y, door.rect.w, door.rect.h).setOrigin(0).setInteractive({ useHandCursor: true })
        zone.on('pointerup', (pointer: PhaserNs.Input.Pointer) => { if (this.acceptPress(pointer)) { this.controller?.cancel?.(); options.onExit(door.target, door.id) } })
      }
    }

    // ---------- player ----------

    spawnPlayer() {
      if (!options.player) return
      const sheet = `char_${options.player.characterSheet}`
      this.ensureCharacterAnimations(sheet)
      const x = options.player.x ?? room.spawn.x
      const y = options.player.y ?? room.spawn.y
      const sprite = this.add.sprite(x, y, sheet).setOrigin(0.5, 1).setDepth(y)
      sprite.play(`${sheet}-idle-down`)
      this.player = sprite
      const context: ControllerContext = {
        scene: this,
        sprite,
        room,
        sheet,
        speed: options.player.speed ?? DEFAULT_SPEED,
        isBlocked: (px, py) => collidesAt(room, px, py, 14, 10),
        onPosition: options.onPosition,
        onDoor: door => {
          if (this.lastDoor?.id === door.id) return
          this.lastDoor = door
          options.onExit(door.target, door.id)
        },
      }
      this.controller = (options.createController ?? createDefaultController)(context)
      const updateController = (_time: number, delta: number) => { this.controller?.update(delta); this.updateNearbyLabels() }
      this.events.on('update', updateController)
      this.events.once('shutdown', () => {
        this.events.off('update', updateController)
        this.controller?.destroy?.()
        this.controller = null
      })
    }

    // ---------- helpers ----------

    plate(x: number, y: number, text: string, color: string, background: string) {
      return this.add.text(x, y, text, {
        fontFamily: FONT, fontSize: '12px', color, backgroundColor: background, padding: { x: 6, y: 2 },
      }).setOrigin(0.5, 0).setDepth(9000).setResolution(2)
    }

    ensureSeatAnimations(sheet: string) {
      if (this.anims.exists(`${sheet}-seat-idle`)) return
      const columns = Math.floor((this.textures.get(sheet).getSourceImage() as HTMLImageElement).width / 32)
      const range = (row: number, start: number, length = 6) => Array.from({ length }, (_, i) => ({ key: sheet, frame: row * columns + start + i }))
      this.anims.create({ key: `${sheet}-seat-idle`, frames: range(4, 0), frameRate: 4, repeat: -1 })
      this.anims.create({ key: `${sheet}-seat-phone`, frames: range(6, 3), frameRate: 6, repeat: -1 })
      this.anims.create({ key: `${sheet}-seat-reading`, frames: range(7, 0), frameRate: 5, repeat: -1 })
    }

    ensureCharacterAnimations(sheet: string) {
      if (this.anims.exists(`${sheet}-idle-down`)) return
      const columns = Math.floor((this.textures.get(sheet).getSourceImage() as HTMLImageElement).width / 32)
      const range = (row: number, start: number, length = 6) => Array.from({ length }, (_, i) => ({ key: sheet, frame: row * columns + start + i }))
      for (const direction of Object.keys(DIRECTION_INDEX) as Direction[]) {
        const offset = DIRECTION_INDEX[direction] * 6
        this.anims.create({ key: `${sheet}-idle-${direction}`, frames: range(1, offset), frameRate: 6, repeat: -1 })
        this.anims.create({ key: `${sheet}-walk-${direction}`, frames: range(2, offset), frameRate: 9, repeat: -1 })
      }
    }

    setupCamera() {
      const camera = this.cameras.main
      camera.removeBounds()
      camera.setViewport(0, 0, this.scale.width, this.scale.height)
      const fit = Math.min((this.scale.width - 32) / worldWidth, (this.scale.height - 172) / worldHeight)
      camera.setZoom(Math.max(0.25, Math.floor(fit * 4) / 4))
      camera.centerOn(worldWidth / 2, worldHeight / 2)
    }

    setupInput() {
      this.input.on('pointerdown', (pointer: PhaserNs.Input.Pointer) => { this.worldPress = pointer?.event?.target === this.game.canvas })
      this.input.on('pointerup', () => { this.worldPress = false })
      const firstDoor = room.doors[0]
      this.escHandler = (event: KeyboardEvent) => {
        if (event.defaultPrevented || roomInputBlocked()) return
        event.preventDefault()
        if (this.controller?.cancel?.()) return
        if (firstDoor) options.onExit(firstDoor.target, firstDoor.id)
      }
      this.input.keyboard?.on('keydown-ESC', this.escHandler)
      this.events.once('shutdown', () => {
        if (this.escHandler) this.input.keyboard?.off('keydown-ESC', this.escHandler)
      })
    }
  }

  return InteriorScene
}

// ---------- default movement controller ----------

/** px/s for the built-in controller; InteriorPlayer.speed is reserved for a future per-room override. */
const DEFAULT_SPEED = 180

/** Keyboard (arrows/WASD) + click-to-walk, four-facing-direction animation, AABB collision via
 * map-loader.ts's `collidesAt`. Deliberately simple — a placeholder any richer controller (e.g.
 * true 8-direction movement/animation) can replace via `InteriorOptions.createController` without
 * this file changing. */
function roomInputBlocked(): boolean {
  return Boolean(document.querySelector('[role="dialog"], .resident-moment')
    || document.activeElement?.matches('input, textarea, select, [contenteditable="true"]'))
}

export function createDefaultController(ctx: ControllerContext): RoomController {
  const { scene, sprite, room, sheet } = ctx
  const cursors = scene.input.keyboard?.createCursorKeys()
  const keys = Object.fromEntries(['A', 'D', 'W', 'S'].map(key => [key, scene.input.keyboard?.addKey(key)]))
  const navigation = roomNavigation(room)
  let clickTarget: Point | null = null
  let waypoints: Point[] = []
  let arrival: (() => void) | null = null
  let restingReturn: Point | null = null
  let facing: Direction = 'down'
  let worldPress = false
  let destroyed = false
  // Older cached maps can spawn inside an exit. Leave it before allowing walk-to-exit.
  let previousDoor = doorAt(room, sprite.x, sprite.y)?.id

  function cancel() {
    const active = Boolean(clickTarget || arrival || restingReturn)
    clickTarget = null
    waypoints = []
    arrival = null
    if (restingReturn) {
      sprite.x = restingReturn.x
      sprite.y = restingReturn.y
      restingReturn = null
      sprite.setDepth(sprite.y)
      sprite.play(`${sheet}-idle-${facing}`, true)
    }
    return active
  }

  function moveTo(target: Point, onArrival: () => void): boolean {
    cancel()
    if (destroyed || roomInputBlocked()) return false
    const path = findPath({ x: sprite.x, y: sprite.y }, target, navigation)
    if (!path) return false
    waypoints = path
    clickTarget = waypoints.shift() ?? null
    arrival = onArrival
    return true
  }

  const pointerDown = (pointer: PhaserNs.Input.Pointer) => {
    worldPress = pointer?.event?.target === scene.game.canvas && !roomInputBlocked()
  }
  const pointerUp = (pointer: PhaserNs.Input.Pointer, over: PhaserNs.GameObjects.GameObject[] = []) => {
    const accepted = worldPress && pointer?.event?.target === scene.game.canvas && !roomInputBlocked()
    worldPress = false
    // Object pointerup handles furniture/doors first; don't replace its new destination.
    if (!accepted || over.some(object => object.input?.enabled)) return
    cancel()
    const world = scene.cameras.main.getWorldPoint(pointer.x, pointer.y)
    const target = nearestStandable(clampToRoom(room, world.x, world.y), navigation)
    moveTo(target, () => {})
  }
  scene.input.on('pointerdown', pointerDown)
  scene.input.on('pointerup', pointerUp)

  function destroy() {
    if (destroyed) return
    destroyed = true
    cancel()
    scene.input.off('pointerdown', pointerDown)
    scene.input.off('pointerup', pointerUp)
    scene.events.off('shutdown', destroy)
  }
  scene.events.once('shutdown', destroy)

  function move(dx: number, dy: number, deltaMs: number, stopAtTarget = false) {
    if (dx === 0 && dy === 0) return false
    const length = Math.hypot(dx, dy) || 1
    const distance = ctx.speed * Math.min(deltaMs, 100) / 1000
    const step = stopAtTarget ? Math.min(length, distance) : distance
    const oldX = sprite.x, oldY = sprite.y
    // Collision-checked substeps prevent frame stalls from jumping over thin furniture.
    const steps = Math.max(1, Math.ceil(step / 4))
    for (let i = 0; i < steps; i++) {
      const nx = sprite.x + dx / length * step / steps
      const ny = sprite.y + dy / length * step / steps
      if (canStand({ x: nx, y: sprite.y }, navigation) && !ctx.isBlocked(nx, sprite.y)) sprite.x = nx
      if (canStand({ x: sprite.x, y: ny }, navigation) && !ctx.isBlocked(sprite.x, ny)) sprite.y = ny
    }
    facing = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up')
    return sprite.x !== oldX || sprite.y !== oldY
  }

  return {
    moveTo,
    cancel,
    destroy,
    sitAt(point) {
      cancel()
      restingReturn = { x: sprite.x, y: sprite.y }
      sprite.x = point.x
      sprite.y = point.y
      sprite.setDepth(sprite.y)
      sprite.play(`${sheet}-seat-idle`, true)
    },
    isResting: () => Boolean(restingReturn),
    update(deltaMs: number) {
      if (destroyed || !sprite.scene || !sprite.anims) return
      if (roomInputBlocked()) {
        // Closing another UI must not fire a queued furniture action.
        if (!restingReturn) { cancel(); sprite.play(`${sheet}-idle-${facing}`, true) }
        return
      }
      let dx = 0, dy = 0
      if (cursors?.left.isDown || keys.A?.isDown) dx -= 1
      if (cursors?.right.isDown || keys.D?.isDown) dx += 1
      if (cursors?.up.isDown || keys.W?.isDown) dy -= 1
      if (cursors?.down.isDown || keys.S?.isDown) dy += 1
      let moving = false
      if (dx !== 0 || dy !== 0) {
        cancel()
        moving = move(dx, dy, deltaMs)
      } else if (restingReturn) {
        ctx.onPosition?.(sprite.x, sprite.y, facing)
        return
      } else if (clickTarget) {
        const remainingX = clickTarget.x - sprite.x
        const remainingY = clickTarget.y - sprite.y
        if (Math.hypot(remainingX, remainingY) < 1) {
          clickTarget = waypoints.shift() ?? null
          if (!clickTarget) {
            const completed = arrival
            arrival = null
            completed?.()
            if (restingReturn || roomInputBlocked()) return
          }
        } else {
          moving = move(remainingX, remainingY, deltaMs, true)
          if (!moving) cancel()
        }
      }
      sprite.setDepth(sprite.y)
      sprite.play(`${sheet}-${moving ? 'walk' : 'idle'}-${facing}`, true)
      ctx.onPosition?.(sprite.x, sprite.y, facing)
      const door = doorAt(room, sprite.x, sprite.y)
      if (door && door.id !== previousDoor && moving) { cancel(); ctx.onDoor(door) }
      previousDoor = door?.id
    },
  }
}
