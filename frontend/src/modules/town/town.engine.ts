/**
 * Phaser renderer for 成长小镇. Kept behind a factory so the Vue view can lazy-load
 * Phaser (about 1 MB) and tests can replace the whole engine with a stub.
 */
import type PhaserNs from 'phaser'
import { activityFor, buildBlueprint, hashString, whereShouldBe } from './building-kit'
import type { TownVenue, VenueAction } from './building-kit'
import { academyResidents, createAcademyScene } from './academy.scene'
import { RUN_ANIM_SCALE, dominantDirection, movementDelta, moveSpeed, registerGreet, shouldGreet, stepToward, stepTowardPoint } from './walkers'
import type { Direction4, GreetCooldowns, WalkDirection } from './walkers'
import { buildTownCollisionWorld, canStand, nearestStandable, resolveMove } from './collision'
import type { CollisionWorld, Point, FurnitureObstacle } from './collision'
import type { ResidentActivity, TownModel, TownResident } from './town.types'
import { createInteriorScene, interiorSceneKey } from './interior.scene'
import { parseRoomMap } from './map-loader'
import { TownAtmosphere } from './atmosphere'
import { presenceTarget, shouldTeleport, createPresenceReporter } from './presence'
import type { PresencePayload } from './presence'
import {
  createGymFurniture,
  createCafeFurniture,
  createParkFurniture,
  createStreetFurniture,
  createGroundDetails,
} from './town-furniture'
import type { FurnitureItem, VenueFurniture, GroundDetail } from './town-furniture'
import type { TownNpcView, InitiativeBudget, NpcActivity, NpcPlace } from './town-npc.types'
import { placeFor, activeSlot, densityCap, selectVisible } from './npc-placement'
import type { TownLayout } from './npc-placement'
import { buildItinerary, nextLeg } from './observation-mode'
import type { ObservationLeg } from './observation-mode'
import { bubbleTierFor, pointsToPlay, canInitiate, consume } from './talking-bubbles'

export type TownSelection = string | 'npc:assistant' | 'npc:postman' | 'academy' | null
export type TownHandlers = {
  onSelect?: (selection: TownSelection) => void
  /** Fired whenever the camera finishes transitioning into/out of the academy interior. */
  onAcademyChange?: (inside: boolean) => void
  /** Called when self avatar stops or at throttled interval; engine reports presence to backend. */
  onPresenceReport?: (payload: PresencePayload) => void
  /** Called when the player avatar moves (for onboarding tracking). */
  onPlayerMove?: (x: number, y: number) => void
  /** Called to report distance to guide NPC (for onboarding tracking). */
  onDistanceToGuide?: (distance: number) => void
}
export type TownGame = {
  /** Hands the engine the town's NPC roster (GET /town/npcs). Safe to call repeatedly. */
  applyNpcs: (npcs: TownNpcView[], budget: InitiativeBudget) => void
  /** 观察模式: detaches the camera from the player and glides it between points of interest. */
  setObservation: (on: boolean) => void
  setNight(night: boolean): void
  /** Turns the self avatar's run mode on/off; holding Shift runs regardless of this toggle. */
  setRun(running: boolean): void
  focus(publicId: string): void
  /** Updates residents' schedules/activity in place; rebuilds only the plots whose blueprint changed. */
  applyModel(model: TownModel): void
  /** Camera pan + roof-prop drop + particle burst + a brief "done" bubble over the resident. Queued, one at a time. */
  celebrate(publicId: string): void
  /** Fades to the 成长学院 · 自习室 interior scene, sleeping the street behind it. */
  enterAcademy(): void
  /** Reverses enterAcademy(): fades back out to the street and wakes it up. */
  exitAcademy(): void
  enterRoom(roomId: string): Promise<void>
  exitRoom(): void
  destroy(): void
}

const ASSETS = '/assets/town'
const TILE = 32
const BASELINE_ROW = 28
const BASELINE = BASELINE_ROW * TILE
const ROWS = 37
const WORLD_HEIGHT = ROWS * TILE
const YARD_X = 96
const ACADEMY_X = YARD_X + 560
const SCHOOL_WIDTH = 768
const PLOT_START = ACADEMY_X + SCHOOL_WIDTH + 192
const PLOT_WIDTH = 224
const PLOT_PITCH = 336
const STREET_Y = BASELINE + 40
const FONT = '"PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif'
/** How long a click/keypress overrides the self avatar's schedule-driven placement (task 6). */
const SELF_OVERRIDE_MS = 20_000
/** How long two passing walkers pause to greet each other. */
const GREET_PAUSE_MS = 1000
/** Fade duration for the town <-> academy interior transition. */
const SCENE_FADE_MS = 250

type Direction = Direction4
const DIRECTION_INDEX: Record<Direction, number> = { right: 0, up: 1, left: 2, down: 3 }
/** How far north of the baseline an ordinary shop's sidewalk apron reaches (task: 八向自由移动). */
const WALK_APRON = 64
/** Southern edge of the walkable street/sidewalk/park band, short of the world's own bottom edge. */
const WALK_BOTTOM = WORLD_HEIGHT - 24
/** The academy/yard courtyard is a proper plaza: it lets the self avatar wander further north
 * than an ordinary shop front, covering the basketball court and the guide NPC's patrol strip. */
const PLAZA_TOP = BASELINE - 380
/** No A* pathing: a straight-line click-to-walk can dead-end against a wall. Give up and settle
 * into 'act' after this long stuck rather than animating in place forever. */
const SELF_STUCK_MS = 350

/** 观察模式跑完一圈的总时长——plan 的验收标准是"一段 30 秒长镜头"。 */
const OBSERVATION_LOOP_MS = 30_000

/** 气泡停留时长。40 字的中文按正常语速念完约 5 秒，留一点余量。 */
const BUBBLE_HOLD_MS = 6_500

/** 观察模式的镜头高度。比默认视角近一档，人物、气泡和店面细节才看得清。 */
const OBSERVATION_ZOOM = 1.3

/** Frames inside emotes.png (10 x 10 grid of 32px bubbles). */
const EMOTES: Record<ResidentActivity | 'mail' | 'question' | 'heart', [number, number]> = {
  done: [64, 65],
  working: [92, 93],
  planned: [40, 41],
  resting: [56, 57],
  mail: [68, 69],
  question: [52, 53],
  heart: [54, 55],
}

type Walker = {
  sprite: PhaserNs.GameObjects.Sprite
  label: PhaserNs.GameObjects.Text
  emote: PhaserNs.GameObjects.Sprite | null
  sheet: string
  homeX: number
  targetX: number
  /** Self avatar only: free-roam y target (NPCs/residents stay pinned to STREET_Y). */
  targetY: number
  state: 'walk' | 'act'
  timer: number
  activity: ResidentActivity
  resident: TownResident | null
  patrol: [number, number] | null
  venue: TownVenue
  action: VenueAction
  /** Stable id for greeting-cooldown pairing: the resident's publicId, or the NPC's selection code. */
  id: string
  /** While now < overrideUntil, the self avatar skips schedule re-evaluation (a click/keypress wins). */
  overrideUntil: number
  /** True while the current walk was started by a click or key rather than the schedule. */
  manualWalk: boolean
  /** Set when walking toward an NPC/the academy by click; dispatched once the walker arrives there. */
  arriveSelect: TownSelection | null
  /** Ephemeral bubble: a "?" while walking to a clicked destination, a heart during a greeting. */
  travelEmote: PhaserNs.GameObjects.Sprite | null
  /** While now < frozenUntil, the walker is paused for a passing greeting. */
  frozenUntil: number
  /** True for the one frame right after a keypress moved this walker directly. */
  keyDriven: boolean
  /** Self avatar only: this move is a run (Shift held, or the HUD toggle is on). */
  running: boolean
  /** Last direction faced; kept so standing still (or being blocked) doesn't reset the sprite
   * to a default facing. */
  facing: Direction
  /** Self avatar only: ms spent unable to make any progress toward targetX/targetY (fully walled
   * in). After a short grace period the walk is abandoned instead of animating in place forever. */
  stuckMs: number
  /** Last known presence for this walker (used to detect position changes and smooth-move). */
  lastPresence: { x: number; y: number } | null
  /** Teleporting (fade in/out) when true; normal walk when false. */
  teleporting: boolean
  /** Self avatar only: last time presence was reported (for throttling during movement). */
  lastReportTime: number
  /** Set for the 16 society NPCs (layers 1-3); null for residents and the two legacy NPC sprites. */
  npc: TownNpcView | null
  /** Speech bubble currently floating above this walker's head, if any. */
  speech: PhaserNs.GameObjects.Container | null
  /** Society NPCs only: the activity their backend schedule says they're doing right now. */
  npcActivity: NpcActivity | null
}

/** Geometry of one labour spritesheet, as written by scripts/build-town-assets.py. */
type LabourAnim = {
  file: string
  frameWidth: number
  frameHeight: number
  frames: number
  framesPerDirection: number
  /** Half-open range of the block that faces the camera — the only one the town plays. */
  downStart: number
  downEnd: number
}

const LABOUR_ACTIVITIES = new Set<NpcActivity>(['watering', 'chopping', 'fishing', 'harvesting', 'digging'])

type Vehicle = { sprite: PhaserNs.GameObjects.Image; speed: number }

type SelfKeys = {
  cursors: PhaserNs.Types.Input.Keyboard.CursorKeys | undefined
  keyA: PhaserNs.Input.Keyboard.Key | undefined
  keyD: PhaserNs.Input.Keyboard.Key | undefined
  keyW: PhaserNs.Input.Keyboard.Key | undefined
  keyS: PhaserNs.Input.Keyboard.Key | undefined
  shift: PhaserNs.Input.Keyboard.Key | undefined
}

/** Phaser's tween easings aren't reachable from a plain number, so the pan interpolation uses its own. */
/**
 * 同一个地点站着好几个人时，各自的偏移量。
 *
 * <p>横向散开只解决"挤在一起"，解决不了"一字排开"——所有人都钉在同一条基线上，看上去就是
 * 一排合唱队。所以纵向也要散：人行道那条带子窄（只有几十像素），广场和公园则一直往北敞到
 * 篮球场，可以拉出真正的纵深。有了纵向差异，深度排序（depth = y）也才有东西可排，人才会
 * 互相遮挡，而不是像贴纸一样并排。
 */
function venueOffset(npcCode: string, place: NpcPlace): { dx: number; dy: number } {
  const seed = hashString(npcCode)
  const dx = (seed % 11) * 38 - 190
  // 广场与公园是开阔地，往北能站得很深；其余地方只有店门前那条人行道。
  const open = place === 'plaza' || place === 'park'
  const dy = open
    ? -((seed >>> 8) % 9) * 22 + 20
    : -((seed >>> 8) % 5) * 15 + 24
  return { dx, dy }
}

function easeInOutSine(t: number) {
  return -(Math.cos(Math.PI * Math.min(1, Math.max(0, t))) - 1) / 2
}

function characterSheet(publicId: string) {
  return 1 + (hashString(`${publicId}:look`) % 20)
}

function worldWidth(count: number) {
  return Math.max(2600, PLOT_START + count * PLOT_PITCH + 320)
}

export function plotX(index: number) {
  return PLOT_START + index * PLOT_PITCH
}

/** serverTime - Date.now() at the moment the model was (re)loaded; calibrates schedule windows to the local clock. */
function computeServerOffset(serverTime?: string): number {
  if (!serverTime) return 0
  const parsed = new Date(serverTime).getTime()
  return Number.isNaN(parsed) ? 0 : parsed - Date.now()
}

/** Cheap signature for "does this plot need to be redrawn": level, direction, streak and open/closed. */
function plotSignatureFor(resident: TownResident): string {
  return `${resident.level}|${resident.dominantDimension ?? ''}|${resident.longestStreak}|${activityFor(resident) !== 'resting'}`
}

export async function createTownGame(container: HTMLElement, model: TownModel, handlers: TownHandlers = {}): Promise<TownGame> {
  const Phaser = (await import('phaser')).default
  const residents = model.residents
  const width = worldWidth(residents.length)
  const academyDoorX = ACADEMY_X + SCHOOL_WIDTH / 2 + 8
  // 一人一镇的 NPC 名册。挂在模块作用域而不是场景上，因为名册是异步到的，可能比场景先到也可能后到。
  let townNpcRoster: TownNpcView[] = []
  let initiativeBudget: InitiativeBudget = { limit: 3, used: 0 }
  let observationOn = false
  const courtX = YARD_X + 220
  const parkX = academyDoorX - 60
  // The self avatar can only walk the paved street between the yard and the far edge of town.
  const groundMinX = YARD_X + 40
  const groundMaxX = width - 80
  let sceneRef: TownScene | null = null
  let desiredNight = false
  let desiredRun = false
  let serverOffsetMs = computeServerOffset(model.serverTime)
  let celebrationQueue: string[] = []
  let celebrationBusy = false
  let latestModel = model
  let academyEntered = false
  let academyBusy = false

  // Presence reporter: throttles self position updates to backend
  const presenceReporter = createPresenceReporter(
    (payload) => { handlers.onPresenceReport?.(payload) },
    3000
  )

  function runCelebrationQueue() {
    if (celebrationBusy || celebrationQueue.length === 0) return
    const publicId = celebrationQueue.shift() as string
    celebrationBusy = true
    sceneRef?.celebrateResident(publicId)
    setTimeout(() => { celebrationBusy = false; runCelebrationQueue() }, 1500)
  }

  class TownScene extends Phaser.Scene {
    walkers: Walker[] = []
    vehicles: Vehicle[] = []
    glows: PhaserNs.GameObjects.GameObject[] = []
    nightOverlay: PhaserNs.GameObjects.Graphics | null = null
    night = false
    dragStart: { x: number; y: number; scrollX: number; scrollY: number } | null = null
    dragged = false
    buildingCenters = new Map<string, { x: number; y: number }>()
    roofTop = new Map<string, { x: number; y: number }>()
    residentIndex = new Map<string, number>()
    plotObjects = new Map<string, PhaserNs.GameObjects.GameObject[]>()
    plotSignature = new Map<string, string>()
    selfWalker: Walker | null = null
    selfKeys: SelfKeys | null = null
    /** HUD toggle: every self move runs until it is turned off. Shift always runs regardless. */
    runMode = false
    greetCooldowns: GreetCooldowns = new Map()
    /** 观察模式的巡游路线；空数组表示没在观察模式。 */
    observationItinerary: ObservationLeg[] = []
    observationStartedAt = 0
    /** 观察模式里下一次让镜头里的人开口的时间。 */
    nextAmbientLineAt = 0
    /** 劳作动画的几何；素材没生成时保持 null，NPC 就退回站着，不报错。 */
    labourGeometry: Record<string, LabourAnim> | null = null
    /** 主动搭话的全镇节流：预算之外再加一层间隔，免得三次额度在同一秒里烧完。 */
    nextInitiativeAt = 0
    /** Walkable ground + building obstacles the self avatar's free 8-directional movement is
     * checked against; rebuilt whenever a plot's height changes (level up adds a floor). */
    collisionWorld: CollisionWorld = { walkable: [], obstacles: [] }
    /** Camera smoothly follows the self avatar while it moves under keyboard/click control, but
     * a manual drag takes over and stays in charge for a few seconds afterwards. */
    cameraFollowPausedUntil = 0
    followingCamera = false
    atmosphere: TownAtmosphere | null = null
    /** 场地配套家具（健身房/咖啡馆/公园） */
    venueFurniture: VenueFurniture[] = []
    /** 街道家具列表 */
    streetFurnitureItems: FurnitureItem[] = []
    /** 所有家具碰撞矩形（用于 buildCollisionWorld） */
    furnitureCollisions: FurnitureObstacle[] = []
    /** 当前正在交互的家具 ID */
    activeFurnitureId: string | null = null
    /** 交互气泡精灵 */
    interactionBubble: PhaserNs.GameObjects.Text | null = null

    constructor() { super('town') }

    preload() {
      this.load.atlas('town', `${ASSETS}/town-atlas.png`, `${ASSETS}/town-atlas.json`)
      // 劳作动画的几何（每张的帧宽高、朝向分块）写在 sidecar 里，先把它读进来。
      this.load.json('labourAnims', `${ASSETS}/characters/labour-anims.json`)
      this.load.spritesheet('emotes', `${ASSETS}/emotes.png`, { frameWidth: 32, frameHeight: 32 })
      this.load.spritesheet('npc_postman', `${ASSETS}/characters/postman.png`, { frameWidth: 32, frameHeight: 64 })
      this.load.spritesheet('npc_scout', `${ASSETS}/characters/scout.png`, { frameWidth: 32, frameHeight: 64 })
      // 居民用到的表 + 全部 20 张预制表。多加载的十几张很小，换来的是名册后到时可以直接生成
      // NPC，不必再跑一轮运行时加载（那会让人物凭空闪现）。
      const sheets = new Set(residents.map(item => characterSheet(item.publicId)))
      for (let index = 1; index <= 20; index += 1) sheets.add(index)
      for (const index of sheets) {
        this.load.spritesheet(`char_${index}`, `${ASSETS}/characters/c${String(index).padStart(2, '0')}.png`, { frameWidth: 32, frameHeight: 64 })
      }
    }

    create() {
      this.atmosphere = new TownAtmosphere({ worldWidth: width, worldHeight: WORLD_HEIGHT, groundY: BASELINE })
      this.atmosphere.attach(this)
      sceneRef = this
      ;(window as unknown as { __townScene?: TownScene }).__townScene = this
      this.createSharedAnimations()
      this.createGlowTexture()
      this.createSparkTexture()
      this.drawGround()
      this.drawBackdrop()
      this.drawAcademy()
      residents.forEach((resident, index) => { this.residentIndex.set(resident.publicId, index); this.drawPlot(resident, index) })
      this.drawStreetFurniture() // 在 buildCollisionWorld 之前绘制，收集碰撞数据
      this.buildCollisionWorld() // 现在包含家具碰撞
      this.drawParkStrip()
      this.drawWildlife()
      this.spawnVehicles()
      residents.forEach((resident, index) => this.spawnResident(resident, index))
      this.spawnNpcs()
      // 名册可能比场景先到（store 已经拉过一次），那就在这里补生成，不必等下一次 applyNpcs。
      if (townNpcRoster.length > 0) this.applyTownNpcs(townNpcRoster)
      this.loadLabourAnimations()
      this.atmosphere = new TownAtmosphere({ worldWidth: width, worldHeight: WORLD_HEIGHT, groundY: BASELINE })
      this.atmosphere.attach(this)
      for (let x = 180; x < width; x += 360) this.atmosphere.registerLight({ id: `lamp-${x}`, x, y: BASELINE + 22, kind: 'lamp', radius: 120 })
      this.atmosphere.followTarget(this.cameras.main, () => this.selfWalker ? ({ x: this.selfWalker.sprite.x, y: this.selfWalker.sprite.y }) : ({ x: academyDoorX, y: BASELINE }))
      this.nightOverlay = this.add.graphics().fillStyle(0x101c4a, 1).fillRect(0, 0, width, WORLD_HEIGHT).setAlpha(0).setDepth(5000)
      if (desiredNight) this.setNight(true, true)
      if (desiredRun) this.setRunMode(true)
      this.setupCamera()
      this.setupInput()
      this.setupPresenceListeners()
      this.events.once('shutdown', () => {
        this.atmosphere?.destroy()
        this.atmosphere = null
        presenceReporter.flush()
      })
    }

    setupPresenceListeners() {
      // Flush presence on page hide or beforeunload
      const handleVisibilityChange = () => {
        if (document.visibilityState === 'hidden') presenceReporter.flush()
      }
      const handleBeforeUnload = () => { presenceReporter.flush() }

      if (typeof document !== 'undefined') {
        document.addEventListener('visibilitychange', handleVisibilityChange)
        window.addEventListener('beforeunload', handleBeforeUnload)

        this.events.once('shutdown', () => {
          document.removeEventListener('visibilitychange', handleVisibilityChange)
          window.removeEventListener('beforeunload', handleBeforeUnload)
        })
      }
    }

    reportSelfPresence() {
      if (!this.selfWalker) return
      const { sprite, facing } = this.selfWalker
      presenceReporter.update({
        x: Math.round(sprite.x),
        y: Math.round(sprite.y),
        facing,
        scene: 'town',
      })
    }

    // ---------- world ----------

    groundFrame(column: number, row: number, crossingStart: number) {
      const noise = hashString(`${column}:${row}`)
      if (row >= BASELINE_ROW + 2 && row <= BASELINE_ROW + 6 && column >= crossingStart && column < crossingStart + 4) return 'sidewalk_34'
      if (row === BASELINE_ROW || row === BASELINE_ROW + 1) return `sidewalk_${25 + (noise % 4)}`
      if (row === BASELINE_ROW + 2) return 'sidewalk_6'
      if (row >= BASELINE_ROW + 3 && row <= BASELINE_ROW + 5) return row === BASELINE_ROW + 4 && column % 2 === 0 ? 'asphalt_14' : `asphalt_${24 + (noise % 4)}`
      if (row === BASELINE_ROW + 6) return 'sidewalk_2'
      if (row === BASELINE_ROW + 7 || row === BASELINE_ROW + 8) return `sidewalk_${25 + (noise % 4)}`
      if (noise % 7 === 0) return `grass_${9 + (noise % 5)}`
      return 'grass_22'
    }

    /** Ground is baked into overlapping render-texture chunks: one draw call each and no tile seams at any zoom. */
    drawGround() {
      const chunkColumns = 32
      const columns = Math.ceil(width / TILE)
      const crossingStart = Math.floor(academyDoorX / TILE) - 2
      for (let start = 0; start < columns; start += chunkColumns) {
        const end = Math.min(columns, start + chunkColumns + 1)
        const chunk = this.add.renderTexture(start * TILE, 0, (end - start) * TILE, WORLD_HEIGHT).setOrigin(0).setDepth(0)
        chunk.beginDraw()
        for (let row = 0; row < ROWS; row += 1) {
          for (let column = start; column < end; column += 1) {
            chunk.batchDrawFrame('town', this.groundFrame(column, row, crossingStart), (column - start) * TILE, row * TILE)
          }
        }
        chunk.endDraw()
      }
    }

    /** A loose tree line and hedges behind the plots so the top of the map is not a bare lawn. */
    drawBackdrop() {
      for (let x = 24; x < width - 64; x += 72) {
        const seed = hashString(`backdrop:${x}`)
        const y = 96 + (seed % 5) * 22
        const pick = seed % 7
        if (pick < 3) this.add.image(x, y, 'town', `tree_${1 + (seed % 6)}`).setOrigin(0, 1).setDepth(1)
        else if (pick === 3) this.add.image(x, y, 'town', `bush_${1 + (seed % 4)}`).setOrigin(0, 1).setDepth(1)
        else if (pick === 4) this.add.image(x, y + 40, 'town', `flowers_${1 + (seed % 5)}`).setOrigin(0, 1).setDepth(1)
      }
      for (let x = 60; x < width - 64; x += 150) {
        const seed = hashString(`meadow:${x}`)
        const y = 230 + (seed % 4) * 30
        if (seed % 3 === 0) this.add.image(x, y, 'town', `flowerbush_${1 + (seed % 6)}`).setOrigin(0, 1).setDepth(1)
        else if (seed % 3 === 1) this.add.image(x, y, 'town', `flowers_${1 + (seed % 5)}`).setOrigin(0, 1).setDepth(1)
      }
    }

    drawAcademy() {
      const courtBottom = BASELINE - 24
      this.add.image(YARD_X, courtBottom, 'town', 'court_3').setOrigin(0, 1).setDepth(1)
      this.add.image(YARD_X + 8, courtBottom - 352, 'town', 'basketnet_1').setOrigin(0, 1).setDepth(courtBottom - 352)
      this.add.image(YARD_X + 470, courtBottom - 40, 'town', 'stadiumlight_1').setOrigin(0, 1).setDepth(courtBottom - 40)
      this.add.image(YARD_X + 120, courtBottom - 360, 'town', 'yardtoy_12').setOrigin(0, 1).setDepth(courtBottom - 360)
      this.add.image(YARD_X + 300, courtBottom - 400, 'town', 'yardtoy_15').setOrigin(0, 1).setDepth(courtBottom - 400)
      this.add.image(YARD_X + 250, courtBottom - 80, 'town', 'soccerball_1').setOrigin(0, 1).setDepth(courtBottom - 80)
      this.add.image(ACADEMY_X, BASELINE, 'town', 'school_1').setOrigin(0, 1).setDepth(BASELINE - 3)
      this.add.image(ACADEMY_X - 120, BASELINE, 'town', 'schoolflag_1').setOrigin(0, 1).setDepth(BASELINE - 2)
      this.add.image(ACADEMY_X + SCHOOL_WIDTH + 24, BASELINE, 'town', 'tree_5').setOrigin(0, 1).setDepth(BASELINE - 2)
      this.add.image(ACADEMY_X + SCHOOL_WIDTH + 90, BASELINE, 'town', 'tree_3').setOrigin(0, 1).setDepth(BASELINE - 2)
      this.plate(academyDoorX, BASELINE - 750, '成长学院', '#fff4e8', '#35644f')
      this.hitZone(ACADEMY_X, BASELINE - 740, SCHOOL_WIDTH, 740, 'academy')
      this.glow(academyDoorX, BASELINE - 90, 520, 300)
    }

    stackPiece(x: number, bottom: number, frame: string) {
      const image = this.add.image(x, bottom, 'town', frame).setOrigin(0, 1).setDepth(BASELINE - 3)
      return { image, top: bottom - image.height }
    }

    drawPlot(resident: TownResident, index: number) {
      const x = plotX(index)
      const blueprint = buildBlueprint(resident)
      const objects: PhaserNs.GameObjects.GameObject[] = []
      let cursor = BASELINE
      const ground = this.stackPiece(x, cursor, blueprint.ground)
      objects.push(ground.image)
      cursor = ground.top
      for (const middle of blueprint.middles) {
        const piece = this.stackPiece(x, cursor, middle)
        objects.push(piece.image)
        cursor = piece.top
      }
      const roof = this.stackPiece(x, cursor, blueprint.roof)
      objects.push(roof.image)
      const roofSurface = roof.top + roof.image.height - 44
      blueprint.roofProps.forEach((prop, propIndex) => {
        const image = this.add.image(x + 20 + propIndex * 64, roofSurface - propIndex * 6, 'town', prop).setOrigin(0.5, 1).setDepth(BASELINE - 2)
        if (image.width > PLOT_WIDTH - 40) image.setX(x + 12)
        objects.push(image)
      })
      const top = roof.top
      this.roofTop.set(resident.publicId, { x: x + PLOT_WIDTH / 2, y: top })
      const seed = hashString(`${resident.publicId}:props`)
      objects.push(this.add.image(x + PLOT_WIDTH + 12, BASELINE, 'town', `tree_${1 + (seed % 8)}`).setOrigin(0, 1).setDepth(BASELINE - 2))
      const title = resident.isSelf ? `${resident.displayName}（我）` : resident.displayName
      const subtitle = resident.title ? ` · ${resident.title}` : ''
      objects.push(this.plate(x + PLOT_WIDTH / 2, top - 14, `${title} · LV.${resident.level}${subtitle}`, resident.isSelf ? '#fff4e8' : '#3b312c', resident.isSelf ? '#c85f47' : '#fffdfa'))
      this.buildingCenters.set(resident.publicId, { x: x + PLOT_WIDTH / 2, y: (top + BASELINE) / 2 })
      objects.push(this.hitZone(x, top, PLOT_WIDTH, BASELINE - top, resident.publicId))
      if (blueprint.open) {
        objects.push(this.glow(x + PLOT_WIDTH / 2, BASELINE - 60, PLOT_WIDTH + 120, 220))
      }
      this.plotObjects.set(resident.publicId, objects)
      this.plotSignature.set(resident.publicId, plotSignatureFor(resident))
    }

    /** Rebuilds one resident's plot only when its blueprint-affecting fields actually changed. */
    refreshPlot(resident: TownResident, index: number) {
      const signature = plotSignatureFor(resident)
      if (this.plotSignature.get(resident.publicId) === signature) return
      const previous = this.plotObjects.get(resident.publicId)
      if (previous) {
        this.glows = this.glows.filter(glow => !previous.includes(glow))
        for (const object of previous) object.destroy()
      }
      this.drawPlot(resident, index)
      this.buildCollisionWorld() // the plot's roof (and so its footprint height) may have changed
    }

    /** Assembles the walkable-ground model (task: 八向自由移动 + 碰撞) from the plots' real drawn
     * footprints (this.roofTop) plus the academy building and the street/plaza layout constants. */
    buildCollisionWorld() {
      const buildings = [{ x: ACADEMY_X, width: SCHOOL_WIDTH, topY: BASELINE - 740 }]
      residents.forEach((resident, index) => {
        const roof = this.roofTop.get(resident.publicId)
        buildings.push({ x: plotX(index), width: PLOT_WIDTH, topY: roof ? roof.y : BASELINE - 200 })
      })
      this.collisionWorld = buildTownCollisionWorld({
        groundMinX,
        groundMaxX,
        baselineY: BASELINE,
        apron: WALK_APRON,
        bottomY: WALK_BOTTOM,
        plaza: { minX: YARD_X, maxX: academyDoorX + 300, topY: PLAZA_TOP },
        buildings,
        furniture: this.furnitureCollisions,
      })
    }

    drawStreetFurniture() {
      // 公交站牌保留
      this.add.image(PLOT_START - 200, BASELINE + 60, 'town', 'busstop_1').setOrigin(0, 1).setDepth(BASELINE + 60)
      this.add.image(PLOT_START - 30, BASELINE + 60, 'town', 'busstopsign_1').setOrigin(0, 1).setDepth(BASELINE + 60)

      // 路灯（保留原有逻辑但密度优化）
      for (let x = ACADEMY_X - 160; x < width - 96; x += PLOT_PITCH) {
        this.add.image(x, BASELINE + 62, 'town', 'lamp_5').setOrigin(0, 1).setDepth(BASELINE + 62)
        this.glow(x + 16, BASELINE + 10, 200, 180, 0.8)
      }

      // 场地配套家具：健身房（假设在第一个商店位置）、咖啡馆（第二个）、公园入口（学院前）
      const gymX = PLOT_START
      const cafeX = PLOT_START + PLOT_PITCH
      const parkX = ACADEMY_X - 100

      this.venueFurniture = [
        createGymFurniture(gymX + PLOT_WIDTH / 2, BASELINE),
        createCafeFurniture(cafeX + PLOT_WIDTH / 2, BASELINE),
        createParkFurniture(parkX, BASELINE),
      ]

      // 街道家具（密集布置）
      this.streetFurnitureItems = createStreetFurniture(ACADEMY_X, width - 200, BASELINE, 400)

      // 绘制所有场地家具
      this.venueFurniture.forEach(venue => {
        venue.items.forEach(item => this.drawFurnitureItem(item))
      })

      // 绘制所有街道家具
      this.streetFurnitureItems.forEach(item => this.drawFurnitureItem(item))

      // 地面细节层
      const groundDetails = createGroundDetails(groundMinX, groundMaxX, BASELINE, STREET_Y)
      groundDetails.forEach(detail => {
        this.add.image(detail.x, detail.y, 'town', detail.frame).setOrigin(0.5, 0.5).setDepth(detail.depth)
      })
    }

    /** 绘制单个家具项并处理碰撞/交互 */
    drawFurnitureItem(item: FurnitureItem) {
      const depth = item.depth ?? item.y
      const sprite = this.add.image(item.x, item.y, 'town', item.frame).setOrigin(0, 1).setDepth(depth)

      // 添加碰撞
      if (item.collision) {
        this.furnitureCollisions.push({
          x: item.x + item.collision.offsetX,
          y: item.y + item.collision.offsetY,
          width: item.collision.width,
          height: item.collision.height,
        })
      }

      // 可交互家具：添加交互区域
      if (item.interactive && item.interactionType) {
        const hitArea = this.add.zone(item.x, item.y, 80, 80).setOrigin(0.5, 1).setInteractive()
        hitArea.on('pointerdown', () => this.onFurnitureInteract(item))
      }
    }

    drawParkStrip() {
      const y = (BASELINE_ROW + 9) * TILE
      for (let x = 40; x < width - 80; x += 96) {
        const seed = hashString(`park:${x}`)
        const pick = seed % 6
        if (pick === 0) this.add.image(x, y, 'town', `tree_${1 + (seed % 8)}`).setOrigin(0, 1).setDepth(y)
        else if (pick === 1) this.add.image(x, y - 16, 'town', `flowerbush_${1 + (seed % 6)}`).setOrigin(0, 1).setDepth(y - 16)
        else if (pick === 2) this.add.image(x, y - 20, 'town', `bush_${1 + (seed % 4)}`).setOrigin(0, 1).setDepth(y - 20)
        else if (pick === 3) this.add.image(x, y - 12, 'town', `flowers_${1 + (seed % 5)}`).setOrigin(0, 1).setDepth(y - 12)
        else if (pick === 4) this.add.image(x, y - 8, 'town', 'gardenbench_1').setOrigin(0, 1).setDepth(y - 8)
      }
      const plaza = (BASELINE_ROW + 8) * TILE + 20
      this.add.image(academyDoorX - 32, plaza, 'town', 'fountain_1').setOrigin(0, 1).setDepth(plaza)
      this.add.image(academyDoorX - 260, plaza, 'town', 'foodcart_1').setOrigin(0, 1).setDepth(plaza)
      this.add.image(academyDoorX + 180, plaza, 'town', 'flowercart_1').setOrigin(0, 1).setDepth(plaza)
      this.add.image(academyDoorX + 330, plaza, 'town', 'hotdogcart_1').setOrigin(0, 1).setDepth(plaza)
    }

    drawWildlife() {
      this.add.sprite(ACADEMY_X + 120, BASELINE - 700, 'town', 'crow_1').setOrigin(0, 1).setDepth(BASELINE).play('crow-idle')
      for (let index = 0; index < residents.length; index += 2) {
        const x = plotX(index) + 160
        this.add.sprite(x, (BASELINE_ROW + 8) * TILE, 'town', 'pigeon_1').setOrigin(0, 1).setDepth(1).play('pigeon-idle')
      }
      this.add.sprite(academyDoorX + 80, (BASELINE_ROW + 8) * TILE + 8, 'town', 'pigeon_1').setOrigin(0, 1).setDepth(2).play('pigeon-idle')
    }

    spawnVehicles() {
      const upperLane = (BASELINE_ROW + 4) * TILE + 4
      const lowerLane = (BASELINE_ROW + 6) * TILE - 2
      const cars = Math.max(3, Math.min(7, Math.floor(width / 700)))
      for (let index = 0; index < cars; index += 1) {
        const seed = hashString(`car:${index}`)
        const goesRight = index % 2 === 0
        const frame = seed % 9 === 0 ? (goesRight ? 'busright_1' : 'busleft_1') : `${goesRight ? 'carright' : 'carleft'}_${1 + (seed % 8)}`
        const y = goesRight ? lowerLane : upperLane
        const sprite = this.add.image((seed % width), y, 'town', frame).setOrigin(0, 1).setDepth(y)
        this.vehicles.push({ sprite, speed: (goesRight ? 1 : -1) * (85 + (seed % 60)) })
      }
    }

    // ---------- helpers ----------

    /** Soft radial light used for windows and lamps at night; additive so it brightens what is under it. */
    createGlowTexture() {
      if (this.textures.exists('glow')) return
      const size = 256
      const canvas = this.textures.createCanvas('glow', size, size)
      if (!canvas) return
      const context = canvas.getContext()
      const gradient = context.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2)
      gradient.addColorStop(0, 'rgba(255, 216, 150, 0.85)')
      gradient.addColorStop(0.45, 'rgba(255, 200, 120, 0.32)')
      gradient.addColorStop(1, 'rgba(255, 190, 100, 0)')
      context.fillStyle = gradient
      context.fillRect(0, 0, size, size)
      canvas.refresh()
    }

    /** A tiny filled circle used as the celebration particle when a "flowers_1" sprite would look too big. */
    createSparkTexture() {
      if (this.textures.exists('spark')) return
      const size = 8
      const canvas = this.textures.createCanvas('spark', size, size)
      if (!canvas) return
      const context = canvas.getContext()
      context.fillStyle = '#ffd27a'
      context.beginPath()
      context.arc(size / 2, size / 2, size / 2, 0, Math.PI * 2)
      context.fill()
      canvas.refresh()
    }

    glow(x: number, y: number, w: number, h: number, alpha = 1) {
      const light = this.add.image(x, y, 'glow').setDisplaySize(w, h).setBlendMode(Phaser.BlendModes.ADD).setDepth(5001).setAlpha(0)
      light.setData('targetAlpha', alpha)
      this.glows.push(light)
      return light
    }

    plate(x: number, y: number, text: string, color: string, background: string) {
      return this.add.text(x, y, text, {
        fontFamily: FONT, fontSize: '13px', color, backgroundColor: background, padding: { x: 7, y: 3 },
      }).setOrigin(0.5, 1).setDepth(4000).setResolution(2)
    }

    hitZone(x: number, y: number, w: number, h: number, selection: TownSelection) {
      const zone = this.add.zone(x, y, w, h).setOrigin(0).setInteractive({ useHandCursor: true })
      zone.on('pointerup', () => {
        if (this.dragged) return
        // The academy door walks the self avatar there first (task 6); building plots select immediately.
        if (selection === 'academy') this.walkSelfToAndSelect(academyDoorX, STREET_Y, selection)
        else handlers.onSelect?.(selection)
      })
      return zone
    }

    createSharedAnimations() {
      const frames = (prefix: string, count: number) => Array.from({ length: count }, (_, i) => ({ key: 'town', frame: `${prefix}_${i + 1}` }))
      this.anims.create({ key: 'crow-idle', frames: frames('crow', 6), frameRate: 4, repeat: -1 })
      this.anims.create({ key: 'pigeon-idle', frames: frames('pigeon', 6), frameRate: 5, repeat: -1 })
      for (const [name, pair] of Object.entries(EMOTES)) {
        this.anims.create({ key: `emote-${name}`, frames: pair.map(frame => ({ key: 'emotes', frame })), frameRate: 2, repeat: -1 })
      }
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
      this.anims.create({ key: `${sheet}-phone`, frames: range(6, 3), frameRate: 6, repeat: -1 })
      this.anims.create({ key: `${sheet}-read`, frames: range(7, 0), frameRate: 5, repeat: -1 })
    }

    spawnWalker(sheet: string, x: number, name: string, background: string, resident: TownResident | null, activity: ResidentActivity, emote: string | null, selection: TownSelection): Walker {
      this.ensureCharacterAnimations(sheet)
      const columns = Math.floor((this.textures.get(sheet).getSourceImage() as HTMLImageElement).width / 32)
      const sprite = this.add.sprite(x, STREET_Y, sheet, columns + 18).setOrigin(0.5, 1).setDepth(STREET_Y)
      sprite.setInteractive({ useHandCursor: true })
      sprite.on('pointerup', () => {
        if (this.dragged) return
        // Clicking the guide/postman walks the self avatar to them first (task 6); a neighbour
        // or one's own sprite is selected immediately, same as clicking their building.
        if (selection === 'npc:assistant' || selection === 'npc:postman') this.walkSelfToAndSelect(sprite.x, sprite.y, selection)
        else handlers.onSelect?.(selection)
      })
      const label = this.add.text(x, STREET_Y + 4, name, {
        fontFamily: FONT, fontSize: '11px', color: '#fff', backgroundColor: background, padding: { x: 5, y: 2 },
      }).setOrigin(0.5, 0).setDepth(4001).setResolution(2)
      const bubble = emote ? this.add.sprite(x, STREET_Y - 66, 'emotes', EMOTES[emote as keyof typeof EMOTES][0]).setOrigin(0.5, 1).setDepth(4002).play(`emote-${emote}`) : null
      const walker: Walker = {
        sprite, label, emote: bubble, sheet, homeX: x, targetX: x, targetY: STREET_Y, state: 'act',
        timer: 600 + (hashString(name) % 2500), activity, resident, patrol: null, venue: 'home', action: 'idle',
        id: resident ? resident.publicId : String(selection),
        overrideUntil: 0, manualWalk: false, arriveSelect: null, travelEmote: null, frozenUntil: 0, keyDriven: false, running: false,
        facing: 'down', stuckMs: 0, lastPresence: null, teleporting: false, lastReportTime: 0,
        npc: null, speech: null, npcActivity: null,
      }
      sprite.play(`${sheet}-idle-down`)
      this.walkers.push(walker)
      return walker
    }

    spawnResident(resident: TownResident, index: number) {
      const activity = activityFor(resident)
      const homeX = plotX(index) + PLOT_WIDTH / 2 + 40

      // Use presence spawn point if available and matches current scene
      let spawnX = homeX
      let spawnY = STREET_Y
      if (resident.presence && resident.presence.scene === 'town') {
        spawnX = resident.presence.x
        spawnY = resident.presence.y
      }

      const walker = this.spawnWalker(
        `char_${characterSheet(resident.publicId)}`,
        spawnX,
        resident.isSelf ? '我' : resident.displayName,
        resident.isSelf ? 'rgba(200,95,71,.92)' : 'rgba(40,30,26,.72)',
        resident,
        activity,
        activity,
        resident.publicId
      )

      // Initialize lastPresence for tracking
      if (resident.presence && resident.presence.scene === 'town') {
        walker.lastPresence = { x: resident.presence.x, y: resident.presence.y }
      }

      // Adjust spawn y for self avatar (spawnWalker always uses STREET_Y for initial position)
      if (resident.isSelf && resident.presence && resident.presence.scene === 'town') {
        walker.sprite.y = spawnY
        walker.targetY = spawnY
        walker.sprite.setDepth(spawnY)
      }

      // Start where the schedule already says they should be, instead of always waking up at home.
      const target = this.evaluateSchedule(walker)
      if (Math.abs(target - walker.sprite.x) > 4) { walker.targetX = target; walker.state = 'walk' }
    }

    spawnNpcs() {
      const guide = this.spawnWalker('npc_scout', academyDoorX - 70, '小助 · AI 助手', 'rgba(53,100,79,.92)', null, 'planned', 'question', 'npc:assistant')
      guide.patrol = [academyDoorX - 120, academyDoorX - 20]
      const postman = this.spawnWalker('npc_postman', PLOT_START - 120, model.unread > 0 ? `邮递员 · ${model.unread} 封新信` : '邮递员', 'rgba(60,90,140,.9)', null, 'working', model.unread > 0 ? 'mail' : null, 'npc:postman')
      postman.patrol = [ACADEMY_X - 100, width - 160]
      postman.targetX = width - 160
      postman.state = 'walk'
    }

    // ---------- 小镇社会: 18 个 NPC 的生成、日程与气泡 ----------

    /** 引擎的真实几何交给纯函数 placeFor 用；坐标常量只在这里出现一次。 */
    townLayout(): TownLayout {
      return {
        academyDoorX,
        plotStartX: PLOT_START,
        gymX: PLOT_START + PLOT_WIDTH / 2,
        cafeX: PLOT_START + PLOT_PITCH + PLOT_WIDTH / 2,
        parkX: ACADEMY_X - 100,
        plazaMinX: YARD_X + 120,
        plazaMaxX: YARD_X + 320,
        worldWidth: width,
      }
    }

    /**
     * 名册到了（或变了）就重建这批 NPC。
     *
     * <p>同屏人数由 densityCap(小时) 决定——这是 plan §2.4 的护栏 B：早晨四五个、午间十来个、
     * 深夜两三个，峰值封在 12。挑谁上场是 selectVisible 的确定性排序，所以同一小时反复调用不会
     * 让街上的人闪来闪去。
     */
    applyTownNpcs(list: TownNpcView[]) {
      for (const walker of this.walkers.filter(item => item.npc !== null)) this.despawnWalker(walker)
      const hour = new Date(Date.now() + serverOffsetMs).getHours()
      const visible = selectVisible(
        list.map(npc => ({ code: npc.code, layer: npc.layer, schedule: npc.schedule, npc })),
        hour,
        densityCap(hour),
      )
      for (const entry of visible) this.spawnTownNpc(entry.npc, hour)
    }

    spawnTownNpc(npc: TownNpcView, hour: number) {
      const sheet = npc.sprite.startsWith('c') && /^c\d+$/.test(npc.sprite)
        ? `char_${Number(npc.sprite.slice(1))}`
        : npc.sprite
      if (!this.textures.exists(sheet)) return
      const slot = activeSlot(npc.schedule, hour)
      const x = slot ? placeFor(slot.place, this.townLayout()) : academyDoorX
      const offset = venueOffset(npc.code, slot?.place ?? 'street')
      const walker = this.spawnWalker(
        sheet,
        x + offset.dx,
        npc.displayName,
        npc.layer === 2 ? 'rgba(84,64,120,.86)' : 'rgba(40,30,26,.62)',
        null,
        'planned',
        null,
        // 三层背景居民不可对话（plan §2.4）；点击他们不该弹出任何东西。
        npc.layer === 3 ? null : `npc:${npc.code}`,
      )
      walker.npc = npc
      walker.id = npc.code
      walker.homeX = x
      walker.sprite.y = STREET_Y + offset.dy
      walker.targetY = walker.sprite.y
      walker.sprite.setDepth(walker.sprite.y)
    }

    despawnWalker(walker: Walker) {
      walker.speech?.destroy()
      walker.travelEmote?.destroy()
      walker.emote?.destroy()
      walker.label.destroy()
      walker.sprite.destroy()
      this.walkers = this.walkers.filter(item => item !== walker)
    }

    /** NPC 版的 evaluateSchedule：地点来自后端下发的日程，动作驱动贴图。 */
    evaluateNpcSchedule(walker: Walker) {
      const npc = walker.npc
      if (!npc) return walker.homeX
      const hour = new Date(Date.now() + serverOffsetMs).getHours()
      const slot = activeSlot(npc.schedule, hour)
      if (!slot) return walker.homeX
      walker.action = slot.activity === 'reading' ? 'read' : slot.activity === 'phone' ? 'phone' : 'idle'
      walker.npcActivity = slot.activity
      const offset = venueOffset(npc.code, slot.place)
      walker.targetY = STREET_Y + offset.dy
      return placeFor(slot.place, this.townLayout()) + offset.dx
    }

    /**
     * 头顶气泡。播的每一句都来自后端已经写好的 retold_text——前端不生成、不改写，
     * 只是把这一手的走样版本取出来放出来（plan §2.2 D12）。
     */
    saySomething(walker: Walker, text: string, holdMs = BUBBLE_HOLD_MS) {
      walker.speech?.destroy()
      const label = this.add.text(0, 0, text, {
        fontFamily: FONT, fontSize: '13px', color: '#2a211c', wordWrap: { width: 208 },
        align: 'center', lineSpacing: 3,
      }).setOrigin(0.5, 1).setResolution(3)
      const padX = 9
      const padY = 7
      const bubble = this.add.graphics()
      // 文字的 origin 是 (0.5, 1)，也就是占 y ∈ [-h, 0]。气泡必须绕着这个范围上下各留一份内边距，
      // 否则上边 padY*2、下边 0，文字会紧贴着底边——看上去就是"文字溢出气泡"。
      bubble.fillStyle(0xfdf8f0, 0.97)
      bubble.fillRoundedRect(-label.width / 2 - padX, -label.height - padY,
        label.width + padX * 2, label.height + padY * 2, 8)
      bubble.lineStyle(1, 0x2a211c, 0.18)
      bubble.strokeRoundedRect(-label.width / 2 - padX, -label.height - padY,
        label.width + padX * 2, label.height + padY * 2, 8)
      const container = this.add.container(walker.sprite.x, walker.sprite.y - 74, [bubble, label]).setDepth(4100)
      // 说话的人可能正站在画面边缘，气泡比人宽得多，直接跟着他就会被镜头切掉半句。
      // 记下半宽，定位时把气泡按视野边界夹回来——人还在原地，只是话框往里挪一点。
      container.setData('halfWidth', label.width / 2 + padX)
      walker.speech = container
      this.positionSpeech(walker)
      this.time.delayedCall(holdMs, () => {
        if (walker.speech === container) walker.speech = null
        container.destroy()
      })
    }

    /** 把气泡放在说话人头顶，但整体不许超出镜头左右边界。 */
    positionSpeech(walker: Walker) {
      const container = walker.speech
      if (!container) return
      const camera = this.cameras.main
      const halfWidth = (container.getData('halfWidth') as number) ?? 0
      const left = camera.scrollX + halfWidth + 8
      const right = camera.scrollX + camera.width / camera.zoom - halfWidth - 8
      const x = right > left ? Math.min(Math.max(walker.sprite.x, left), right) : walker.sprite.x
      container.setPosition(x, walker.sprite.y - 74)
    }

    /**
     * 两个 NPC 撞上了就各自说一句（plan §3.3）。
     *
     * <p>说什么完全取决于说话人自己 knowledge 里那几条——所以同一个镜头里两个人说的必然不一样。
     * 这里刻意<b>不</b>按 affinityToPlayer 分档：那是"这个 NPC 和玩家多熟"，和两个 NPC 之间聊不聊
     * 得起来没有关系。按亲密度分档属于玩家在场的那条路径（maybeInitiate），NPC 彼此之间该用的是
     * 他们自己的 bond，而那条边目前不在名册接口里。
     */
    speakOnEncounter(a: Walker, b: Walker) {
      for (const walker of [a, b]) {
        const points = walker.npc?.talkingPoints ?? []
        if (points.length === 0) continue
        points.slice(0, 2).forEach((point, index) => {
          this.time.delayedCall(index * 2400, () => this.saySomething(walker, point.text))
        })
      }
    }

    /**
     * 玩家靠近时的主动搭话，受护栏 A 的全镇每日预算约束（默认 3 次，小助优先）。
     * 预算耗尽后没有任何 NPC 会再主动开口——这正是"招架不住"被机制封死的地方。
     */
    maybeInitiate(walker: Walker, now: number) {
      const npc = walker.npc
      if (!npc || !this.selfWalker) return
      if (now < this.nextInitiativeAt) return
      if (Math.abs(walker.sprite.x - this.selfWalker.sprite.x) > 90) return
      if (!canInitiate(initiativeBudget, npc.code)) return
      const lines = pointsToPlay(bubbleTierFor(npc.affinityToPlayer), npc.talkingPoints)
      if (lines.length === 0) return
      initiativeBudget = consume(initiativeBudget, npc.code)
      this.nextInitiativeAt = now + 30_000
      this.saySomething(walker, lines[0].text)
    }

    // ---------- 劳作动画 (M2-3) ----------

    /**
     * 劳作图是独立的一张张 spritesheet，尺寸各不相同，几何只能从 sidecar 读，所以要等
     * labour-anims.json 到齐之后再发起第二轮加载。没有素材就整段跳过——NPC 照常站着。
     */
    loadLabourAnimations() {
      const manifest = this.cache.json.get('labourAnims') as Record<string, LabourAnim> | undefined
      if (!manifest) return
      this.labourGeometry = manifest
      for (const [name, geometry] of Object.entries(manifest)) {
        this.load.spritesheet(`labour_${name}`, `${ASSETS}/characters/${geometry.file}`,
          { frameWidth: geometry.frameWidth, frameHeight: geometry.frameHeight })
      }
      this.load.once('complete', () => {
        for (const [name, geometry] of Object.entries(manifest)) {
          const key = `labour-${name}`
          if (this.anims.exists(key) || !this.textures.exists(`labour_${name}`)) continue
          // 每张图里四个朝向首尾相接，只播朝向镜头的那一段（钓鱼那张的正面不是第一段）。
          const frames: number[] = []
          for (let i = geometry.downStart; i < geometry.downEnd; i += 1) frames.push(i)
          this.anims.create({
            key,
            frames: frames.map(frame => ({ key: `labour_${name}`, frame })),
            frameRate: 10,
            repeat: -1,
          })
        }
      })
      this.load.start()
    }

    /** 干活时整体换成劳作贴图，停下来再换回本人的角色表。 */
    applyLabour(walker: Walker, activity: NpcActivity) {
      const key = `labour-${activity}`
      if (!this.anims.exists(key)) return false
      walker.sprite.setTexture(`labour_${activity}`)
      walker.sprite.play(key, true)
      return true
    }

    restoreSheet(walker: Walker) {
      if (walker.sprite.texture.key === walker.sheet) return
      walker.sprite.setTexture(walker.sheet)
      walker.sprite.play(`${walker.sheet}-idle-down`, true)
    }

    // ---------- 观察模式 (M2-6) ----------

    /**
     * 兴趣点：镇上真正有人聚集的三处，从西到东排。
     *
     * <p>刻意只取三处。整圈固定 30 秒（验收标准就是"一段 30 秒长镜头"），点越多每段越短——
     * 五个点时每段只有 2.4 秒平移，镜头像在甩而不是在巡游。三个点正好是 6 秒停 + 4 秒移，
     * 也就是 observation-mode.ts 里那组默认值当初设计的节奏。
     *
     * <p>纵向对着街面而不是建筑顶：小镇的内容（人、车、摊位、店面）都贴着基线，镜头抬太高
     * 会有大半屏是空草地。zoom 也调高一档，人物和气泡才看得清。
     */
    observationPoints() {
      const layout = this.townLayout()
      const y = BASELINE + 10
      return [
        { x: layout.plazaMinX + 160, y, zoom: OBSERVATION_ZOOM },
        { x: layout.academyDoorX, y, zoom: OBSERVATION_ZOOM },
        { x: layout.cafeX, y, zoom: OBSERVATION_ZOOM },
      ]
    }

    setObservationMode(on: boolean) {
      observationOn = on
      const camera = this.cameras.main
      if (on) {
        this.releaseCameraFollow()
        // 整圈按 30 秒排：plan 的验收标准就是"一段 30 秒长镜头"，而兴趣点有五个——用默认的
        // 每处 6s 停 + 4s 移，一圈要 50 秒，录 30 秒只能扫到五处里的三处。把总时长固定成 30 秒
        // 再按点数均分（六成停、四成移），无论以后兴趣点增减，一圈都还是一段 30 秒长镜头。
        const points = this.observationPoints()
        const perLeg = OBSERVATION_LOOP_MS / Math.max(1, points.length)
        this.observationItinerary = buildItinerary(points, {
          holdMs: Math.round(perLeg * 0.6),
          panMs: Math.round(perLeg * 0.4),
        })
        this.observationStartedAt = this.time.now
      } else {
        this.observationItinerary = []
        camera.pan(this.selfWalker?.sprite.x ?? academyDoorX, BASELINE - 120, 700, 'Sine.easeInOut')
        camera.zoomTo(Math.min(1.2, Math.max(0.75, this.scale.height / 820)), 500)
        this.startFollowingSelf()
      }
    }

    /**
     * 观察模式下让镜头里的人自己开口。
     *
     * <p>没有这一步，观察模式就只是"把镜头挪来挪去"——街上的人是安静的，因为路遇气泡要靠两个人
     * 恰好擦肩，而同一时段在同一地点的人本来就不多。验收标准要的是「镜头扫过小镇，NPC 各自活动、
     * 彼此搭话，说的内容互不相同」，所以镜头停下来的时候，画面里的人该说话。
     *
     * <p>说的仍然只能是他自己 knowledge 里那几条，所以两个人说的必然不一样——限知在这里是看得见的。
     * 这不占护栏 A 的主动性预算：那份预算管的是"NPC 跑来找玩家要回应"，而观察模式里玩家只是在看。
     */
    speakAmbientLine(now: number) {
      if (now < this.nextAmbientLineAt) return
      const camera = this.cameras.main
      const centerX = camera.scrollX + camera.width / camera.zoom / 2
      // 只挑靠近画面中央的人：站在边上的人说话，气泡会被镜头切掉一半。
      const halfView = camera.width / camera.zoom / 2
      const candidates = this.walkers.filter(walker =>
        walker.npc?.talkingPoints?.length && !walker.speech
        && Math.abs(walker.sprite.x - centerX) < halfView * 0.6)
      if (candidates.length === 0) return
      const walker = candidates[Math.floor(this.time.now / 997) % candidates.length]
      const points = walker.npc?.talkingPoints ?? []
      const point = points[Math.floor(this.time.now / 1471) % points.length]
      this.saySomething(walker, point.text)
      this.nextAmbientLineAt = now + 3200
    }

    /** 每帧把相机放到 nextLeg 算出来的位置上；所有算术都在纯函数里，这里只负责画。 */
    updateObservation() {
      if (!observationOn || this.observationItinerary.length === 0) return
      const position = nextLeg(this.observationItinerary, this.time.now - this.observationStartedAt)
      if (!position) return
      // 停下来看的时候才让人说话；镜头还在移动时弹气泡看不清。
      if (position.phase === 'holding') this.speakAmbientLine(this.time.now)
      const camera = this.cameras.main
      const legs = this.observationItinerary
      const previous = legs[(position.legIndex - 1 + legs.length) % legs.length]
      const target = position.leg
      if (position.phase === 'panning') {
        const t = easeInOutSine(position.progress)
        camera.centerOn(previous.x + (target.x - previous.x) * t, previous.y + (target.y - previous.y) * t)
        camera.setZoom(previous.zoom + (target.zoom - previous.zoom) * t)
      } else {
        camera.centerOn(target.x, target.y)
        camera.setZoom(target.zoom)
      }
    }

    // ---------- behaviour ----------

    /** NPC patrol wandering only — resident placement is schedule-driven, see evaluateSchedule. */
    chooseNextTarget(walker: Walker) {
      const [from, to] = walker.patrol as [number, number]
      const roll = hashString(`${walker.sheet}:${walker.homeX}:${Math.floor(this.time.now)}`) % 100
      return walker.resident === null && walker.sheet === 'npc_postman'
        ? (Math.abs(walker.sprite.x - to) < 8 ? from : to)
        : from + (roll / 100) * (to - from)
    }

    /** x position for a venue, with a small deterministic per-walker spread so residents don't stack exactly. */
    venueX(venue: TownVenue, walker: Walker) {
      const spread = (hashString(`${walker.sheet}:${walker.homeX}`) % 5) * 16 - 32
      if (venue === 'academy') return academyDoorX + spread
      if (venue === 'court') return courtX + spread
      if (venue === 'park') return parkX + spread
      return walker.homeX
    }

    /** 日程驱动规则: where the resident's schedule says they should be right now, and what to do there. */
    evaluateSchedule(walker: Walker) {
      if (!walker.resident) return walker.homeX
      const result = whereShouldBe(walker.resident, Date.now(), serverOffsetMs)
      walker.venue = result.venue
      walker.action = result.action
      return this.venueX(result.venue, walker)
    }

    performActivity(walker: Walker) {
      // 三层背景居民在公园干活时换成劳作动画；一二层不干活，免得"你熟悉的面孔"整天在浇水。
      if (walker.npc && walker.npc.layer === 3 && walker.npcActivity && LABOUR_ACTIVITIES.has(walker.npcActivity)
        && this.applyLabour(walker, walker.npcActivity)) {
        walker.timer = 3200 + (hashString(walker.id) % 2600)
        return
      }
      if (walker.npc) this.restoreSheet(walker)
      const key = walker.action === 'read' ? `${walker.sheet}-read`
        : walker.action === 'phone' ? `${walker.sheet}-phone`
        : `${walker.sheet}-idle-down`
      walker.sprite.play(key, true)
      const seed = hashString(`${walker.sheet}:${walker.homeX}:${Math.floor(this.time.now)}`)
      // Residents re-check their schedule roughly every 5s; NPC patrol timing is unchanged.
      walker.timer = walker.resident ? 4600 + (seed % 900) : 2600 + (seed % 4200)
    }

    // ---------- self avatar: 8-directional walk, click-to-walk & greetings (task 6) ----------

    /** Ground/street click: walk the self avatar there, overriding schedule placement for 20s.
     * The clicked point is corrected onto the nearest standable ground first, so clicking on a
     * building or off the map still gives a sensible destination. */
    walkSelfToGround(worldX: number, worldY: number, run = this.runMode) {
      const self = this.selfWalker
      if (!self) return
      const target = nearestStandable({ x: worldX, y: worldY }, this.collisionWorld)
      self.arriveSelect = null
      self.travelEmote?.destroy()
      self.travelEmote = null
      self.manualWalk = true
      self.running = run
      if (Math.hypot(target.x - self.sprite.x, target.y - self.sprite.y) < 6) { self.overrideUntil = this.time.now + SELF_OVERRIDE_MS; return }
      self.targetX = target.x
      self.targetY = target.y
      self.state = 'walk'
      self.stuckMs = 0
      this.startFollowingSelf()
    }

    /** Clicking an NPC or the academy walks the self avatar there first; the select/enter action
     * fires once they arrive (dispatchArrival), with a "?" bubble while on the way. */
    walkSelfToAndSelect(worldX: number, worldY: number, selection: TownSelection, run = this.runMode) {
      const self = this.selfWalker
      if (!self) { this.dispatchArrival(selection); return }
      const target = nearestStandable({ x: worldX, y: worldY }, this.collisionWorld)
      self.manualWalk = true
      self.running = run
      if (Math.hypot(target.x - self.sprite.x, target.y - self.sprite.y) < 6) { this.dispatchArrival(selection); return }
      self.arriveSelect = selection
      self.targetX = target.x
      self.targetY = target.y
      self.state = 'walk'
      self.stuckMs = 0
      self.travelEmote?.destroy()
      self.travelEmote = this.add.sprite(self.sprite.x, self.sprite.y - 66, 'emotes', EMOTES.question[0]).setOrigin(0.5, 1).setDepth(4002).play('emote-question')
      this.startFollowingSelf()
    }

    dispatchArrival(selection: TownSelection) {
      if (selection === 'academy') enterAcademy()
      else handlers.onSelect?.(selection)
    }

    /** Desktop movement: arrow keys or WASD move the self avatar in any of 8 directions (diagonal
     * input is normalized so it is no faster than a straight move), sliding along walls via the
     * same collision model click-to-walk uses. */
    handleSelfKeys(now: number, delta: number) {
      const self = this.selfWalker
      if (!self || !this.selfKeys || self.frozenUntil > now) return
      const { cursors, keyA, keyD, keyW, keyS, shift } = this.selfKeys
      const left = Boolean(cursors?.left.isDown || keyA?.isDown)
      const right = Boolean(cursors?.right.isDown || keyD?.isDown)
      const up = Boolean(cursors?.up.isDown || keyW?.isDown)
      const down = Boolean(cursors?.down.isDown || keyS?.isDown)
      const inputX = left === right ? 0 : left ? -1 : 1
      const inputY = up === down ? 0 : up ? -1 : 1
      if (inputX === 0 && inputY === 0) return
      const running = this.runMode || Boolean(shift?.isDown)
      self.running = running
      const from: Point = { x: self.sprite.x, y: self.sprite.y }
      const step = movementDelta(inputX, inputY, moveSpeed(running), delta)
      const resolved = resolveMove(from, { x: from.x + step.x, y: from.y + step.y }, this.collisionWorld)
      self.sprite.x = resolved.x
      self.sprite.y = resolved.y
      self.sprite.setDepth(resolved.y)
      self.targetX = resolved.x
      self.targetY = resolved.y
      if (self.arriveSelect !== null) { self.arriveSelect = null; self.travelEmote?.destroy(); self.travelEmote = null }
      self.manualWalk = true
      self.overrideUntil = now + SELF_OVERRIDE_MS
      const dx = resolved.x - from.x
      const dy = resolved.y - from.y
      if (dx === 0 && dy === 0) {
        // Held against a wall: face the blocked direction instead of moon-walking in place.
        self.sprite.play(`${self.sheet}-idle-${self.facing}`, true)
      } else {
        self.facing = dominantDirection(dx, dy, self.facing)
        self.sprite.play(`${self.sheet}-walk-${self.facing}`, true)
      }
      self.sprite.anims.timeScale = running ? RUN_ANIM_SCALE : 1
      self.keyDriven = true
      this.startFollowingSelf()
    }

    /** Two walkers passing within greeting distance, walking toward each other, pause and wave a heart. */
    detectGreetings(now: number) {
      const moving = this.walkers.filter(walker => walker.state === 'walk' && walker.frozenUntil <= now)
      for (let i = 0; i < moving.length; i += 1) {
        for (let j = i + 1; j < moving.length; j += 1) {
          const a = moving[i]
          const b = moving[j]
          const aDir: WalkDirection = a.targetX - a.sprite.x < 0 ? 'left' : 'right'
          const bDir: WalkDirection = b.targetX - b.sprite.x < 0 ? 'left' : 'right'
          if (shouldGreet({ id: a.id, x: a.sprite.x, dir: aDir }, { id: b.id, x: b.sprite.x, dir: bDir }, this.greetCooldowns, now)) {
            this.triggerGreeting(a, b, now)
          }
        }
      }
    }

    triggerGreeting(a: Walker, b: Walker, now: number) {
      registerGreet(this.greetCooldowns, a.id, b.id, now)
      a.frozenUntil = now + GREET_PAUSE_MS
      b.frozenUntil = now + GREET_PAUSE_MS
      const aFacesRight = a.sprite.x <= b.sprite.x
      a.sprite.play(`${a.sheet}-idle-${aFacesRight ? 'right' : 'left'}`, true)
      b.sprite.play(`${b.sheet}-idle-${aFacesRight ? 'left' : 'right'}`, true)
      for (const walker of [a, b]) {
        walker.travelEmote?.destroy()
        walker.travelEmote = this.add.sprite(walker.sprite.x, walker.sprite.y - 66, 'emotes', EMOTES.heart[0]).setOrigin(0.5, 1).setDepth(4002).play('emote-heart')
      }
      this.speakOnEncounter(a, b)
    }

    update(_time: number, delta: number) {
      this.atmosphere?.update(delta)
      this.atmosphere?.update(delta)
      const now = this.time.now
      this.handleSelfKeys(now, delta)
      this.updateObservation()
      this.detectGreetings(now)
      for (const walker of this.walkers) {
        if (walker.frozenUntil > now) {
          walker.label.setPosition(walker.sprite.x, walker.sprite.y + 4)
          walker.emote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 62)
          walker.travelEmote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 66)
          this.positionSpeech(walker)
          continue
        }
        if (walker.frozenUntil !== 0) {
          // Just came out of a greeting: drop the heart bubble and re-decide almost immediately.
          walker.frozenUntil = 0
          walker.travelEmote?.destroy()
          walker.travelEmote = null
          walker.timer = 200
        }
        if (walker.keyDriven) {
          walker.keyDriven = false
          walker.label.setPosition(walker.sprite.x, walker.sprite.y + 4)
          walker.emote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 62)
          continue
        }
        if (walker.state === 'walk' && walker.resident?.isSelf) {
          // Self avatar: free 2D pathing toward (targetX, targetY), collision-resolved every step
          // so a straight line into a wall slides along it instead of clipping through.
          const from: Point = { x: walker.sprite.x, y: walker.sprite.y }
          const to: Point = { x: walker.targetX, y: walker.targetY }
          const stepped = stepTowardPoint(from, to, moveSpeed(walker.running), delta)
          const resolved = resolveMove(from, stepped, this.collisionWorld)
          walker.sprite.x = resolved.x
          walker.sprite.y = resolved.y
          walker.sprite.setDepth(resolved.y)
          const dx = resolved.x - from.x
          const dy = resolved.y - from.y
          if (dx === 0 && dy === 0) {
            walker.stuckMs += delta
            walker.sprite.play(`${walker.sheet}-idle-${walker.facing}`, true)
          } else {
            walker.stuckMs = 0
            walker.facing = dominantDirection(dx, dy, walker.facing)
            walker.sprite.play(`${walker.sheet}-walk-${walker.facing}`, true)
          }
          walker.sprite.anims.timeScale = walker.running ? RUN_ANIM_SCALE : 1
          const remaining = Math.hypot(walker.targetX - walker.sprite.x, walker.targetY - walker.sprite.y)

          // Periodic reporting during movement (throttled by presenceReporter)
          if (now - walker.lastReportTime > 3000) {
            walker.lastReportTime = now
            this.reportSelfPresence()
          }

          // A straight line can dead-end against a wall (no A* pathing); give up gracefully
          // after a short stall instead of animating in place forever.
          if (remaining < 1 || walker.stuckMs > SELF_STUCK_MS) {
            walker.state = 'act'
            walker.running = false
            walker.stuckMs = 0
            walker.sprite.anims.timeScale = 1
            if (walker.arriveSelect !== null) {
              const selection = walker.arriveSelect
              walker.arriveSelect = null
              walker.travelEmote?.destroy()
              walker.travelEmote = null
              this.dispatchArrival(selection)
            } else if (walker.manualWalk) {
              walker.overrideUntil = now + SELF_OVERRIDE_MS
            }
            walker.manualWalk = false
            this.performActivity(walker)
            // Report self presence when stopped
            this.reportSelfPresence()
          }
        } else if (walker.state === 'walk') {
          const distance = walker.targetX - walker.sprite.x
          walker.sprite.x = stepToward(walker.sprite.x, walker.targetX, moveSpeed(false), delta)
          if (!walker.resident?.isSelf) walker.sprite.y = stepToward(walker.sprite.y, walker.targetY, moveSpeed(false), delta)
          const direction: Direction = distance < 0 ? 'left' : 'right'
          walker.sprite.play(`${walker.sheet}-walk-${direction}`, true)
          if (Math.abs(distance) < 1) {
            walker.state = 'act'
            walker.running = false
            walker.sprite.anims.timeScale = 1
            if (walker.arriveSelect !== null) {
              const selection = walker.arriveSelect
              walker.arriveSelect = null
              walker.travelEmote?.destroy()
              walker.travelEmote = null
              this.dispatchArrival(selection)
            } else if (walker.manualWalk) {
              walker.overrideUntil = now + SELF_OVERRIDE_MS
            }
            walker.manualWalk = false
            this.performActivity(walker)
            // Report self presence when stopped (non-self avatars don't report)
            if (walker.resident?.isSelf) this.reportSelfPresence()
          }
        } else {
          walker.timer -= delta
          if (walker.timer <= 0) {
            let next: number
            if (walker.npc) next = this.evaluateNpcSchedule(walker)
            else if (walker.patrol) next = this.chooseNextTarget(walker)
            else if (walker.resident?.isSelf && now < walker.overrideUntil) next = walker.sprite.x
            else next = this.evaluateSchedule(walker)
            if (Math.abs(next - walker.sprite.x) > 4) { walker.targetX = next; walker.state = 'walk' } else this.performActivity(walker)
          }
        }
        walker.label.setPosition(walker.sprite.x, walker.sprite.y + 4)
        walker.emote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 62)
        walker.travelEmote?.setPosition(walker.sprite.x + 14, walker.sprite.y - 66)
        this.positionSpeech(walker)
        if (walker.npc) this.maybeInitiate(walker, now)
      }
      for (const vehicle of this.vehicles) {
        vehicle.sprite.x += (vehicle.speed * delta) / 1000
        if (vehicle.speed > 0 && vehicle.sprite.x > width + 40) vehicle.sprite.x = -vehicle.sprite.width - 40
        if (vehicle.speed < 0 && vehicle.sprite.x < -vehicle.sprite.width - 40) vehicle.sprite.x = width + 40
      }
    }

    // ---------- camera & input ----------

    setupCamera() {
      const camera = this.cameras.main
      camera.setBounds(0, 0, width, WORLD_HEIGHT)
      camera.setZoom(Math.min(1.2, Math.max(0.75, this.scale.height / 820)))
      const self = residents.find(item => item.isSelf) ?? residents[0]
      const center = self ? this.buildingCenters.get(self.publicId) : undefined
      camera.centerOn(academyDoorX, BASELINE - 260)
      if (center) this.time.delayedCall(700, () => camera.pan(center.x, center.y + 40, 1600, 'Sine.easeInOut'))
    }

    /** 家具交互：坐下/查看公告/互动 */
    onFurnitureInteract(item: FurnitureItem) {
      if (!this.selfWalker || this.activeFurnitureId === item.id) {
        // 再次点击同一家具 = 取消交互
        this.endFurnitureInteraction()
        return
      }

      // 走到家具附近
      const targetX = item.x + (item.collision?.offsetX ?? 0) + (item.collision?.width ?? 32) / 2
      const targetY = item.y + (item.collision?.offsetY ?? 0) + (item.collision?.height ?? 16) / 2
      this.selfWalker.targetX = targetX
      this.selfWalker.targetY = targetY + 40 // 站在家具前方
      this.selfWalker.state = 'walk'
      this.activeFurnitureId = item.id

      // 到达后触发交互
      this.time.delayedCall(1000, () => {
        if (this.activeFurnitureId !== item.id) return
        this.executeFurnitureInteraction(item)
      })
    }

    /** 执行具体的家具交互 */
    executeFurnitureInteraction(item: FurnitureItem) {
      if (!this.selfWalker) return

      switch (item.interactionType) {
        case 'sit':
          // 坐下：停止移动，播放坐姿（暂用 idle 动画）
          this.selfWalker.state = 'act'
          this.selfWalker.sprite.anims.stop()
          this.showInteractionBubble(item.x, item.y - 60, '💺')
          break

        case 'read':
          // 查看告示牌/菜单：显示气泡提示
          this.selfWalker.state = 'act'
          this.showInteractionBubble(item.x, item.y - 60, '📋')
          // 实际项目中可打开 UI 弹窗显示公告内容
          break

        case 'view':
          // 查看报刊亭等：显示气泡
          this.selfWalker.state = 'act'
          this.showInteractionBubble(item.x, item.y - 60, '👀')
          break
      }
    }

    /** 显示交互气泡（简化版，实际可用 emote 系统） */
    showInteractionBubble(x: number, y: number, emoji: string) {
      if (this.interactionBubble) this.interactionBubble.destroy()
      const text = this.add.text(x, y, emoji, { fontSize: '24px' }).setOrigin(0.5).setDepth(5000)
      this.interactionBubble = text
      this.time.delayedCall(2000, () => {
        if (this.interactionBubble === text) this.interactionBubble = null
        text.destroy()
      })
    }

    /** 结束家具交互 */
    endFurnitureInteraction() {
      this.activeFurnitureId = null
      if (this.interactionBubble) {
        this.interactionBubble.destroy()
        this.interactionBubble = null
      }
      if (this.selfWalker && this.selfWalker.state === 'act') {
        this.selfWalker.state = 'walk'
      }
    }

    setupInput() {
      this.selfWalker = this.walkers.find(walker => walker.resident?.isSelf) ?? null
      this.selfKeys = {
        cursors: this.input.keyboard?.createCursorKeys(),
        keyA: this.input.keyboard?.addKey('A'),
        keyD: this.input.keyboard?.addKey('D'),
        keyW: this.input.keyboard?.addKey('W'),
        keyS: this.input.keyboard?.addKey('S'),
        shift: this.input.keyboard?.addKey('SHIFT'),
      }
      const camera = this.cameras.main
      this.input.on('pointerdown', (pointer: PhaserNs.Input.Pointer) => {
        this.atmosphere?.beginManualControl()
        this.dragStart = { x: pointer.x, y: pointer.y, scrollX: camera.scrollX, scrollY: camera.scrollY }
        this.dragged = false
      })
      this.input.on('pointermove', (pointer: PhaserNs.Input.Pointer) => {
        if (!this.dragStart || !pointer.isDown) return
        const dx = pointer.x - this.dragStart.x
        const dy = pointer.y - this.dragStart.y
        if (!this.dragged && Math.abs(dx) + Math.abs(dy) > 6) this.pauseCameraFollowForDrag()
        camera.setScroll(this.dragStart.scrollX - dx / camera.zoom, this.dragStart.scrollY - dy / camera.zoom)
      })
      this.input.on('pointerup', (pointer: PhaserNs.Input.Pointer, objects: unknown[]) => {
        if (!this.dragged && objects.length === 0 && pointer.getDistance() < 6) {
          handlers.onSelect?.(null)
          this.endFurnitureInteraction() // 点击地面时取消家具交互
          const shiftClick = Boolean((pointer.event as MouseEvent | undefined)?.shiftKey)
          this.walkSelfToGround(pointer.worldX, pointer.worldY, this.runMode || shiftClick)
        }
        this.dragStart = null
        this.atmosphere?.endManualControl()
      })
      this.input.on('wheel', (_pointer: PhaserNs.Input.Pointer, _objects: unknown[], _dx: number, dy: number) => {
        camera.setZoom(Math.min(2.4, Math.max(0.5, camera.zoom - dy * 0.0012)))
      })
    }

    /** Engages smooth camera-follow on the self avatar once it starts moving under player control
     * (keys or a click-to-walk); a no-op while a drag pan or another camera move is in charge. */
    startFollowingSelf() {
      if (this.followingCamera || this.time.now < this.cameraFollowPausedUntil) return
      const self = this.selfWalker
      if (!self) return
      this.cameras.main.startFollow(self.sprite, false, 0.08, 0.08, 0, 40)
      this.followingCamera = true
    }

    /** Hands the camera to a one-off pan/tween for a few seconds so follow doesn't immediately
     * yank it back toward the self avatar mid-tween. */
    releaseCameraFollow(pauseMs = 3000) {
      this.cameras.main.stopFollow()
      this.followingCamera = false
      this.cameraFollowPausedUntil = this.time.now + pauseMs
    }

    /** Hands camera control to a drag pan for a few seconds so it does not fight the player's hand. */
    pauseCameraFollowForDrag() {
      this.dragged = true
      this.releaseCameraFollow()
    }

    /** HUD toggle for touch users; Shift still runs on its own while held. */
    setRunMode(enabled: boolean) {
      this.runMode = enabled
      if (!enabled && this.selfWalker) {
        this.selfWalker.running = false
        this.selfWalker.sprite.anims.timeScale = 1
      }
    }

    setNight(night: boolean, instant = false) {
      if (this.night === night) return
      this.night = night
      const duration = instant ? 0 : 900
      this.tweens.add({ targets: this.nightOverlay, alpha: night ? 0.55 : 0, duration, ease: 'Sine.easeInOut' })
      for (const light of this.glows) this.tweens.add({ targets: light, alpha: night ? (light as PhaserNs.GameObjects.Image).getData('targetAlpha') ?? 1 : 0, duration, ease: 'Sine.easeInOut' })
    }

    focusOn(publicId: string) {
      const center = publicId === 'academy' ? { x: academyDoorX, y: BASELINE - 300 } : this.buildingCenters.get(publicId)
      if (center) { this.releaseCameraFollow(); this.cameras.main.pan(center.x, center.y + 40, 500, 'Sine.easeInOut') }
    }

    // ---------- live updates ----------

    /** Updates residents' schedules/activity without rebuilding the scene; rebuilds only plots whose blueprint changed. */
    applyResidents(newResidents: TownResident[]) {
      for (const resident of newResidents) {
        const walker = this.walkers.find(item => item.resident?.publicId === resident.publicId)
        if (!walker) continue
        walker.resident = resident

        // Handle presence updates for non-self residents (and stale ones)
        const remote = presenceTarget(resident.presence)
        if (remote && !resident.isSelf) {
          const current = { x: walker.sprite.x, y: walker.sprite.y }
          const hasMovedSignificantly = walker.lastPresence &&
            Math.hypot(remote.x - walker.lastPresence.x, remote.y - walker.lastPresence.y) > 4

          if (hasMovedSignificantly || walker.lastPresence === null) {
            // Position changed: decide walk vs teleport
            if (shouldTeleport(current, remote)) {
              // Teleport with fade transition (distance > 800px)
              walker.teleporting = true
              this.tweens.add({
                targets: walker.sprite,
                alpha: 0,
                duration: 150,
                onComplete: () => {
                  walker.sprite.setPosition(remote.x, remote.y)
                  walker.sprite.setDepth(remote.y)
                  walker.targetX = remote.x
                  walker.targetY = remote.y
                  walker.lastPresence = { x: remote.x, y: remote.y }
                  this.tweens.add({
                    targets: walker.sprite,
                    alpha: 1,
                    duration: 150,
                    onComplete: () => { walker.teleporting = false }
                  })
                }
              })
            } else {
              // Walk smoothly to new position
              walker.targetX = remote.x
              walker.targetY = remote.y
              walker.state = 'walk'
              walker.lastPresence = { x: remote.x, y: remote.y }
            }
          }
        }

        // Self avatar: only sync if not manually controlled
        if (resident.isSelf && remote && !resident.presence?.stale && !walker.manualWalk && walker.state === 'act') {
          walker.sprite.setPosition(remote.x, remote.y)
          walker.sprite.setDepth(remote.y)
          walker.targetX = remote.x
          walker.targetY = remote.y
          walker.facing = (remote.facing as Direction) || walker.facing
        }

        const activity = activityFor(resident)
        if (walker.activity !== activity) {
          walker.activity = activity
          walker.emote?.play(`emote-${activity}`, true)
        }
        walker.timer = Math.min(walker.timer, 300) // re-check the schedule almost immediately
        const index = this.residentIndex.get(resident.publicId)
        if (index !== undefined) this.refreshPlot(resident, index)
      }
    }

    /** Camera pan + a roof prop dropping in with a bounce + a small particle burst + a brief "done" bubble. */
    celebrateResident(publicId: string) {
      const center = this.buildingCenters.get(publicId)
      if (center) { this.releaseCameraFollow(); this.cameras.main.pan(center.x, center.y + 40, 500, 'Sine.easeInOut') }
      const roof = this.roofTop.get(publicId)
      if (roof) {
        const prop = this.add.image(roof.x, roof.y - 260, 'town', 'roofprop_3').setOrigin(0.5, 1).setDepth(6000)
        this.tweens.add({
          targets: prop,
          y: roof.y - 4,
          duration: 650,
          ease: 'Bounce.easeOut',
          onComplete: () => { this.time.delayedCall(1800, () => prop.destroy()) },
        })
        this.spawnBurst(roof.x, roof.y - 30)
      }
      const walker = this.walkers.find(item => item.resident?.publicId === publicId)
      if (walker) {
        const bubble = this.add.sprite(walker.sprite.x, walker.sprite.y - 78, 'emotes', EMOTES.done[0])
          .setOrigin(0.5, 1).setDepth(4003).setAlpha(0).play('emote-done')
        this.tweens.add({
          targets: bubble,
          alpha: 1,
          y: walker.sprite.y - 92,
          duration: 220,
          onComplete: () => { this.time.delayedCall(1300, () => { this.tweens.add({ targets: bubble, alpha: 0, duration: 260, onComplete: () => bubble.destroy() }) }) },
        })
      }
    }

    spawnBurst(x: number, y: number) {
      const count = 12
      for (let i = 0; i < count; i += 1) {
        const angle = (Math.PI * 2 * i) / count + Math.random() * 0.3
        const speed = 50 + Math.random() * 60
        const spark = i % 3 === 0
          ? this.add.image(x, y, 'town', 'flowers_1').setScale(0.5)
          : this.add.image(x, y, 'spark')
        spark.setDepth(6001)
        this.tweens.add({
          targets: spark,
          x: x + Math.cos(angle) * speed,
          y: y + Math.sin(angle) * speed - 30,
          alpha: 0,
          duration: 650 + Math.random() * 250,
          ease: 'Cubic.easeOut',
          onComplete: () => spark.destroy(),
        })
      }
    }
  }

  /** Fades to the 成长学院 interior (task 7 wiring): town scene sleeps, academy scene takes over. */
  function enterAcademy() {
    if (!sceneRef || academyEntered || academyBusy) return
    academyBusy = true
    const town = sceneRef
    const cam = town.cameras.main
    cam.fadeOut(SCENE_FADE_MS, 8, 10, 8)
    cam.once('camerafadeoutcomplete', () => {
      town.scene.sleep()
      const phaserGame = town.sys.game
      if (phaserGame.scene.getScene('academy')) phaserGame.scene.remove('academy')
      const academyOptions = { residents: academyResidents(latestModel), onBack: () => exitAcademy() }
      const SceneClass = createAcademyScene(Phaser, academyOptions)
      const academyScene = phaserGame.scene.add('academy', SceneClass, true, academyOptions)
      academyScene?.events.once('create', () => { academyScene.cameras.main.fadeIn(SCENE_FADE_MS, 8, 10, 8) })
      academyEntered = true
      academyBusy = false
      handlers.onAcademyChange?.(true)
    })
  }

  async function enterRoom(roomId: string) {
    if (!sceneRef || academyBusy) return
    const paths: Record<string, string> = { home: 'home-living-room', academy: 'academy-study', gym: 'public-gym' }
    const file = paths[roomId]
    if (!file) return
    const response = await fetch(`${ASSETS}/maps/${file}.json`)
    const room = parseRoomMap(await response.json())
    const game = sceneRef.sys.game
    const key = interiorSceneKey(room.id)
    if (game.scene.getScene(key)) game.scene.remove(key)
    game.scene.add(key, createInteriorScene(Phaser, { room, onExit: target => target === 'town' && exitRoom() }), true)
    sceneRef.scene.sleep()
  }

  function exitRoom() { sceneRef?.scene.wake('town') }

  /** Reverses enterAcademy(): fades the interior out, wakes the street back up. */
  function exitAcademy() {
    if (!academyEntered || academyBusy) return
    academyBusy = true
    const phaserGame = sceneRef?.sys.game
    const academyScene = phaserGame?.scene.getScene('academy')
    const finish = () => {
      phaserGame?.scene.stop('academy')
      if (sceneRef) {
        sceneRef.scene.wake('town')
        sceneRef.cameras.main.fadeIn(SCENE_FADE_MS, 8, 10, 8)
      }
      academyEntered = false
      academyBusy = false
      handlers.onAcademyChange?.(false)
    }
    const cam = academyScene?.cameras.main
    if (cam) {
      cam.fadeOut(SCENE_FADE_MS, 8, 10, 8)
      cam.once('camerafadeoutcomplete', finish)
    } else {
      finish()
    }
  }

  const game = new Phaser.Game({
    type: Phaser.AUTO,
    parent: container,
    width: container.clientWidth || 960,
    height: container.clientHeight || 600,
    pixelArt: true,
    roundPixels: false,
    backgroundColor: '#78a95f',
    scene: [TownScene],
    scale: { mode: Phaser.Scale.RESIZE, autoCenter: Phaser.Scale.NO_CENTER },
    audio: { noAudio: true },
  })

  // Flush presence when page becomes hidden
  const visibilityHandler = () => {
    if (document.hidden) presenceReporter.flush()
  }
  document.addEventListener('visibilitychange', visibilityHandler)

  return {
    setNight: night => { desiredNight = night; sceneRef?.setNight(night) },
    setRun: running => { desiredRun = running; sceneRef?.setRunMode(running) },
    focus: publicId => sceneRef?.focusOn(publicId),
    applyModel: nextModel => {
      latestModel = nextModel
      serverOffsetMs = computeServerOffset(nextModel.serverTime)
      sceneRef?.applyResidents(nextModel.residents)
    },
    celebrate: publicId => { celebrationQueue.push(publicId); runCelebrationQueue() },
    applyNpcs: (npcs, budget) => {
      townNpcRoster = npcs
      initiativeBudget = budget
      sceneRef?.applyTownNpcs(npcs)
    },
    setObservation: on => sceneRef?.setObservationMode(on),
    enterAcademy,
    exitAcademy,
    enterRoom,
    exitRoom,
    destroy: () => {
      presenceReporter.flush()
      document.removeEventListener('visibilitychange', visibilityHandler)
      sceneRef?.atmosphere?.destroy()
      sceneRef = null
      celebrationQueue = []
      game.destroy(true)
    },
  }
}
