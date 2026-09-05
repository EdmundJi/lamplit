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
import {
  assignSeats,
  clampToRoom,
  collidesAt,
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
  /** Replace the built-in keyboard/click mover. See `ControllerContext`. */
  createController?: (ctx: ControllerContext) => RoomController
}

/** `characters/c{nn}.png`-style sheet index a resident should render as, same numbering as
 * town.engine.ts's private characterSheet(). Re-derived here (not imported) for the same
 * self-containment reason as academy.scene.ts. */
export function characterSheetFor(publicId: string): number {
  return 1 + (hashString(`${publicId}:look`) % 20)
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
    escHandler: (() => void) | null = null
    player: PhaserNs.GameObjects.Sprite | null = null
    controller: RoomController | null = null
    lastDoor: RoomDoor | null = null

    constructor() { super(key) }

    preload() {
      if (!this.textures.exists('interior')) {
        this.load.atlas('interior', `${ASSETS}/interior-atlas.png`, `${ASSETS}/interior-atlas.json`)
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
      this.cameras.main.setBackgroundColor(room.backgroundColor)
      this.drawLayer(room.layers.floor, 0)
      this.drawLayer(room.layers.walls, 2)
      this.drawFurniture()
      this.drawSlots()
      this.drawSeats()
      this.drawDoors()
      this.spawnPlayer()
      this.plate(worldWidth / 2, 8, room.title, '#fff4e8', '#35644f')
      this.setupCamera()
      this.setupInput()
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
      }
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
        zone.on('pointerup', () => options.onExit(door.target, door.id))
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
        onDoor: door => {
          if (this.lastDoor?.id === door.id) return
          this.lastDoor = door
          options.onExit(door.target, door.id)
        },
      }
      this.controller = (options.createController ?? createDefaultController)(context)
      this.events.on('update', (_time: number, delta: number) => this.controller?.update(delta))
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
      camera.setBounds(0, 0, worldWidth, worldHeight)
      const fit = Math.min(this.scale.width / worldWidth, this.scale.height / worldHeight)
      const zoom = fit >= 1 ? Math.max(1, Math.floor(fit)) : Math.max(0.5, Math.round(fit * 4) / 4)
      camera.setZoom(zoom)
      camera.centerOn(worldWidth / 2, worldHeight / 2)
    }

    setupInput() {
      const firstDoor = room.doors[0]
      this.escHandler = () => { if (firstDoor) options.onExit(firstDoor.target, firstDoor.id) }
      this.input.keyboard?.on('keydown-ESC', this.escHandler)
      this.events.once('shutdown', () => {
        if (this.escHandler) this.input.keyboard?.off('keydown-ESC', this.escHandler)
        this.controller?.destroy?.()
      })
    }
  }

  return InteriorScene
}

// ---------- default movement controller ----------

/** px/s for the built-in controller; InteriorPlayer.speed is reserved for a future per-room override. */
const DEFAULT_SPEED = 90

/** Keyboard (arrows/WASD) + click-to-walk, four-facing-direction animation, AABB collision via
 * map-loader.ts's `collidesAt`. Deliberately simple — a placeholder any richer controller (e.g.
 * true 8-direction movement/animation) can replace via `InteriorOptions.createController` without
 * this file changing. */
export function createDefaultController(ctx: ControllerContext): RoomController {
  const { scene, sprite, room, sheet } = ctx
  const cursors = scene.input.keyboard?.createCursorKeys()
  const keyA = scene.input.keyboard?.addKey('A')
  const keyD = scene.input.keyboard?.addKey('D')
  const keyW = scene.input.keyboard?.addKey('W')
  const keyS = scene.input.keyboard?.addKey('S')
  let clickTarget: { x: number; y: number } | null = null
  let facing: Direction = 'down'

  const pointerHandler = (pointer: PhaserNs.Input.Pointer) => {
    const world = scene.cameras.main.getWorldPoint(pointer.x, pointer.y)
    clickTarget = clampToRoom(room, world.x, world.y)
  }
  scene.input.on('pointerdown', pointerHandler)
  scene.events.once('shutdown', () => scene.input.off('pointerdown', pointerHandler))

  function move(dx: number, dy: number, deltaMs: number) {
    if (dx === 0 && dy === 0) return false
    const length = Math.hypot(dx, dy) || 1
    const step = (ctx.speed * deltaMs) / 1000
    const nx = sprite.x + (dx / length) * step
    const ny = sprite.y + (dy / length) * step
    if (!ctx.isBlocked(nx, sprite.y)) sprite.x = nx
    if (!ctx.isBlocked(sprite.x, ny)) sprite.y = ny
    facing = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up')
    return true
  }

  return {
    update(deltaMs: number) {
      let dx = 0
      let dy = 0
      if (cursors?.left.isDown || keyA?.isDown) dx -= 1
      if (cursors?.right.isDown || keyD?.isDown) dx += 1
      if (cursors?.up.isDown || keyW?.isDown) dy -= 1
      if (cursors?.down.isDown || keyS?.isDown) dy += 1
      let moving = false
      if (dx !== 0 || dy !== 0) {
        clickTarget = null
        moving = move(dx, dy, deltaMs)
      } else if (clickTarget) {
        const remainingX = clickTarget.x - sprite.x
        const remainingY = clickTarget.y - sprite.y
        if (Math.hypot(remainingX, remainingY) < 3) clickTarget = null
        else moving = move(remainingX, remainingY, deltaMs)
      }
      sprite.play(`${sheet}-${moving ? 'walk' : 'idle'}-${facing}`, true)
      const door = doorAt(room, sprite.x, sprite.y)
      if (door) ctx.onDoor(door)
    },
  }
}
