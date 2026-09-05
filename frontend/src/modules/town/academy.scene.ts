/**
 * 成长学院 · 自习室 (task 7). A self-contained Phaser scene: a small pixel-art study
 * room where residents who touched today's tasks sit reading (or on their phone).
 * Kept independent from town.engine.ts (different owner file per docs/成长小镇-接口约定.md
 * §4) but reuses the same conventions: atlas frame naming, `char_${n}` character
 * sheets (32×64 frames, row 1 = idle, row 2 = walk, row 4 = seated front idle,
 * row 6 = seated phone, row 7 = seated reading), and `hashString` from building-kit.ts
 * for the per-person look. The orchestrator registers this scene next to 'town' with
 * `game.scene.add('academy', createAcademyScene(Phaser, options), true, options)`.
 */
import type PhaserNs from 'phaser'
import { hashString } from './building-kit'
import type { TownModel } from './town.types'

const ASSETS = '/assets/town'
const TILE = 32
const COLS = 20
const ROWS = 12
const WALL_ROWS = 3
const WORLD_WIDTH = COLS * TILE
const WORLD_HEIGHT = ROWS * TILE
const FONT = '"PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif'

type Direction = 'right' | 'up' | 'left' | 'down'
const DIRECTION_INDEX: Record<Direction, number> = { right: 0, up: 1, left: 2, down: 3 }

export type AcademyResidentState = 'reading' | 'phone' | 'idle'

export type AcademyResident = {
  publicId: string
  displayName: string
  isSelf: boolean
  /** Index (1-20) into `characters/c{nn}.png`, same numbering as town.engine.ts's characterSheet(). */
  characterSheet: number
  state: AcademyResidentState
}

export type AcademyOptions = {
  residents: AcademyResident[]
  onBack: () => void
}

/** Same formula as town.engine.ts's private characterSheet(), reimplemented so this file stays self-contained. */
function characterSheetFor(publicId: string): number {
  return 1 + (hashString(`${publicId}:look`) % 20)
}

/**
 * residents with todayDone>0 → 'reading' (finished something today); todayStarted>0 (but not
 * done) → 'phone' (mid-task, distracted); everyone else is skipped — except the viewer, who is
 * always shown so the room never looks empty on the very first visit ('idle' when they too have
 * nothing going on today).
 */
export function academyResidents(model: Pick<TownModel, 'residents'>): AcademyResident[] {
  const list: AcademyResident[] = []
  for (const resident of model.residents) {
    const state: AcademyResidentState | null =
      resident.todayDone > 0 ? 'reading' : resident.todayStarted > 0 ? 'phone' : resident.isSelf ? 'idle' : null
    if (state === null) continue
    list.push({
      publicId: resident.publicId,
      displayName: resident.displayName,
      isSelf: resident.isSelf,
      characterSheet: characterSheetFor(resident.publicId),
      state,
    })
  }
  return list
}

type Desk = { x: number; y: number }

/** Two rows of five desks: a modest reading room, within the 8-12 range the room is built for. */
function deskSlots(): Desk[] {
  const rowsY = [172, 272]
  const columnsX = [116, 218, 320, 422, 524]
  const slots: Desk[] = []
  for (const y of rowsY) for (const x of columnsX) slots.push({ x, y })
  return slots
}

export function createAcademyScene(Phaser: typeof PhaserNs, options: AcademyOptions): typeof Phaser.Scene {
  const residents = options.residents
  const slots = deskSlots()
  const doorX = WORLD_WIDTH / 2

  class AcademyScene extends Phaser.Scene {
    escHandler: (() => void) | null = null

    constructor() { super('academy') }

    preload() {
      if (!this.textures.exists('interior')) {
        this.load.atlas('interior', `${ASSETS}/interior-atlas.png`, `${ASSETS}/interior-atlas.json`)
      }
      const sheets = new Set(residents.map(item => item.characterSheet))
      for (const index of sheets) {
        const key = `char_${index}`
        if (this.textures.exists(key)) continue
        this.load.spritesheet(key, `${ASSETS}/characters/c${String(index).padStart(2, '0')}.png`, { frameWidth: 32, frameHeight: 64 })
      }
    }

    create() {
      this.cameras.main.setBackgroundColor('#e7d9bd')
      this.drawFloor()
      this.drawWalls()
      this.drawBackWallFurniture()
      this.drawReadingArea()
      this.drawDoor()
      this.setupCamera()
      this.setupInput()
    }

    // ---------- world ----------

    drawFloor() {
      const floor = this.add.renderTexture(0, WALL_ROWS * TILE, WORLD_WIDTH, WORLD_HEIGHT - WALL_ROWS * TILE).setOrigin(0).setDepth(0)
      floor.beginDraw()
      for (let row = 0; row < ROWS - WALL_ROWS; row += 1) {
        for (let column = 0; column < COLS; column += 1) {
          floor.batchDrawFrame('interior', 'floor_1', column * TILE, row * TILE)
        }
      }
      floor.endDraw()
      const mat = this.add.image(doorX, WORLD_HEIGHT - 6, 'interior', 'doormat_1').setOrigin(0.5, 1).setDepth(WORLD_HEIGHT - 6)
      mat.setDisplaySize(96, mat.height)
    }

    drawWalls() {
      // Back (north) wall: two rows of plain wallpaper over one row of wood wainscot.
      for (let column = 0; column < COLS; column += 1) {
        this.add.image(column * TILE, 0, 'interior', 'wall_2').setOrigin(0).setDepth(1)
        this.add.image(column * TILE, TILE, 'interior', 'wall_2').setOrigin(0).setDepth(1)
        this.add.image(column * TILE, TILE * 2, 'interior', 'wall_1').setOrigin(0).setDepth(1)
      }
      // Side walls, full height, so the room reads as boxed in rather than open-ended.
      for (let row = 0; row < ROWS; row += 1) {
        this.add.image(0, row * TILE, 'interior', 'wall_1').setOrigin(0).setDepth(2)
        this.add.image(WORLD_WIDTH - TILE, row * TILE, 'interior', 'wall_1').setOrigin(0).setDepth(2)
      }
    }

    drawBackWallFurniture() {
      const wallBottom = WALL_ROWS * TILE
      const shelf = (x: number, frame: string) => this.add.image(x, wallBottom, 'interior', frame).setOrigin(0.5, 1).setDepth(wallBottom + 1)
      shelf(90, 'bookshelf_1')
      shelf(300, 'bookshelf_2')
      shelf(560, 'bookshelf_3')
      this.add.image(430, wallBottom, 'interior', 'board_2').setOrigin(0.5, 1).setDepth(wallBottom + 1)
      this.add.image(190, 56, 'interior', 'notice_1').setOrigin(0.5, 1).setDepth(60)
      this.add.image(300, wallBottom - 6, 'interior', 'globe_1').setOrigin(0.5, 1).setDepth(wallBottom + 2)
      this.plate(WORLD_WIDTH / 2, 20, '成长学院 · 自习室', '#fff4e8', '#35644f')
      // A standing chalkboard and two plants soften the corners.
      this.add.image(40, WORLD_HEIGHT - 20, 'interior', 'board_1').setOrigin(0.5, 1).setDepth(WORLD_HEIGHT - 20)
      this.add.image(30, wallBottom + 40, 'interior', 'plant_1').setOrigin(0.5, 1).setDepth(wallBottom + 41)
      this.add.image(WORLD_WIDTH - 30, wallBottom + 40, 'interior', 'plant_2').setOrigin(0.5, 1).setDepth(wallBottom + 41)
      const rug = this.add.image(WORLD_WIDTH / 2, WORLD_HEIGHT - 60, 'interior', 'rug_1').setOrigin(0.5, 1).setDepth(WALL_ROWS * TILE)
      rug.setDisplaySize(WORLD_WIDTH - 260, 190)
    }

    drawReadingArea() {
      slots.forEach((slot, index) => {
        const resident = residents[index]
        this.add.image(slot.x, slot.y, 'interior', 'chair_2').setOrigin(0.5, 1).setDepth(slot.y - 1)
        if (resident) this.seatResident(resident, slot)
        this.add.image(slot.x, slot.y + 34, 'interior', 'desk_1').setOrigin(0.5, 1).setDepth(slot.y + 34)
      })
    }

    seatResident(resident: AcademyResident, slot: Desk) {
      this.ensureSeatAnimations(`char_${resident.characterSheet}`)
      const sheet = `char_${resident.characterSheet}`
      const y = slot.y + 6
      const sprite = this.add.sprite(slot.x, y, sheet).setOrigin(0.5, 1).setDepth(y)
      sprite.play(`${sheet}-seat-${resident.state}`)
      const background = resident.isSelf ? 'rgba(200,95,71,.92)' : 'rgba(40,30,26,.72)'
      const label = resident.isSelf ? `${resident.displayName}（我）` : resident.displayName
      this.plate(slot.x, y + 4, label, '#fff', background)
    }

    drawDoor() {
      this.plate(doorX, WORLD_HEIGHT - 30, '回到小镇', '#fff4e8', '#3b312c')
      const zone = this.add.zone(doorX - 48, WORLD_HEIGHT - 40, 96, 40).setOrigin(0).setInteractive({ useHandCursor: true })
      zone.on('pointerup', () => options.onBack())
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
      // idle-down, kept around in case a future revision walks residents to their seat.
      const downOffset = DIRECTION_INDEX.down * 6
      this.anims.create({ key: `${sheet}-idle-down`, frames: range(1, downOffset), frameRate: 6, repeat: -1 })
    }

    setupCamera() {
      const camera = this.cameras.main
      camera.setBounds(0, 0, WORLD_WIDTH, WORLD_HEIGHT)
      const fit = Math.min(this.scale.width / WORLD_WIDTH, this.scale.height / WORLD_HEIGHT)
      const zoom = fit >= 1 ? Math.max(1, Math.floor(fit)) : Math.max(0.5, Math.round(fit * 4) / 4)
      camera.setZoom(zoom)
      camera.centerOn(WORLD_WIDTH / 2, WORLD_HEIGHT / 2)
    }

    setupInput() {
      this.escHandler = () => options.onBack()
      this.input.keyboard?.on('keydown-ESC', this.escHandler)
      this.events.once('shutdown', () => {
        if (this.escHandler) this.input.keyboard?.off('keydown-ESC', this.escHandler)
      })
    }
  }

  return AcademyScene
}
