/** Shared geometry, public contracts and pure helpers for the town renderer. */
import type PhaserNs from 'phaser'
import { activityFor, hashString } from '../building-kit'
import type { TownVenue, VenueAction } from '../building-kit'
import { dominantDirection } from '../walkers'
import type { Direction4 } from '../walkers'
import type { ConversationNotice } from '../npc-conversation'
import { pointAlongRoute } from '../town-spaces'
import type { TownEventView } from '../town-events'
import type { Point } from '../collision'
import type { ResidentActivity, TownModel, TownResident } from '../town.types'
import type { InteriorPet, InteriorPetSpecies } from '../interior.scene'
import type { PresencePayload } from '../presence'
import type { TownNpcView, InitiativeBudget, NpcActivity, NpcDayPlan, NpcPlace } from '../town-npc.types'
import { placeFor, verticalJitter, depthForY } from '../npc-placement'
import type { TownLayout } from '../npc-placement'
import { positionAt } from '../day-plan'
import { api } from '../../../shared/api/client'
import type { Achievement } from '../../achievements/achievement.types'
import type { PartnerProfile } from '../../partners/partner.types'

export type TownSelection = string | 'npc:assistant' | 'npc:postman' | 'academy' | 'home' | null
export type TownTravel = { place: string; label: string; phase: 'walking' | 'arrived' | 'blocked' }
export type TownNearby = { id: string; label: string; action: string }
export type TownHandlers = {
  /** Actual rendered light state, including manual previews and the player timezone. */
  onTimeChange?: (night: boolean) => void
  onConversationChange?: (notice: ConversationNotice | null) => void
  onObservationChange?: (enabled: boolean) => void
  onTravelChange?: (travel: TownTravel | null) => void
  onNearbyChange?: (nearby: TownNearby | null) => void
  onSelect?: (selection: TownSelection) => void
  onRoomChange?: (room: string | null) => void
  /** Fired whenever the camera finishes transitioning into/out of the academy interior. */
  onAcademyChange?: (inside: boolean) => void
  /** Called when self avatar stops or at throttled interval; engine reports presence to backend. */
  onPresenceReport?: (payload: PresencePayload) => void
  /** Called when the player avatar moves (for onboarding tracking). */
  onPlayerMove?: (x: number, y: number) => void
  /** Called to report distance to guide NPC (for onboarding tracking). */
  onDistanceToGuide?: (distance: number) => void
  /** M3-3/M3-4: 玩家点击房间里的可交互家具（书桌/成就墙/宠物窝）。actionId 是
   * world-actions.ts 的注册表 id——engine 自己不认识这些动作具体做什么，只管转发；调用方
   * （TownView.vue / ImmersiveTown.vue）负责接上 runWorldAction(actionId, ctx)。 */
  onInteriorInteract?: (actionId: string, id: string) => void
  /** 护栏 A：某个 NPC 刚花掉了一格全镇每日主动搭话额度。引擎先在本地扣一格保证这一帧的判断
   * 立刻生效，调用方负责把这一次消耗报给后端（`POST /town/initiative/consume`），再用返回的
   * 权威值调 `setInitiativeBudget` 校正——否则刷新页面额度就归零，等于没有护栏。 */
  onInitiativeSpent?: (npcCode: string) => void
}
export type TownGame = {
  /** Hands the engine the town's NPC roster (GET /town/npcs). Safe to call repeatedly. */
  applyNpcs: (npcs: TownNpcView[], budget: InitiativeBudget) => void
  applyEvents?: (events: TownEventView[]) => void
  /** 观察模式: detaches the camera from the player and glides it between points of interest. */
  setObservation: (on: boolean) => void
  setScenic?: (on: boolean) => void
  /** 用服务端返回的权威额度覆盖本地的乐观值。刻意不复用 `applyNpcs`——那会整批重建走位。 */
  setInitiativeBudget: (budget: InitiativeBudget) => void
  setLetterUnread?: (count: number) => void
  setNight(night: boolean): void
  /** Clear the visual day/night preview and resume the player’s timezone clock. */
  setAutomaticTime(): void
  /** Opt-in environmental sound, including the street record player. Defaults to false. */
  setSoundEnabled(enabled: boolean): void
  /** Turns the self avatar's run mode on/off; holding Shift runs regardless of this toggle. */
  setRun(running: boolean): void
  focus(publicId: string): void
  travelTo?(place: string): void
  cancelTravel?(): void
  interactNearby?(): void
  recover?(): boolean
  beginConversation?(npcCode: string): boolean
  endConversation?(npcCode?: string): void
  interruptConversation?(npcCode: string, reason: string): void
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

export const ASSETS = '/assets/town'
export const TILE = 32
export const BASELINE_ROW = 28
export const BASELINE = BASELINE_ROW * TILE
export const ROWS = 37
export const WORLD_HEIGHT = ROWS * TILE
export const YARD_X = 96
export const ACADEMY_X = YARD_X + 560
export const SCHOOL_WIDTH = 768
export const PLOT_START = ACADEMY_X + SCHOOL_WIDTH + 192
export const PLOT_WIDTH = 224
export const PLOT_PITCH = 336
export const STREET_Y = BASELINE + 40
export const FONT = '"PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif'
/** How long a click/keypress overrides the self avatar's schedule-driven placement (task 6). */
export const SELF_OVERRIDE_MS = 20_000
/** How long two passing walkers pause to greet each other. */
export const GREET_PAUSE_MS = 1000
/** Fade duration for the town <-> academy interior transition. */
export const SCENE_FADE_MS = 250

export type Direction = Direction4
export const DIRECTION_INDEX: Record<Direction, number> = { right: 0, up: 1, left: 2, down: 3 }
/** How far north of the baseline an ordinary shop's sidewalk apron reaches (task: 八向自由移动). */
export const WALK_APRON = 64
/** Southern edge of the walkable street/sidewalk/park band, short of the world's own bottom edge. */
export const WALK_BOTTOM = WORLD_HEIGHT - 24
/** The academy/yard courtyard is a proper plaza: it lets the self avatar wander further north
 * than an ordinary shop front, covering the basketball court and the guide NPC's patrol strip. */
export const PLAZA_TOP = BASELINE - 380
/** No A* pathing: a straight-line click-to-walk can dead-end against a wall. Give up and settle
 * into 'act' after this long stuck rather than animating in place forever. */
export const SELF_STUCK_MS = 350

/** 观察模式跑完一圈的总时长——plan 的验收标准是"一段 30 秒长镜头"。 */
export const OBSERVATION_LOOP_MS = 30_000

/** 气泡停留时长。40 字的中文按正常语速念完约 5 秒，留一点余量。 */
export const BUBBLE_HOLD_MS = 6_500

/** 观察模式的镜头高度。比默认视角近一档，人物、气泡和店面细节才看得清。 */
export const OBSERVATION_ZOOM = 1.3

/** Frames inside emotes.png (10 x 10 grid of 32px bubbles). */
export const EMOTES: Record<ResidentActivity | 'mail' | 'question' | 'heart', [number, number]> = {
  done: [64, 65],
  working: [92, 93],
  planned: [40, 41],
  resting: [56, 57],
  mail: [68, 69],
  question: [52, 53],
  heart: [54, 55],
}

export type Walker = {
  catchupPath?: Point[]
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
  /** Society NPCs only (M7-6): cached dayPlan (either the backend's own, or dayPlanFallback of
   * its legacy `schedule`) so `resolveNpcFrame` doesn't rebuild the fallback every frame. */
  dayPlan: NpcDayPlan | null
  /** Society NPCs only (M7-9): next time this walker is allowed to roll the roadside-episode
   * dice again — throttled so "偶尔" doesn't collapse into "every frame near anyone". */
  episodeCheckAt: number
  /** Society NPCs only (M7-9): while now < this, nudge the walker's y toward the row of shops
   * (雨天靠边躲雨) — purely cosmetic, never changes x or which errand/leg is active. */
  shelterUntil: number
}

/** Geometry of one labour spritesheet, as written by scripts/build-town-assets.py. */
export type LabourAnim = {
  file: string
  frameWidth: number
  frameHeight: number
  frames: number
  framesPerDirection: number
  /** Half-open range of the block that faces the camera — the only one the town plays. */
  downStart: number
  downEnd: number
}

export const LABOUR_ACTIVITIES = new Set<NpcActivity>(['watering', 'chopping', 'fishing', 'harvesting', 'digging'])

export type Vehicle = { sprite: PhaserNs.GameObjects.Image; speed: number }

export type SelfKeys = {
  cursors: PhaserNs.Types.Input.Keyboard.CursorKeys | undefined
  keyA: PhaserNs.Input.Keyboard.Key | undefined
  keyD: PhaserNs.Input.Keyboard.Key | undefined
  keyW: PhaserNs.Input.Keyboard.Key | undefined
  keyS: PhaserNs.Input.Keyboard.Key | undefined
  shift: PhaserNs.Input.Keyboard.Key | undefined
}

/** Phaser's tween easings aren't reachable from a plain number, so the pan interpolation uses its own. */
/**
 * 同一个地点站着好几个人时，横向的偏移量（纵向散开交给 npc-placement.ts 的 verticalJitter——
 * 那是 M7-8 指定要用的纯函数，这里只管 x 轴，两者一起用才是"横纵都散开"而不是"贴在一条竖线
 * 上"）。
 *
 * <p>横向散开只解决"挤在一起"，解决不了"一字排开"——所有人都钉在同一条基线上，看上去就是
 * 一排合唱队。所以纵向也要散：人行道那条带子窄（只有几十像素），广场和公园则一直往北敞到
 * 篮球场，可以拉出真正的纵深。有了纵向差异，深度排序（depth = y）也才有东西可排，人才会
 * 互相遮挡，而不是像贴纸一样并排。
 */
export function venueOffset(npcCode: string, place: NpcPlace): { dx: number; dy: number } {
  const seed = hashString(npcCode)
  const dx = (seed % 11) * 38 - 190
  // 广场与公园是开阔地，往北能站得很深；其余地方只有店门前那条人行道。
  const open = place === 'plaza' || place === 'park'
  const dy = open
    ? -((seed >>> 8) % 9) * 22 + 20
    : -((seed >>> 8) % 5) * 15 + 24
  return { dx, dy }
}

export function easeInOutSine(t: number) {
  return -(Math.cos(Math.PI * Math.min(1, Math.max(0, t))) - 1) / 2
}

// ---------- M7-6/7/8: 18 人名册每帧该画在哪儿 (纯函数, 不碰 Phaser, 直接单测) ----------

/** `resolveNpcFrame` 的结果：这一刻该把 NPC 的 sprite 摆在哪、朝哪、播哪个动作。 */
export type NpcFrame = {
  x: number
  y: number
  depth: number
  activity: NpcActivity
  walking: boolean
  /** WALKING 时朝哪边走；AT（站定）时是 null——朝向由已经在播的待机动画决定，不需要改。 */
  facing: Direction4 | null
  /** WALKING 时是终点 x；AT 时就是自己的 x。只用来给 detectGreetings 判断朝向的符号，不是
   * 真的还有一个"目标点"要走过去——positionAt 的 progress 已经把位置算死了。 */
  targetX: number
}

/**
 * CONTRACT-M7.md §2 落地到画面的那一步：把 `positionAt` 的输出摊成"这一刻画在哪"。
 * AT 用 verticalJitter 纵向散开、venueOffset 横向散开、depth 跟着 y 走（M7-8）；WALKING 在
 * 起点终点之间按 `progress` 连续插值——`progress` 本身就是"当前分钟"的连续函数（不是整点才
 * 跳一下），所以只要每帧都用当前的、带小数的分钟数调用这个函数，画出来的位置就永远和
 * positionAt(dayPlan, 那一刻) 的结果对得上，不会出现"瞬移到目的地站着"。
 *
 * 纯函数：不碰 Phaser、不切动画、不管冻结/插曲——那些是 town.engine.ts 里 updateNpcWalker 的
 * 事，这里只做算术，方便单测钉死。
 */
export function resolveNpcFrame(
  dayPlan: NpcDayPlan,
  minuteOfDay: number,
  npcCode: string,
  layout: TownLayout,
  streetY: number,
  placements: Partial<Record<NpcPlace, Point>> = {},
): NpcFrame {
  const endpoint = (place: NpcPlace) => {
    if (placements[place]) return placements[place]!
    const spread = venueOffset(npcCode, place)
    return { x: placeFor(place, layout) + spread.dx, y: (place === 'park' ? layout.parkY ?? streetY : streetY) + verticalJitter(npcCode, place) }
  }
  const position = positionAt(dayPlan, minuteOfDay)
  if (position.kind === 'AT') {
    const { x, y } = endpoint(position.place)
    return { x, y, depth: depthForY(y), activity: position.activity, walking: false, facing: null, targetX: x }
  }
  const from = endpoint(position.fromPlace)
  const to = endpoint(position.toPlace)
  let route = [from, to]
  if (layout.branchX !== undefined && layout.parkY !== undefined && (position.fromPlace === 'park' || position.toPlace === 'park')) {
    const lane = (hashString(npcCode) % 3 - 1) * 26
    const corner = [{ x: layout.branchX + lane, y: streetY + lane }, { x: layout.branchX + lane, y: layout.parkY + lane }]
    route = position.toPlace === 'park' ? [from, ...corner, to] : [from, ...corner.reverse(), to]
  }
  const { x, y } = pointAlongRoute(route, position.progress)
  const ahead = pointAlongRoute(route, Math.min(1, position.progress + .001))
  return { x, y, depth: depthForY(y), activity: 'walking', walking: true, facing: dominantDirection(ahead.x - x, ahead.y - y, 'down'), targetX: to.x }
}

/** 带小数的"当地时间自 00:00 起的分钟数"——带小数才能让 WALKING 的插值逐帧平滑，而不是整
 * 分钟才挪一下。纯函数（只依赖传入的 epoch ms），方便单测，不用真的等挂钟走。 */
export function minuteOfLocalDay(epochMs: number): number {
  const date = new Date(epochMs)
  return date.getHours() * 60 + date.getMinutes() + date.getSeconds() / 60 + date.getMilliseconds() / 60000
}

/** M7-9 路上插曲的节流与幅度——都是纯表现常量，调大调小不影响任何数据。 */
export const ROADSIDE_EPISODE_CHECK_MS = 4_000
export const ROADSIDE_PASSING_DISTANCE_PX = 70
export const ROADSIDE_SHELTER_MS = 6_000
export const ROADSIDE_SHELTER_OFFSET_PX = 14

// ---------- M3-2: 自家客厅要用到的两份"旁支"数据 (宠物 / 成就墙) ----------

export const INTERIOR_PET_SPECIES = new Set<InteriorPetSpecies>(['CAT', 'DOG', 'HAMSTER', 'SNAKE', 'RABBIT', 'BIRD', 'TURTLE', 'FOX'])

/** `partner.types.ts` 的 `Pet.speciesCode` 是普通 string，不是字面量联合——运行时校验一下，
 * 校验不过就当"没有宠物"处理，不能让后端一个意外取值把"能进屋"这件事炸掉。 */
export function isInteriorPetSpecies(value: string): value is InteriorPetSpecies {
  return INTERIOR_PET_SPECIES.has(value as InteriorPetSpecies)
}

/**
 * 自家客厅要用到的两份"旁支"数据：成就墙点亮数、当前选中的宠物。都是尽力而为——任何一个
 * 请求失败都不能把"走到门口就能进屋"这件事卡住，拿不到就按"没有"处理（成就墙按 0 算，
 * 宠物窝空着）。这两份数据都不在 TownModel/TownResident 上，只能各自问一次接口。
 */
export async function fetchHomeExtras(): Promise<{ homeAchievements: number; pet?: InteriorPet }> {
  const [achievements, profile] = await Promise.all([
    api.get<Achievement[]>('/achievements').catch(() => [] as Achievement[]),
    api.get<PartnerProfile>('/partners/profile').catch(() => null),
  ])
  const homeAchievements = achievements.filter(item => item.earned).length
  const selected = profile?.selectedPet
  const pet: InteriorPet | undefined = selected && isInteriorPetSpecies(selected.speciesCode)
    ? { id: selected.publicId, species: selected.speciesCode, breed: selected.breed }
    : undefined
  return { homeAchievements, pet }
}

export function characterSheet(publicId: string) {
  return 1 + (hashString(`${publicId}:look`) % 20)
}

export function worldWidth(count: number) {
  return Math.max(2600, PLOT_START + (count + 2) * PLOT_PITCH + 320)
}

export function plotX(index: number) {
  return PLOT_START + index * PLOT_PITCH
}

/** serverTime - Date.now() at the moment the model was (re)loaded; calibrates schedule windows to the local clock. */
export function computeServerOffset(serverTime?: string): number {
  if (!serverTime) return 0
  const parsed = new Date(serverTime).getTime()
  return Number.isNaN(parsed) ? 0 : parsed - Date.now()
}

/** Cheap signature for "does this plot need to be redrawn": level, direction, streak and open/closed. */
export function plotSignatureFor(resident: TownResident): string {
  return `${resident.level}|${resident.dominantDimension ?? ''}|${resident.longestStreak}|${activityFor(resident) !== 'resting'}`
}
