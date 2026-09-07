import { canStand } from './collision'
/**
 * 小镇诊断探针（只在 dev / 测试构建里装载）。
 *
 * 存在的理由：小镇整个世界画在一张 canvas 上。DOM 里除了一个空 <div> 什么都没有，
 * 所以"小人有没有走""NPC 站在哪""脚下能不能走""刚才那次交互到底触发没有"——
 * 仅检查 DOM 的测试无法感知这些问题。截图负责画面，探针补充状态。
 * 结果就是画面早就坏了，测试还一片绿。
 *
 * 探针把世界翻译成两种可读形式：
 *   window.__town.snapshot()  → 结构化 JSON，给断言用
 *   window.__town.describe()  → 一段中文，给人和 agent 直接读
 * 外加移动探针（walk / walkTo），让调用方能真的"进去走一圈"。
 *
 * 三条硬约束：
 *   1. 只读优先。驱动方法走的是引擎和 store 已有的公开路径，不新开后门逻辑，
 *      否则测试测的就不是玩家真正走的那条路了。
 *   2. 绝不抛错。任何一处读取失败都降级成 null/空数组并记进 snapshot.probeErrors，
 *      探针自己把应用搞挂就本末倒置了。
 *   3. 不进生产包。install 由 import.meta.env.DEV 守卫，见 town.engine.ts 的调用点。
 */

export type ProbePoint = { x: number; y: number }

export type ProbeActor = {
  id: string
  name: string
  x: number
  y: number
  kind: 'self' | 'resident' | 'npc'
  state: string
  activity: string
  visible: boolean
  screen: ProbePoint | null
  /** 与玩家的直线距离；玩家自己是 0，玩家不存在时是 null。 */
  distance: number | null
}

export type ProbeNearby = {
  kind: 'npc' | 'resident' | 'furniture' | 'building'
  id: string
  label: string
  distance: number
}

export type ProbeUi = {
  route: string
  panelsOpen: string[]
  panelsMinimized: string[]
  dockCollapsed: boolean
  /** 屏幕上此刻可读的文字线索：对话框、反馈条、动作菜单、报错条。 */
  dialogue: string | null
  feedback: string[]
  actionMenu: string[]
  alerts: string[]
}

export type ProbeSnapshot = {
  at: string
  /** 引擎起来了没有。false 时下面的世界字段全是空的，先看 errors。 */
  ready: boolean
  scene: string | null
  player: (ProbePoint & {
    facing: string
    state: string
    running: boolean
    /** 关键健康指标：为 false 说明 setupInput() 没跑完，方向键完全没用。 */
    controllable: boolean
    /** 脚下这个点在碰撞世界里是否可行走。 */
    onWalkable: boolean
  }) | null
  camera: (ProbePoint & { zoom: number; width: number; height: number }) | null
  activeScene: string | null
  renderer: { fps: number; textures: number; missingTextures: string[] } | null
  actors: ProbeActor[]
  nearby: ProbeNearby[]
  world: {
    night: boolean
    runMode: boolean
    walkableRects: number
    obstacleRects: number
    observation: boolean
  }
  ui: ProbeUi
  /** 页面加载以来的未捕获错误 / Promise 拒绝，最近 50 条。 */
  errors: { message: string; stack: string; at: string }[]
  /** 4xx/5xx 或直接失败的请求，最近 50 条。 */
  failedRequests: { url: string; status: number; at: string }[]
  /** 探针自己读取失败的地方，方便区分"世界坏了"和"探针坏了"。 */
  probeErrors: string[]
}

type UiProvider = () => Partial<ProbeUi>

type SceneLike = {
  walkers?: any[]
  selfWalker?: any
  selfKeys?: any
  night?: boolean
  runMode?: boolean
  observationItinerary?: any[]
  collisionWorld?: { walkable?: any[]; obstacles?: any[] }
  streetFurnitureItems?: any[]
  venueFurniture?: any[]
  buildingCenters?: Map<string, ProbePoint>
  cameras?: any
  scene?: { key?: string }
  sys?: any
}

const errors: ProbeSnapshot['errors'] = []
const failedRequests: ProbeSnapshot['failedRequests'] = []
let uiProvider: UiProvider | null = null
let installed = false

/** 视图层（TownView / ImmersiveTown）把自己的 UI 状态挂进来。返回一个注销函数。 */
export function registerTownUi(provider: UiProvider): () => void {
  uiProvider = provider
  return () => { if (uiProvider === provider) uiProvider = null }
}

function scene(): SceneLike | null {
  return (window as unknown as { __townScene?: SceneLike }).__townScene ?? null
}

function push<T>(list: T[], item: T) {
  list.push(item)
  if (list.length > 50) list.shift()
}

function dist(a: ProbePoint, b: ProbePoint) {
  return Math.round(Math.hypot(a.x - b.x, a.y - b.y))
}

function text(selector: string): string[] {
  return Array.from(document.querySelectorAll(selector))
    .map(node => (node.textContent ?? '').replace(/\s+/g, ' ').trim())
    .filter(Boolean)
}

/** 兜底的 UI 读取：视图层没注册 provider 时也能从 DOM 上刮出可读线索。 */
function domUi(): ProbeUi {
  const dialogues = text('.town-panel, [role="dialog"]')
  return {
    route: location.pathname,
    panelsOpen: text('[role="dialog"] .world-panel-title, [role="dialog"] h2'),
    panelsMinimized: text('.immersive-minimized button'),
    dockCollapsed: document.querySelector('.immersive-dock') === null,
    dialogue: dialogues[0] ?? null,
    feedback: text('.world-feedback li, .world-feedback p'),
    actionMenu: text('.world-action-menu button'),
    alerts: text('[role="alert"]'),
  }
}

function collectUi(probeErrors: string[]): ProbeUi {
  const base = domUi()
  if (!uiProvider) return base
  try {
    return { ...base, ...uiProvider() }
  } catch (error) {
    probeErrors.push(`uiProvider: ${(error as Error).message}`)
    return base
  }
}

function walkableAt(world: SceneLike['collisionWorld'], point: ProbePoint): boolean {
  const rects = world?.walkable ?? []
  return canStand(point, { walkable: rects, obstacles: world?.obstacles ?? [] })
}

function actorsOf(s: SceneLike, self: ProbePoint | null, probeErrors: string[]): ProbeActor[] {
  const out: ProbeActor[] = []
  for (const walker of s.walkers ?? []) {
    try {
      const point = { x: Math.round(walker.sprite.x), y: Math.round(walker.sprite.y) }
      const isSelf = Boolean(walker.resident?.isSelf)
      const camera = s.cameras?.main
      const origin = camera?.getWorldPoint(0, 0)
      const screen = origin ? { x: Math.round((point.x - origin.x) * camera.zoom), y: Math.round((point.y - origin.y) * camera.zoom) } : null
      out.push({
        id: String(walker.id ?? '?'),
        name: String(walker.label?.text ?? walker.resident?.displayName ?? walker.id ?? '?'),
        ...point,
        kind: isSelf ? 'self' : walker.resident ? 'resident' : 'npc',
        state: String(walker.state ?? '?'),
        activity: String(walker.npcActivity ?? walker.activity ?? '?'),
        visible: Boolean(walker.sprite.visible && screen && screen.x >= 0 && screen.x <= camera.width && screen.y >= 0 && screen.y <= camera.height),
        screen,
        distance: self ? dist(self, point) : null,
      })
    } catch (error) {
      probeErrors.push(`walker: ${(error as Error).message}`)
    }
  }
  return out.sort((a, b) => (a.distance ?? 1e9) - (b.distance ?? 1e9))
}

/** 玩家身边 260px 内、能对它做点什么的东西。这是"我现在能干嘛"的答案。 */
function nearbyOf(s: SceneLike, self: ProbePoint | null, actors: ProbeActor[]): ProbeNearby[] {
  if (!self) return []
  const out: ProbeNearby[] = []
  for (const actor of actors) {
    if (actor.kind === 'self' || actor.distance === null || actor.distance > 260) continue
    out.push({ kind: actor.kind === 'npc' ? 'npc' : 'resident', id: actor.id, label: actor.name, distance: actor.distance })
  }
  for (const item of [...(s.streetFurnitureItems ?? []), ...(s.venueFurniture ?? []).flatMap((v: any) => v.items ?? [])]) {
    const x = item?.x ?? item?.sprite?.x
    const y = item?.y ?? item?.sprite?.y
    if (typeof x !== 'number' || typeof y !== 'number') continue
    const d = dist(self, { x, y })
    if (d > 260) continue
    out.push({ kind: 'furniture', id: String(item.id ?? item.kind ?? 'furniture'), label: String(item.label ?? item.kind ?? '家具'), distance: d })
  }
  for (const [id, center] of s.buildingCenters ?? new Map()) {
    const d = dist(self, center)
    if (d > 260) continue
    out.push({ kind: 'building', id, label: id, distance: d })
  }
  return out.sort((a, b) => a.distance - b.distance).slice(0, 12)
}

export function snapshot(): ProbeSnapshot {
  const probeErrors: string[] = []
  const s = scene()
  const ui = collectUi(probeErrors)
  const base: ProbeSnapshot = {
    at: new Date().toISOString(),
    ready: false,
    scene: null,
    player: null,
    camera: null,
    activeScene: null,
    renderer: null,
    actors: [],
    nearby: [],
    world: { night: false, runMode: false, walkableRects: 0, obstacleRects: 0, observation: false },
    ui,
    errors: [...errors],
    failedRequests: [...failedRequests],
    probeErrors,
  }
  if (!s) return base

  try {
    const selfWalker = s.selfWalker
    const self = selfWalker ? { x: Math.round(selfWalker.sprite.x), y: Math.round(selfWalker.sprite.y) } : null
    const actors = actorsOf(s, self, probeErrors)
    const cam = s.cameras?.main
    return {
      ...base,
      ready: Boolean(s.selfKeys && s.sys?.game?.scene?.getScenes(true)?.length),
      scene: String(s.scene?.key ?? 'town'),
      player: self && {
        ...self,
        facing: String(selfWalker.facing ?? '?'),
        state: String(selfWalker.state ?? '?'),
        running: Boolean(selfWalker.running),
        controllable: Boolean(s.selfKeys),
        onWalkable: walkableAt(s.collisionWorld, self),
      },
      camera: cam ? { x: Math.round(cam.getWorldPoint(0, 0).x), y: Math.round(cam.getWorldPoint(0, 0).y), zoom: cam.zoom ?? 1, width: cam.width, height: cam.height } : null,
      activeScene: s.sys?.game?.scene?.getScenes(true)?.at(-1)?.sys?.settings?.key ?? null,
      renderer: { fps: Math.round(s.sys?.game?.loop?.actualFps ?? 0), textures: Object.keys(s.sys?.textures?.list ?? {}).length, missingTextures: (s.sys?.displayList?.list ?? []).filter((obj: any) => obj.texture?.key === '__MISSING').map((obj: any) => obj.type) },
      actors,
      nearby: nearbyOf(s, self, actors),
      world: {
        night: Boolean(s.night),
        runMode: Boolean(s.runMode),
        walkableRects: s.collisionWorld?.walkable?.length ?? 0,
        obstacleRects: s.collisionWorld?.obstacles?.length ?? 0,
        observation: (s.observationItinerary?.length ?? 0) > 0,
      },
    }
  } catch (error) {
    probeErrors.push(`snapshot: ${(error as Error).message}`)
    return base
  }
}

/** 把快照写成一段中文。agent 读这个比读 JSON 快，也更容易发现"不对劲"。 */
export function describe(snap: ProbeSnapshot = snapshot()): string {
  const lines: string[] = []
  lines.push(`【${snap.ui.route}】${snap.ready ? `场景 ${snap.scene}` : '引擎没起来'}`)

  if (!snap.ready) {
    lines.push('街道尚未就绪；结合截图、资源错误和当前场景继续检查。')
  } else if (!snap.player) {
    lines.push('⚠️ 找不到玩家小人（selfWalker 为 null）：镜头没有跟随对象，方向键也没有作用对象。')
  } else {
    const p = snap.player
    lines.push(`我站在 (${p.x}, ${p.y})，朝${p.facing}，${p.state === 'walk' ? '正在移动' : '站着'}${p.running ? '（奔跑）' : ''}。`)
    if (!p.controllable) lines.push('⚠️ 方向键无效：selfKeys 没建起来，setupInput() 没跑完。')
    if (!p.onWalkable) lines.push('⚠️ 我脚下不在任何可行走矩形内，走位判定会异常。')
  }

  if (snap.ready) {
    if (snap.activeScene && snap.activeScene !== 'town') lines.push(`当前在室内：${snap.activeScene}（街道已休眠）。`)
    lines.push(`世界：${snap.world.night ? '夜晚' : '白天'}，${snap.world.runMode ? '奔跑开着' : '奔跑关着'}，`
      + `可行走区 ${snap.world.walkableRects} 块 / 障碍 ${snap.world.obstacleRects} 块${snap.world.observation ? '，观察模式进行中' : ''}。`)
    lines.push(`场上 ${snap.actors.length} 个角色：${snap.actors.slice(0, 8).map(a => `${a.name}(${a.x},${a.y},${a.activity})`).join('、') || '无'}`)
    lines.push(snap.nearby.length
      ? `身边能互动的：${snap.nearby.map(n => `${n.label}[${n.kind}] ${n.distance}px`).join('、')}`
      : '身边 260px 内没有可互动的东西。')
  }

  const ui = snap.ui
  lines.push(`界面：dock ${ui.dockCollapsed ? '收起' : '展开'}，`
    + `开着的面板 ${ui.panelsOpen.join('、') || '无'}，托盘里 ${ui.panelsMinimized.join('、') || '无'}。`)
  if (ui.dialogue) lines.push(`对话框：${ui.dialogue.slice(0, 160)}`)
  if (ui.actionMenu.length) lines.push(`动作菜单：${ui.actionMenu.join('、')}`)
  if (ui.feedback.length) lines.push(`反馈条：${ui.feedback.join(' / ')}`)
  if (ui.alerts.length) lines.push(`⚠️ 页面报错条：${ui.alerts.join(' / ')}`)

  if (snap.failedRequests.length) {
    lines.push(`⚠️ 失败的请求 ${snap.failedRequests.length} 条：`
      + snap.failedRequests.slice(-5).map(r => `${r.status} ${r.url}`).join('、'))
  }
  if (snap.errors.length) {
    lines.push(`⚠️ 未捕获错误 ${snap.errors.length} 条：`)
    for (const e of snap.errors.slice(-5)) lines.push(`   ${e.message}\n   ${e.stack.split('\n').slice(1, 3).join('\n   ')}`)
  }
  if (snap.probeErrors.length) lines.push(`（探针自身读取失败：${snap.probeErrors.join('；')}）`)
  return lines.join('\n')
}

/**
 * 按住方向键 ms 毫秒，返回位移。
 *
 * 直接翻 Phaser 的 Key 对象而不是派发 keydown：Phaser 自己在 window 上收事件、按帧结算，
 * 合成事件在 update 循环里读回来一直是 isDown=false（这一点 town-engine.cy.ts 的注释里
 * 已经踩过）。浏览器事件投递那一层由 HUD 的快捷键用例覆盖，这里测的是移动/碰撞逻辑。
 */
async function walk(direction: 'left' | 'right' | 'up' | 'down', ms = 700) {
  const s = scene()
  const keys = s?.selfKeys
  const walker = s?.selfWalker
  if (!s?.sys?.isActive?.()) return { ok: false, reason: '街道休眠中，请先返回街道或使用室内键盘', moved: 0, from: null, to: null }
  if (!keys || !walker) return { ok: false, reason: 'selfKeys 或 selfWalker 不存在，方向键此刻无效', moved: 0, from: null, to: null }
  const key = keys.cursors?.[direction]
  if (!key) return { ok: false, reason: `没有 ${direction} 键`, moved: 0, from: null, to: null }
  const from = { x: Math.round(walker.sprite.x), y: Math.round(walker.sprite.y) }
  key.isDown = true
  key.isUp = false
  await new Promise(resolve => setTimeout(resolve, ms))
  key.isDown = false
  key.isUp = true
  const to = { x: Math.round(walker.sprite.x), y: Math.round(walker.sprite.y) }
  return { ok: true, reason: '', moved: Math.round(Math.hypot(to.x - from.x, to.y - from.y)), from, to }
}

/** 一直往 x 走到位（或超时）。用来把玩家送到某个 NPC/建筑旁边再做交互。 */
async function walkTo(targetX: number, timeoutMs = 8000) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    const walker = scene()?.selfWalker
    if (!walker) return { ok: false, reason: 'selfWalker 不存在', x: null }
    const gap = targetX - walker.sprite.x
    if (Math.abs(gap) < 24) return { ok: true, reason: '', x: Math.round(walker.sprite.x) }
    const step = await walk(gap > 0 ? 'right' : 'left', 300)
    if (!step.ok) return { ok: false, reason: step.reason, x: null }
    if (step.moved < 2) return { ok: false, reason: `在 x=${step.to?.x} 卡住了，可能撞到障碍`, x: step.to?.x ?? null }
  }
  return { ok: false, reason: '超时', x: Math.round(scene()?.selfWalker?.sprite.x ?? 0) }
}

export function installTownProbe() {
  if (installed) return
  installed = true

  window.addEventListener('error', event => {
    push(errors, { message: String(event.message), stack: String(event.error?.stack ?? ''), at: new Date().toISOString() })
  })
  window.addEventListener('unhandledrejection', event => {
    const reason = event.reason as { message?: string; stack?: string } | undefined
    push(errors, { message: `unhandledrejection: ${reason?.message ?? String(event.reason)}`, stack: String(reason?.stack ?? ''), at: new Date().toISOString() })
  })

  // 失败的请求同样是"看不见的坏"：面板空着不一定是没数据，也可能是接口 500 了。
  const originalOpen = XMLHttpRequest.prototype.open
  XMLHttpRequest.prototype.open = function(...args: any[]) {
    const xhr = this
    const url = String(args[1])
    xhr.addEventListener('loadend', () => {
      if (xhr.status >= 400) push(failedRequests, { url, status: xhr.status, at: new Date().toISOString() })
    }, { once: true })
    return (originalOpen as any).apply(xhr, args)
  }
  const originalFetch = window.fetch.bind(window)
  window.fetch = async (...args: Parameters<typeof fetch>) => {
    const url = typeof args[0] === 'string' ? args[0] : (args[0] as Request).url ?? String(args[0])
    try {
      const response = await originalFetch(...args)
      if (!response.ok) push(failedRequests, { url, status: response.status, at: new Date().toISOString() })
      return response
    } catch (error) {
      if ((error as Error).name !== 'AbortError') push(failedRequests, { url, status: 0, at: new Date().toISOString() })
      throw error
    }
  }

  ;(window as any).__town = {
    snapshot,
    describe: () => describe(),
    walk,
    walkTo,
    /** 清空累计的错误，方便按步骤分段判断"这一步引入了什么"。 */
    reset: () => { errors.length = 0; failedRequests.length = 0 },
  }
}
