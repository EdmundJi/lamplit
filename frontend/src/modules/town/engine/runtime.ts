import type { CompanionPet } from '../companion-visual'
import type { CompanionMode } from '../companion-motion'
import type PhaserNs from 'phaser'
import { townTime, weatherForDate } from '../world-life'
import { TownSoundscape } from '../soundscape'
import { paletteForTime, seasonForMonth } from '../atmosphere'
import type { Point } from '../collision'
import type { TownModel, TownResident } from '../town.types'
import type { TownNpcView, InitiativeBudget } from '../town-npc.types'
import type { TownEventView } from '../town-events'
import { gardenDistrict } from '../town-spaces'
import { createPresenceReporter } from '../presence'
import type { TownSceneInstance } from './scene-core'
import type { TownHandlers } from './shared'
import { worldWidth, plotX, computeServerOffset, PLOT_WIDTH, BASELINE, ACADEMY_X, SCHOOL_WIDTH, YARD_X } from './shared'

/** One live owner for state shared by the public API, scene methods and room transitions.
 * Asynchronous model/roster/budget updates replace fields here; consumers never capture copies. */
export class TownRuntime {
  game!: PhaserNs.Game
  sceneRef: TownSceneInstance | null = null
  readonly residents: TownResident[]
  readonly width: number
  readonly gymPlotX: number
  readonly cafePlotX: number
  readonly terraceOrigin: Point
  readonly terraceBounds: { x: number; y: number; width: number; height: number }
  readonly garden: ReturnType<typeof gardenDistrict>
  readonly academyDoorX = ACADEMY_X + SCHOOL_WIDTH / 2 + 8
  readonly courtX = YARD_X + 220
  readonly parkX = this.academyDoorX - 60
  readonly groundMinX = YARD_X + 40
  readonly groundMaxX: number
  readonly presenceReporter: ReturnType<typeof createPresenceReporter>
  townNpcRoster: TownNpcView[] = []
  initiativeBudget: InitiativeBudget = { limit: 3, used: 0 }
  observationOn = false
  scenicMode = false
  townEvents: TownEventView[] = []
  companionState: { pet: CompanionPet | null; mode: CompanionMode } = { pet: null, mode: 'home' }
  companionPlace = false
  companionLoaded = false
  letterUnread = 0
  desiredNight: boolean | null = null
  soundEnabled = false
  private publishedNight: boolean | null = null
  private environmentCache: { key: string; time: ReturnType<typeof townTime> } | null = null
  readonly soundscape: TownSoundscape
  desiredRun = false
  serverOffsetMs: number
  celebrationQueue: string[] = []
  celebrationBusy = false
  latestModel: TownModel
  academyEntered = false
  academyBusy = false
  activeRoomKey: string | null = null
  roomBusy = false
  queuedDestination: string | null = null
  requestedConversation: string | null = null
  exteriorReturn: Point | null = null
  transitionGeneration = 0
  roomRequest: AbortController | null = null

  // Bound to the room controller before Phaser starts loading the scene.
  enterAcademy!: () => void
  enterRoom!: (roomId: string) => Promise<void>
  restoreStreet!: (preferred: Point | null) => boolean
  recover!: () => boolean
  exitRoom!: () => void
  exitAcademy!: () => void

  constructor(
    readonly Phaser: typeof PhaserNs,
    readonly container: HTMLElement,
    readonly model: TownModel,
    readonly handlers: TownHandlers,
  ) {
    this.residents = model.residents
    this.width = worldWidth(this.residents.length)
    this.gymPlotX = plotX(this.residents.length)
    this.cafePlotX = plotX(this.residents.length + 1)
    this.terraceOrigin = { x: this.cafePlotX + PLOT_WIDTH / 2, y: BASELINE + 64 }
    this.terraceBounds = { x: this.terraceOrigin.x - 320, y: this.terraceOrigin.y, width: 640, height: 204 }
    this.garden = gardenDistrict(this.width)
    this.groundMaxX = this.width - 80
    this.latestModel = model
    this.serverOffsetMs = computeServerOffset(model.serverTime)
    this.soundscape = new TownSoundscape(() => ({ minutes: this.environmentTime().minutes, weather: weatherForDate(this.latestModel.localDate) }))
    this.presenceReporter = createPresenceReporter(payload => handlers.onPresenceReport?.(payload), 3000)
  }

  environmentTime() {
    const epoch = Date.now() + this.serverOffsetMs
    const zone = this.latestModel.residents.find(r => r.isSelf)?.timezone ?? 'Asia/Shanghai'
    const key = `${Math.floor(epoch / 100)}:${zone}:${this.desiredNight}`
    if (this.environmentCache?.key !== key) this.environmentCache = { key, time: townTime(epoch, zone, this.desiredNight) }
    return this.environmentCache.time
  }

  publishTime() {
    const time = this.environmentTime()
    const night = paletteForTime(time.minutes, seasonForMonth(time.month)).lightIntensity >= .7
    if (this.publishedNight !== night) { this.publishedNight = night; this.handlers.onTimeChange?.(night) }
  }

  inTerrace(x: number, y: number, margin = 0) {
    const bounds = this.terraceBounds
    return x >= bounds.x - margin && x <= bounds.x + bounds.width + margin && y >= bounds.y - margin && y <= bounds.y + bounds.height + margin
  }

  isWorldPointer(pointer: PhaserNs.Input.Pointer) { return pointer?.event?.target === this.game.canvas }

  runCelebrationQueue() {
    if (this.celebrationBusy || this.celebrationQueue.length === 0) return
    const publicId = this.celebrationQueue.shift() as string
    this.celebrationBusy = true
    this.sceneRef?.celebrateResident(publicId)
    setTimeout(() => { this.celebrationBusy = false; this.runCelebrationQueue() }, 1500)
  }
}
