/**
 * 成长小镇氛围层：连续昼夜色温、路灯/窗户光晕、雨雪天气、季节滤镜、镜头缓动。
 * 与 town.engine.ts 一样按需动态 import phaser，本文件只静态引入类型（`import type`），
 * 不产生运行时依赖，可以脱离 Phaser 单测所有纯函数。
 *
 * ## 接线方式（TownScene 三个时机）
 * ```ts
 * // create()
 * this.atmosphere = new TownAtmosphere({ worldWidth: width, worldHeight: WORLD_HEIGHT, groundY: BASELINE })
 * this.atmosphere.attach(this)                     // 建立遮罩/粒子/光晕对象
 * for (const lamp of streetLamps) this.atmosphere.registerLight({ id: lamp.id, x: lamp.x, y: lamp.y, kind: 'lamp' })
 * for (const win of buildingWindows) this.atmosphere.registerLight({ id: win.id, x: win.x, y: win.y, kind: 'window' })
 *
 * // update(_time, delta)
 * this.atmosphere.update(delta)
 *
 * // destroy()（Scene 关闭或 game.destroy 前）
 * this.atmosphere.destroy()
 * ```
 *
 * 天气/季节由编排者按需调用 `setWeather('rain' | 'snow' | 'clear')`、
 * `setSeasonOverride(season | null)`（null 表示按当前月份自动判断）。
 * 镜头跟随/推近拉远与「用户拖拽时临时接管」的接线示例：
 * ```ts
 * this.atmosphere.followTarget(this.cameras.main, () => ({ x: self.sprite.x, y: self.sprite.y }))
 * // 拖拽开始/结束（setupInput 的 pointerdown / pointerup）：
 * this.atmosphere.beginManualControl()   // 拖拽期间跟随暂停，相机完全交给用户
 * this.atmosphere.endManualControl()     // 拖拽结束恢复跟随
 * // 进门/对话：
 * this.atmosphere.pushIn(this.cameras.main, doorX, doorY, 1.6, 500)
 * this.atmosphere.pullBack(this.cameras.main, 500)   // 对话结束按栈弹回上一次的位置与缩放
 * ```
 * 注意：`followTarget` 每帧会写 `camera.scrollX/scrollY`，与引擎里已有的 `camera.pan(...)`
 * 一次性平移属于两种不同的相机语言，同一相机同一时刻只应使用其中一种，避免互相打架。
 *
 * 页面不可见（`document.visibilitychange`）时调用 `setVisible(false)` 暂停粒子与光晕过渡；
 * `prefers-reduced-motion` 时用 `setReducedMotion(true)` 或直接把 `particleQuality` 设为 'off'，
 * 雨雪粒子完全停止，只保留湿地/积雪的静态色块提示，不做渐变动画。
 */
import type PhaserNs from 'phaser'

// ---------- 纯类型 ----------

export type Season = 'spring' | 'summer' | 'autumn' | 'winter'
export type WeatherKind = 'clear' | 'rain' | 'snow'
export type ParticleQuality = 'full' | 'reduced' | 'off'
export type LightKind = 'lamp' | 'window'

/** 引擎按坐标登记的光源；window 默认比 lamp 暗一些、半径小一些。 */
export type LightSource = {
  id: string
  x: number
  y: number
  kind: LightKind
  /** 光晕直径 px，默认 lamp=200，window=140。 */
  radius?: number
  /** 光晕颜色，默认暖黄（lamp 0xffd8a0，window 0xfff2c8）。 */
  color?: number
}

/** paletteForTime() 的返回值：night 遮罩颜色/透明度 + 灯光该有多亮。 */
export type TimePalette = {
  /** 0xRRGGBB，遮罩颜色（深夜偏蓝、黄昏偏橙、清晨偏冷白）。 */
  overlayColor: number
  /** 遮罩透明度 0..1，正午为 0（完全不遮）。 */
  overlayAlpha: number
  /** 灯光该有的亮度 0..1，白天为 0（灯全灭）。 */
  lightIntensity: number
  /** 与 overlayColor 相同，供需要单独调背景天空色的调用方使用。 */
  skyTint: number
}

export type SeasonTint = { color: number; alpha: number }

export type AtmosphereOptions = {
  worldWidth: number
  worldHeight: number
  /** 地面基线 y（引擎里的 BASELINE），用于放置湿地/积雪色带。 */
  groundY: number
  /** 注入时钟，默认 Date.now；测试或回放时可传入固定值。 */
  now?: () => number
  /** The player’s timezone clock; separate from device-local Date access. */
  townTime?: () => { minutes: number; month: number }
  /** 固定季节；不传则按 now() 对应的月份自动推算。 */
  season?: Season
  reducedMotion?: boolean
  particleQuality?: ParticleQuality
}

// ---------- 纯函数：连续昼夜 ----------

type Keyframe = { t: number; color: readonly [number, number, number]; alpha: number; intensity: number }

/** 一天 24h 的色温关键帧（分钟制，0=零点）。深夜偏蓝、黄昏偏橙、清晨偏冷白，白天完全不遮。 */
const DAY_KEYFRAMES: readonly Keyframe[] = [
  { t: 0, color: [11, 16, 48], alpha: 0.60, intensity: 1.00 }, // 深夜
  { t: 300, color: [11, 16, 48], alpha: 0.55, intensity: 1.00 }, // 黎明前最暗
  { t: 360, color: [143, 163, 200], alpha: 0.32, intensity: 0.55 }, // 拂晓：冷白偏蓝
  { t: 420, color: [191, 208, 255], alpha: 0.10, intensity: 0.15 }, // 清晨
  { t: 480, color: [255, 255, 255], alpha: 0.00, intensity: 0.00 }, // 白天开始
  { t: 1020, color: [255, 255, 255], alpha: 0.00, intensity: 0.00 }, // 午后
  { t: 1080, color: [255, 157, 92], alpha: 0.18, intensity: 0.40 }, // 黄昏起
  { t: 1140, color: [255, 122, 60], alpha: 0.36, intensity: 0.70 }, // 日落
  { t: 1200, color: [74, 58, 106], alpha: 0.50, intensity: 0.90 }, // 暮色转夜
  { t: 1260, color: [11, 16, 48], alpha: 0.60, intensity: 1.00 }, // 入夜
] as const

function lerp(a: number, b: number, f: number): number {
  return a + (b - a) * f
}

function interpolateKeyframes(minutes: number): { color: [number, number, number]; alpha: number; intensity: number } {
  const m = ((minutes % 1440) + 1440) % 1440
  for (let i = 0; i < DAY_KEYFRAMES.length - 1; i += 1) {
    const a = DAY_KEYFRAMES[i]
    const b = DAY_KEYFRAMES[i + 1]
    if (m >= a.t && m <= b.t) {
      const f = b.t === a.t ? 0 : (m - a.t) / (b.t - a.t)
      return {
        color: [lerp(a.color[0], b.color[0], f), lerp(a.color[1], b.color[1], f), lerp(a.color[2], b.color[2], f)],
        alpha: lerp(a.alpha, b.alpha, f),
        intensity: lerp(a.intensity, b.intensity, f),
      }
    }
  }
  // 跨零点：从最后一个关键帧(1260)回绕到第一个关键帧(0 -> 1440)
  const last = DAY_KEYFRAMES[DAY_KEYFRAMES.length - 1]
  const first = DAY_KEYFRAMES[0]
  const span = first.t + 1440 - last.t
  const f = span === 0 ? 0 : (m - last.t) / span
  return {
    color: [lerp(last.color[0], first.color[0], f), lerp(last.color[1], first.color[1], f), lerp(last.color[2], first.color[2], f)],
    alpha: lerp(last.alpha, first.alpha, f),
    intensity: lerp(last.intensity, first.intensity, f),
  }
}

/** 夏天白昼更长、冬天更短：把「与正午的距离」按季节系数缩放，早晚过渡的时刻随之推迟/提前。 */
function seasonDayFactor(season: Season | undefined): number {
  if (season === 'summer') return 1.25
  if (season === 'winter') return 0.80
  return 1
}

function seasonAdjustedMinutes(minutes: number, season: Season | undefined): number {
  const factor = seasonDayFactor(season)
  if (factor === 1) return minutes
  return 720 + (minutes - 720) / factor
}

/** 按一天中的分钟数（0-1439）算出连续色温/亮度，不做白天黑夜二选一。season 可选，影响昼夜长短。 */
export function paletteForTime(minutesOfDay: number, season?: Season): TimePalette {
  const { color, alpha, intensity } = interpolateKeyframes(seasonAdjustedMinutes(minutesOfDay, season))
  const r = Math.round(Math.max(0, Math.min(255, color[0])))
  const g = Math.round(Math.max(0, Math.min(255, color[1])))
  const b = Math.round(Math.max(0, Math.min(255, color[2])))
  const packed = (r << 16) | (g << 8) | b
  return {
    overlayColor: packed,
    overlayAlpha: Math.round(alpha * 1000) / 1000,
    lightIntensity: Math.round(intensity * 1000) / 1000,
    skyTint: packed,
  }
}

/** 本地时间 -> 一天中的分钟数（0-1439，含小数）。 */
export function minutesOfDay(date: Date): number {
  return date.getHours() * 60 + date.getMinutes() + date.getSeconds() / 60
}

// ---------- 纯函数：季节色调 ----------

/** 月份(1-12) -> 季节，按北半球/国内习惯划分。 */
export function seasonForMonth(month: number): Season {
  const m = ((Math.round(month) - 1) % 12 + 12) % 12 + 1
  if (m >= 3 && m <= 5) return 'spring'
  if (m >= 6 && m <= 8) return 'summer'
  if (m >= 9 && m <= 11) return 'autumn'
  return 'winter'
}

/** 极淡的整体滤镜：春绿、夏亮（暖黄偏亮）、秋黄、冬冷（蓝灰）。alpha 均 <= 0.08，只做氛围提示。 */
export function seasonTint(season: Season): SeasonTint {
  switch (season) {
    case 'spring': return { color: 0x8fd18a, alpha: 0.05 }
    case 'summer': return { color: 0xfff2b0, alpha: 0.04 }
    case 'autumn': return { color: 0xd98a3d, alpha: 0.07 }
    case 'winter': return { color: 0x9fb3c8, alpha: 0.06 }
  }
}

// ---------- 纯函数：天气与性能预算 ----------

const RAIN_CAP: Record<'full' | 'reduced', number> = { full: 220, reduced: 60 }
const SNOW_CAP: Record<'full' | 'reduced', number> = { full: 140, reduced: 40 }

/** 某种天气在给定画质档位下允许同时存活的粒子数上限；'clear' 或 'off' 恒为 0。 */
export function particleBudget(kind: WeatherKind, quality: ParticleQuality): number {
  if (kind === 'clear' || quality === 'off') return 0
  return kind === 'rain' ? RAIN_CAP[quality] : SNOW_CAP[quality]
}

/** 指数平滑：每帧把 current 拉向 target，deltaMs 越大越接近 target，永不越过。smoothingMs<=0 时直接到位。 */
export function approach(current: number, target: number, deltaMs: number, smoothingMs: number): number {
  if (smoothingMs <= 0 || deltaMs <= 0) return smoothingMs <= 0 ? target : current
  const f = 1 - Math.exp(-deltaMs / smoothingMs)
  return current + (target - current) * f
}

/** 灯光该有的目标透明度：lamp 用色温强度原值，window 稍暗一些（像是窗帘挡了一部分光）。 */
export function lightTargetAlpha(kind: LightKind, palette: Pick<TimePalette, 'lightIntensity'>): number {
  return kind === 'lamp' ? palette.lightIntensity : palette.lightIntensity * 0.82
}

/** 浏览器是否要求减少动效；非浏览器环境（测试/SSR）一律返回 false。 */
export function reducedMotionPreferred(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

// ---------- Phaser 交互层 ----------

type Scene = PhaserNs.Scene
type Camera = PhaserNs.Cameras.Scene2D.Camera
type RectangleGO = ReturnType<Scene['add']['rectangle']>
type ImageGO = ReturnType<Scene['add']['image']>
type ParticleEmitter = ReturnType<Scene['add']['particles']>

const GLOW_TEXTURE = 'atmo-glow'
const RAIN_TEXTURE = 'atmo-rain'
const SNOW_TEXTURE = 'atmo-snow'
const DEFAULT_RADIUS: Record<LightKind, number> = { lamp: 200, window: 140 }
const DEFAULT_LIGHT_COLOR: Record<LightKind, number> = { lamp: 0xffd8a0, window: 0xfff2c8 }
const WEATHER_TRANSITION_MS = 900
const CAMERA_FOLLOW_SMOOTHING_MS = 220

/** 一盏已登记灯光的运行时状态：目标由 paletteForTime 决定，实际 alpha 用 approach() 缓动过去。 */
type LiveLight = { source: LightSource; sprite: ImageGO; alpha: number }

/**
 * 氛围层运行时对象。纯逻辑（调色板/季节/粒子预算/缓动）全部来自上面的纯函数，
 * 这里只负责把结果写进 Phaser 对象；与 Phaser 打交道的代码集中在 attach/update/destroy
 * 与 followTarget/pushIn/pullBack 几个方法里。
 */
export class TownAtmosphere {
  private readonly opts: AtmosphereOptions
  private readonly nowFn: () => number
  private reducedMotion: boolean
  private particleQuality: ParticleQuality
  private seasonOverride: Season | null
  private weather: WeatherKind = 'clear'
  private visible = true
  private manual = false

  private scene: Scene | null = null
  private nightOverlay: RectangleGO | null = null
  private seasonOverlay: RectangleGO | null = null
  private wetBand: RectangleGO | null = null
  private snowBand: RectangleGO | null = null
  private rainEmitter: ParticleEmitter | null = null
  private snowEmitter: ParticleEmitter | null = null
  private lights = new Map<string, LiveLight>()
  private wetAlpha = 0
  private snowAlpha = 0

  private followCam: Camera | null = null
  private followTargetFn: (() => { x: number; y: number }) | null = null
  private followSmoothingMs = CAMERA_FOLLOW_SMOOTHING_MS
  private cameraStack: Array<{ x: number; y: number; zoom: number }> = []

  constructor(options: AtmosphereOptions) {
    this.opts = options
    this.nowFn = options.now ?? (() => Date.now())
    this.reducedMotion = options.reducedMotion ?? reducedMotionPreferred()
    this.particleQuality = options.particleQuality ?? 'full'
    this.seasonOverride = options.season ?? null
  }

  private effectiveQuality(): ParticleQuality {
    return this.reducedMotion ? 'off' : this.particleQuality
  }

  private currentSeason(): Season {
    return this.seasonOverride ?? seasonForMonth(this.opts.townTime?.().month ?? new Date(this.nowFn()).getMonth() + 1)
  }

  private ensureTextures(scene: Scene) {
    if (!scene.textures.exists(GLOW_TEXTURE)) {
      const canvas = scene.textures.createCanvas(GLOW_TEXTURE, 256, 256)
      if (canvas) {
        const ctx = canvas.getContext()
        const gradient = ctx.createRadialGradient(128, 128, 0, 128, 128, 128)
        gradient.addColorStop(0, 'rgba(255,255,255,0.9)')
        gradient.addColorStop(0.45, 'rgba(255,255,255,0.35)')
        gradient.addColorStop(1, 'rgba(255,255,255,0)')
        ctx.fillStyle = gradient
        ctx.fillRect(0, 0, 256, 256)
        canvas.refresh()
      }
    }
    if (!scene.textures.exists(RAIN_TEXTURE)) {
      const canvas = scene.textures.createCanvas(RAIN_TEXTURE, 4, 24)
      if (canvas) {
        const ctx = canvas.getContext()
        ctx.fillStyle = 'rgba(210,230,255,0.75)'
        ctx.fillRect(0, 0, 4, 24)
        canvas.refresh()
      }
    }
    if (!scene.textures.exists(SNOW_TEXTURE)) {
      const canvas = scene.textures.createCanvas(SNOW_TEXTURE, 6, 6)
      if (canvas) {
        const ctx = canvas.getContext()
        ctx.fillStyle = 'rgba(255,255,255,0.9)'
        ctx.beginPath()
        ctx.arc(3, 3, 3, 0, Math.PI * 2)
        ctx.fill()
        canvas.refresh()
      }
    }
  }

  /** create() 里调用一次：建立昼夜/季节遮罩、湿地雪地色带、雨雪粒子（先建好但不发射）。 */
  attach(scene: Scene): void {
    this.scene = scene
    this.ensureTextures(scene)
    const { worldWidth, worldHeight, groundY } = this.opts

    this.nightOverlay = scene.add.rectangle(0, 0, worldWidth, worldHeight, 0x000000, 0).setOrigin(0).setDepth(5000)
    this.seasonOverlay = scene.add.rectangle(0, 0, worldWidth, worldHeight, 0x000000, 0).setOrigin(0).setDepth(4990)

    const bandHeight = 180
    const bandY = groundY - 30
    this.wetBand = scene.add.rectangle(0, bandY, worldWidth, bandHeight, 0x16223a, 0).setOrigin(0, 0).setDepth(0.5)
    this.snowBand = scene.add.rectangle(0, bandY, worldWidth, bandHeight, 0xf3f7ff, 0).setOrigin(0, 0).setDepth(0.5)

    this.rainEmitter = scene.add.particles(0, 0, RAIN_TEXTURE, {
      x: { min: 0, max: worldWidth },
      y: -20,
      lifespan: 900,
      speedY: { min: 420, max: 560 },
      speedX: { min: -15, max: 15 },
      alpha: { start: 0.7, end: 0.25 },
      frequency: 12,
      maxAliveParticles: particleBudget('rain', this.effectiveQuality()),
      blendMode: 'ADD',
    }).setDepth(4600)
    this.rainEmitter.stop()

    this.snowEmitter = scene.add.particles(0, 0, SNOW_TEXTURE, {
      x: { min: 0, max: worldWidth },
      y: -20,
      lifespan: 4200,
      speedY: { min: 40, max: 90 },
      speedX: { min: -25, max: 25 },
      alpha: { start: 0.9, end: 0.5 },
      scale: { start: 0.6, end: 1 },
      frequency: 40,
      maxAliveParticles: particleBudget('snow', this.effectiveQuality()),
    }).setDepth(4600)
    this.snowEmitter.stop()

    this.applySeasonOverlay()
  }

  private applySeasonOverlay() {
    if (!this.seasonOverlay) return
    const tint = seasonTint(this.currentSeason())
    this.seasonOverlay.setFillStyle(tint.color, tint.alpha)
  }

  /** 登记一处光源坐标（路灯/窗户），由引擎在建好场景对象后调用；重复 id 会覆盖旧坐标。 */
  registerLight(light: LightSource): void {
    if (!this.scene) return
    const previous = this.lights.get(light.id)
    if (previous) previous.sprite.destroy()
    const radius = light.radius ?? DEFAULT_RADIUS[light.kind]
    const color = light.color ?? DEFAULT_LIGHT_COLOR[light.kind]
    const sprite = this.scene.add.image(light.x, light.y, GLOW_TEXTURE)
      .setDisplaySize(radius, radius)
      .setBlendMode('ADD')
      .setTint(color)
      .setDepth(5001)
      .setAlpha(0)
    this.lights.set(light.id, { source: light, sprite, alpha: 0 })
  }

  /** 清空所有已登记光源（例如切换城镇布局重新登记前）。 */
  clearLights(): void {
    for (const light of this.lights.values()) light.sprite.destroy()
    this.lights.clear()
  }

  /** 切换天气；粒子发射与湿地/积雪色带的渐入渐出在 update() 里用 approach() 完成过渡。 */
  setWeather(kind: WeatherKind): void {
    this.weather = kind
    if (kind === 'rain') this.rainEmitter?.start()
    else this.rainEmitter?.stop()
    if (kind === 'snow') this.snowEmitter?.start()
    else this.snowEmitter?.stop()
  }

  /** 只读地看一眼当前天气——M7-9 路上插曲要靠它判断"雨天该不该往屋檐下躲"，本身不改变任何状态。 */
  getWeather(): WeatherKind {
    return this.weather
  }

  /** 固定季节；传 null 恢复「按当前月份自动判断」。 */
  setSeasonOverride(season: Season | null): void {
    this.seasonOverride = season
    this.applySeasonOverlay()
  }

  setReducedMotion(reduced: boolean): void {
    this.reducedMotion = reduced
    this.syncParticleBudgets()
  }

  setParticleQuality(quality: ParticleQuality): void {
    this.particleQuality = quality
    this.syncParticleBudgets()
  }

  private syncParticleBudgets() {
    const quality = this.effectiveQuality()
    const rainBudget = particleBudget('rain', quality)
    const snowBudget = particleBudget('snow', quality)
    if (this.rainEmitter) this.rainEmitter.maxAliveParticles = rainBudget
    if (this.snowEmitter) this.snowEmitter.maxAliveParticles = snowBudget
    if (quality === 'off') {
      this.rainEmitter?.stop()
      this.snowEmitter?.stop()
    } else if (this.weather === 'rain') this.rainEmitter?.start()
    else if (this.weather === 'snow') this.snowEmitter?.start()
  }

  /** 页面不可见时调用 false 暂停粒子与缓动；恢复可见时调用 true。 */
  setVisible(visible: boolean): void {
    this.visible = visible
    if (visible) {
      if (this.weather === 'rain') this.rainEmitter?.resume()
      if (this.weather === 'snow') this.snowEmitter?.resume()
    } else {
      this.rainEmitter?.pause()
      this.snowEmitter?.pause()
    }
  }

  /** 相机每帧缓动跟随 target()；在 beginManualControl() 期间暂停，交还给用户拖拽。 */
  followTarget(camera: Camera, target: () => { x: number; y: number }, smoothingMs = CAMERA_FOLLOW_SMOOTHING_MS): void {
    this.followCam = camera
    this.followTargetFn = target
    this.followSmoothingMs = smoothingMs
  }

  clearFollow(): void {
    this.followCam = null
    this.followTargetFn = null
  }

  /** 进门/对话时推近：记录当前中心与缩放（可嵌套），再 pan + zoomTo 过去。force:true 是必须的——
   * Phaser 的 pan/zoomTo 效果若上一次还没播完，不加 force 会被静默忽略（见 Zoom.start 源码），
   * 连续快速触发推近/拉远时后一次调用必须能覆盖前一次。 */
  pushIn(camera: Camera, x: number, y: number, zoom: number, durationMs = 500, ease = 'Sine.easeInOut'): void {
    this.cameraStack.push({ x: camera.midPoint.x, y: camera.midPoint.y, zoom: camera.zoom })
    camera.pan(x, y, durationMs, ease, true)
    camera.zoomTo(zoom, durationMs, ease, true)
  }

  /** 对话结束拉远：弹出并还原最近一次 pushIn 之前的中心与缩放；栈空则什么也不做。 */
  pullBack(camera: Camera, durationMs = 500, ease = 'Sine.easeInOut'): void {
    const previous = this.cameraStack.pop()
    if (!previous) return
    camera.pan(previous.x, previous.y, durationMs, ease, true)
    camera.zoomTo(previous.zoom, durationMs, ease, true)
  }

  /** 用户开始拖拽时调用：followTarget 暂停写 scroll，相机完全交给用户。 */
  beginManualControl(): void {
    this.manual = true
  }

  /** 拖拽结束时调用：跟随恢复。 */
  endManualControl(): void {
    this.manual = false
  }

  /** update(_time, delta) 里每帧调用：推进色温/光晕/天气过渡/相机跟随。 */
  update(deltaMs: number): void {
    if (!this.scene || !this.visible) return
    const now = this.nowFn()
    const palette = paletteForTime(this.opts.townTime?.().minutes ?? minutesOfDay(new Date(now)), this.currentSeason())

    this.nightOverlay?.setFillStyle(palette.overlayColor, palette.overlayAlpha)
    for (const light of this.lights.values()) {
      const target = lightTargetAlpha(light.source.kind, palette)
      light.alpha = approach(light.alpha, target, deltaMs, 900)
      light.sprite.setAlpha(light.alpha)
    }

    const quality = this.effectiveQuality()
    const wetTarget = this.weather === 'rain' ? 0.38 : 0
    const snowTarget = this.weather === 'snow' ? 0.6 : 0
    if (quality === 'off') {
      // 降级为静态：直接到位，不做逐帧渐变。
      this.wetAlpha = wetTarget
      this.snowAlpha = snowTarget
    } else {
      this.wetAlpha = approach(this.wetAlpha, wetTarget, deltaMs, WEATHER_TRANSITION_MS)
      this.snowAlpha = approach(this.snowAlpha, snowTarget, deltaMs, WEATHER_TRANSITION_MS)
    }
    this.wetBand?.setFillStyle(0x16223a, this.wetAlpha)
    this.snowBand?.setFillStyle(0xf3f7ff, this.snowAlpha)

    if (this.followCam && this.followTargetFn && !this.manual) {
      const target = this.followTargetFn()
      const desiredX = target.x - this.followCam.width / 2 / this.followCam.zoom
      const desiredY = target.y - this.followCam.height / 2 / this.followCam.zoom
      this.followCam.scrollX = approach(this.followCam.scrollX, desiredX, deltaMs, this.followSmoothingMs)
      this.followCam.scrollY = approach(this.followCam.scrollY, desiredY, deltaMs, this.followSmoothingMs)
    }
  }

  /** 释放所有 Phaser 对象；场景销毁或 game.destroy() 前调用一次。 */
  destroy(): void {
    this.nightOverlay?.destroy()
    this.seasonOverlay?.destroy()
    this.wetBand?.destroy()
    this.snowBand?.destroy()
    this.rainEmitter?.destroy()
    this.snowEmitter?.destroy()
    this.clearLights()
    this.nightOverlay = null
    this.seasonOverlay = null
    this.wetBand = null
    this.snowBand = null
    this.rainEmitter = null
    this.snowEmitter = null
    this.scene = null
    this.followCam = null
    this.followTargetFn = null
    this.cameraStack = []
  }
}
