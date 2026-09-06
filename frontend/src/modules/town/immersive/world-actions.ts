import type { Component } from 'vue'
import { CalendarCheck, Footprints, GraduationCap, Home, Moon, PawPrint, RefreshCw, TrendingUp } from 'lucide-vue-next'
import { PET_HOUSE_ACTION_ID } from '../interior.scene'
import type { WorldAnchor, WorldEvent } from './panel.types'
import type { WorldBridge } from './panel.types'
import { worldPanels } from './panels/manifest'

/**
 * 世界能力（World Action）注册表。
 *
 * 这是"能在小镇里做的一件事"的统一描述：昼夜切换、开关跑步、打开某个面板，将来任何一个
 * 领域（今天/目标/好友/伙伴/洞察/属性/个人/设置）想让自己的某个操作"长在世界里"——不用再开一个
 * 新页面、不用再发明一套自己的提示条——都注册一个 WorldAction 到这里就行。外壳
 * （ImmersiveTown.vue）和动作菜单（WorldActionMenu.vue）只认这份注册表，不关心某个能力具体
 * 属于哪个模块。
 *
 * 本文件不实现任何业务：下面 `builtinWorldActions()` 给出的是"世界级"能力（昼夜/跑步/回家/
 * 学院/打开面板/刷新），当例子看。一个新功能要接入，做三件事：
 *
 *   1. 声明能力：写一个 WorldAction，给 id（建议 "<domain>.<verb>"，如 "goals.create-draft"）、
 *      label/hint（给动作菜单看的文字，可以是字符串，也可以是 (ctx) => string 根据当前状态换
 *      文案）、domain（分组用）。
 *   2. 声明触发点：填 anchors（小镇里哪些地点/NPC 旁会出现这个能力）。不填 = 全局能力，任何
 *      锚点的动作菜单都会带上它（用于"随时能做"的事，比如切昼夜）。
 *   3. 实现效果：available(ctx) 判断当前能不能用（连原因一起给），run(ctx) 做真正的事，返回
 *      { ok, message, events }——message 会自动出现在 WorldFeedback 里，events 会自动回放给
 *      WorldBridge.emit（开面板、移镜头、播放庆祝、弹提示条……都走这条已有的路，不用自己再造）。
 *      需要二次确认的操作再给一个 confirm 文案，动作菜单会先问一句再真正调用 run()。
 *
 * 注册的时机：领域模块在自己被引入时调用一次 registerWorldActions([...])（比如面板组件的
 * <script setup> 顶层，或该模块的入口文件）即可；重复调用用相同 id 覆盖，不会重复出现。
 */

export type WorldActionDomain =
  | 'today' | 'goals' | 'friends' | 'partners' | 'insights' | 'attributes' | 'profile' | 'settings' | 'world'

/** 调用一个能力时外壳提供的上下文。刻意不引入 TownGame/TownSelection 类型（同 immersive.store.ts
 * 里 anchorForSelection 的做法），世界效果一律通过 bridge.emit 的 WorldEvent 去驱动引擎，这样这份
 * 注册表不用知道 Phaser 长什么样，领域模块写 run() 时也不用管小镇引擎的类型。 */
export type WorldActionContext = {
  bridge: WorldBridge
  /** 玩家当前站在哪个锚点旁；不在任何锚点旁时是 null。 */
  anchor: WorldAnchor | null
  /** 当前登录者在小镇里的 publicId；小镇模型还没加载出来时是 null。 */
  selfPublicId: string | null
  /** 现在是不是夜晚——TownGame.setNight 只有 setter，这个状态由外壳记着再传进来。 */
  night: boolean
  /** 是否已经在成长学院内部（TownGame.enterAcademy/exitAcademy 同理，只有方法没有查询）。 */
  insideAcademy: boolean
  /** 重新拉一次小镇模型（对应 town.store 的 load()）。由外壳提供，这份注册表不知道 store 长什么样。 */
  refresh(): void | Promise<void>
  /** WorldBridge.run(id, payload) 透传下来的调用参数，能力自己决定要不要用。 */
  payload?: unknown
}

export type WorldActionAvailability = { ok: true } | { ok: false; reason: string }

export type WorldActionResult = {
  ok: boolean
  /** 给 WorldFeedback 看的一句话，成功失败都可以有。 */
  message?: string
  /** 这次调用要在世界里引发的连锁反应，会依次交给 bridge.emit。 */
  events?: WorldEvent[]
}

/** 大多数文案是固定字符串；需要跟着当前状态变（比如"进入学院"/"回到小镇"）就写成函数。 */
export type WorldActionText = string | ((ctx: WorldActionContext) => string)

export type WorldAction = {
  /** 全局唯一，建议 "<domain>.<verb>"。 */
  id: string
  label: WorldActionText
  /** 出现在能力下方的一句说明；不需要就留空。 */
  hint?: WorldActionText
  icon?: Component
  domain: WorldActionDomain
  /** 在哪些地点/NPC 旁的动作菜单会带上这个能力；不填代表"随时随地都带上"。 */
  anchors?: WorldAnchor[]
  /** 判断当前能不能用；不写就当作永远可用。不可用要给原因，菜单会显示原因而不是隐藏这个能力。 */
  available?(ctx: WorldActionContext): WorldActionAvailability
  /** 需要二次确认时的提示文案；动作菜单会先展示这句话等玩家确认，再真正调用 run()。 */
  confirm?: WorldActionText
  run(ctx: WorldActionContext): WorldActionResult | Promise<WorldActionResult>
}

/** 给动作菜单这类 UI 用的、文案已经按当前 ctx 求好值的版本。 */
export type ResolvedWorldAction = {
  id: string
  label: string
  hint: string
  icon?: Component
  domain: WorldActionDomain
  availability: WorldActionAvailability
  /** 非 null 时，UI 要先让玩家确认这句话，再以 confirmed:true 调用 run。 */
  confirmText: string | null
}

function resolveText(value: WorldActionText | undefined, ctx: WorldActionContext, fallback = ''): string {
  if (value === undefined) return fallback
  return typeof value === 'function' ? value(ctx) : value
}

const registry = new Map<string, WorldAction>()

/** 注册一批能力；同 id 后注册的会覆盖先注册的（方便热更新/测试）。 */
export function registerWorldActions(actions: WorldAction[]) {
  for (const action of actions) registry.set(action.id, action)
}

export function getWorldAction(id: string): WorldAction | undefined {
  return registry.get(id)
}

export function allWorldActions(): WorldAction[] {
  return [...registry.values()]
}

/** 按锚点/领域过滤。锚点过滤：没声明 anchors 的能力（全局能力）在任何锚点下都会带上。 */
export function worldActionsFor(filter: { anchor?: WorldAnchor | null; domain?: WorldActionDomain } = {}): WorldAction[] {
  return allWorldActions().filter(action => {
    if (filter.domain !== undefined && action.domain !== filter.domain) return false
    if (filter.anchor !== undefined && filter.anchor !== null) {
      if (action.anchors && !action.anchors.includes(filter.anchor)) return false
    } else if (filter.anchor === null && action.anchors) {
      return false // 不在任何锚点旁：只保留全局能力
    }
    return true
  })
}

/** 把一个能力按当前 ctx 求值成 UI 可以直接渲染的形状。 */
export function resolveWorldAction(action: WorldAction, ctx: WorldActionContext): ResolvedWorldAction {
  const availability: WorldActionAvailability = action.available ? action.available(ctx) : { ok: true }
  const confirmText = action.confirm ? resolveText(action.confirm, ctx) || null : null
  return {
    id: action.id,
    label: resolveText(action.label, ctx, action.id),
    hint: resolveText(action.hint, ctx),
    icon: action.icon,
    domain: action.domain,
    availability,
    confirmText,
  }
}

/** 真正调用一个能力：查不到 id、或 available() 说不行，都直接给出失败结果，不会跑到 run()。
 * run() 返回的 events 会按顺序交给 ctx.bridge.emit——celebrate/focus/toast/night/academy 等
 * 世界反馈由外壳的同一条通路处理，这里不用关心引擎细节。 */
export async function runWorldAction(id: string, ctx: WorldActionContext): Promise<WorldActionResult> {
  const action = registry.get(id)
  if (!action) return { ok: false, message: '这个操作暂时不存在' }
  const availability: WorldActionAvailability = action.available ? action.available(ctx) : { ok: true }
  if (!availability.ok) return { ok: false, message: availability.reason }
  const result = await action.run(ctx)
  for (const event of result.events ?? []) ctx.bridge.emit(event)
  return result
}

/** 仅供测试/热重载清空注册表；业务代码不要调用。 */
export function clearWorldActions() {
  registry.clear()
}

// ---------------------------------------------------------------------------
// 世界级内置能力：昼夜、跑步、回家、进出学院、打开面板、刷新小镇。
// 这些不属于任何具体领域，domain 统一给 'world'。
// ---------------------------------------------------------------------------

function toggleNightAction(): WorldAction {
  return {
    id: 'world.toggle-night',
    label: ctx => (ctx.night ? '切到白天' : '切到夜晚'),
    hint: '小镇的灯光和天色跟着换一次',
    icon: Moon,
    domain: 'world',
    run(ctx) {
      const value = !ctx.night
      return { ok: true, message: value ? '夜幕降下来了' : '天亮了', events: [{ type: 'night', value }] }
    },
  }
}

function toggleRunAction(): WorldAction {
  return {
    id: 'world.toggle-run',
    label: ctx => (ctx.bridge.runMode ? '停下奔跑' : '开始奔跑'),
    hint: '和顶栏的奔跑开关、快捷键 R 是同一个开关',
    icon: Footprints,
    domain: 'world',
    run(ctx) {
      const enabled = !ctx.bridge.runMode
      ctx.bridge.setRunMode(enabled)
      return { ok: true, message: enabled ? '开始奔跑' : '恢复步行' }
    },
  }
}

function goHomeAction(): WorldAction {
  return {
    id: 'world.go-home',
    label: '回到我家',
    hint: '沿街走到家门口，自动进入客厅',
    icon: Home,
    domain: 'world',
    available: ctx => (ctx.selfPublicId ? { ok: true } : { ok: false, reason: '还没找到你的角色' }),
    run(ctx) {
      return { ok: true, message: '正在往家走', events: [{ type: 'travel', place: 'home' }] }
    },
  }
}

function toggleAcademyAction(): WorldAction {
  return {
    id: 'world.toggle-academy',
    label: ctx => (ctx.insideAcademy ? '回到小镇' : '去成长学院'),
    hint: '走到学院门口，在自习室里继续今天的学习',
    icon: GraduationCap,
    domain: 'world',
    // 离开学院时的中断提示：进入学院不需要确认，退出时提醒一句更贴心。

    run(ctx) {
      const entering = !ctx.insideAcademy
      return {
        ok: true,
        message: entering ? '进了成长学院' : '回到小镇街上',
        events: [{ type: 'academy', value: entering }],
      }
    },
  }
}

function refreshAction(): WorldAction {
  return {
    id: 'world.refresh',
    label: '刷新小镇',
    hint: '立刻拉一次最新数据，不用等下一次自动轮询',
    icon: RefreshCw,
    domain: 'world',
    async run(ctx) {
      await ctx.refresh()
      return { ok: true, message: '小镇数据已经是最新的了' }
    },
  }
}

// ---------------------------------------------------------------------------
// 我的家 · 三件可交互物（M3-4）：书桌 / 成就墙 / 宠物窝。interior.scene.ts 点击对应家具时会调用
// runWorldAction(这里的 id, ctx)——地图数据和场景本身都不知道"打开书桌"具体是什么，统一走这份
// 注册表，和门/onExit 是同一个"数据只说做什么、这里才说怎么做"的分工。
// ---------------------------------------------------------------------------

function openDeskAction(): WorldAction {
  return {
    id: 'home.open-desk',
    label: '打开书桌',
    hint: '看看今天要做的这一件事',
    icon: CalendarCheck,
    domain: 'world',
    anchors: ['home'],
    run: () => ({ ok: true, events: [{ type: 'open', panel: 'today' }] }),
  }
}

function openAchievementWallAction(): WorldAction {
  return {
    id: 'home.open-achievement-wall',
    label: '看看成就墙',
    hint: '这段时间的成长，都挂在这面墙上',
    icon: TrendingUp,
    domain: 'world',
    anchors: ['home'],
    run: () => ({ ok: true, events: [{ type: 'open', panel: 'insights' }] }),
  }
}

/** id 从 interior.scene.ts 导入而不是重复写一遍字符串——那边的宠物窝家具/宠物本身点击后调用的
 * 就是这同一个 id，两处不会因为改了一边忘了改另一边而悄悄错开。 */
function openPetHouseAction(): WorldAction {
  return {
    id: PET_HOUSE_ACTION_ID,
    label: '看看伙伴',
    hint: '你的宠物窝在客厅角落',
    icon: PawPrint,
    domain: 'world',
    anchors: ['home'],
    run: () => ({ ok: true, events: [{ type: 'open', panel: 'partners' }] }),
  }
}

/** 每个声明了 anchor 的面板生成一个"打开 XX"的能力，走到对应地点时能在动作菜单里手动重开
 * （面板自动开一次之后被关掉，这是重新打开它的入口）。没声明 anchor 的面板留给 dock 触达。 */
function panelOpenActions(): WorldAction[] {
  return worldPanels
    .filter(panel => panel.anchor)
    .map(panel => ({
      id: `world.open-panel:${panel.key}`,
      label: `打开${panel.title}`,
      hint: panel.subtitle,
      icon: panel.icon,
      domain: 'world' as const,
      anchors: [panel.anchor as WorldAnchor],
      run: () => ({ ok: true, events: [{ type: 'open', panel: panel.key }] }),
    }))
}

export function builtinWorldActions(): WorldAction[] {
  return [
    toggleNightAction(), toggleRunAction(), goHomeAction(), toggleAcademyAction(), refreshAction(),
    openDeskAction(), openAchievementWallAction(), openPetHouseAction(),
    { id: 'gym.open-attributes', label: '看看健康与成长', domain: 'world', anchors: ['gym'], run: () => ({ ok: true, events: [{ type: 'open', panel: 'attributes' }] }) },
    { id: 'cafe.open-goals', label: '在桌边理理方向', domain: 'world', anchors: ['cafe'], run: () => ({ ok: true, events: [{ type: 'open', panel: 'goals' }] }) },
    { id: 'cafe.open-ai', label: '与小助聊聊', domain: 'world', anchors: ['cafe'], run: () => ({ ok: true, events: [{ type: 'open', panel: 'ai' }] }) },
    ...panelOpenActions(),
  ]
}

/** 外壳在挂载时调用一次即可；重复调用是幂等的（同 id 覆盖）。 */
export function registerBuiltinWorldActions() {
  registerWorldActions(builtinWorldActions())
}
