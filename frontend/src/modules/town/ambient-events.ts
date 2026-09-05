/**
 * 环境小事件系统：让世界感觉"活着"的随机小事
 * 每隔一段时间触发一件小事，纯视觉反馈，不影响游戏逻辑
 */

import type { WeatherKind } from './atmosphere'

export type AmbientEventType =
  | 'bird_fly'
  | 'delivery'
  | 'npc_stretch'
  | 'npc_wipe_sweat'
  | 'neighbor_phone'
  | 'stray_cat'
  | 'bulletin_update'
  | 'balloon_float'

export type AmbientEvent = {
  id: AmbientEventType
  /** 权重，决定触发概率（权重越高越容易触发） */
  weight: number
  /** 触发条件（天气、时间） */
  condition?: (context: AmbientContext) => boolean
  /** 最小触发间隔（毫秒），防止同一事件频繁重复 */
  cooldownMs: number
}

export type AmbientContext = {
  weather: WeatherKind
  hour: number
  /** 最近完成任务的次数（用于触发气球飘过） */
  recentCompletions: number
}

export const AMBIENT_EVENTS: AmbientEvent[] = [
  {
    id: 'bird_fly',
    weight: 10,
    cooldownMs: 120_000, // 2分钟
  },
  {
    id: 'delivery',
    weight: 5,
    condition: ctx => ctx.hour >= 9 && ctx.hour < 18, // 工作时间
    cooldownMs: 300_000, // 5分钟
  },
  {
    id: 'npc_stretch',
    weight: 8,
    condition: ctx => ctx.hour >= 14 && ctx.hour < 16, // 下午容易疲劳
    cooldownMs: 180_000, // 3分钟
  },
  {
    id: 'npc_wipe_sweat',
    weight: 6,
    condition: ctx => ctx.weather === 'clear' && ctx.hour >= 11 && ctx.hour < 15, // 晴天中午热
    cooldownMs: 240_000, // 4分钟
  },
  {
    id: 'neighbor_phone',
    weight: 7,
    cooldownMs: 200_000, // 3分20秒
  },
  {
    id: 'stray_cat',
    weight: 4,
    condition: ctx => ctx.hour >= 6 && ctx.hour < 22, // 白天活动
    cooldownMs: 360_000, // 6分钟
  },
  {
    id: 'bulletin_update',
    weight: 3,
    condition: ctx => ctx.hour === 9 || ctx.hour === 15, // 上午下午各一次
    cooldownMs: 600_000, // 10分钟
  },
  {
    id: 'balloon_float',
    weight: 12,
    condition: ctx => ctx.recentCompletions > 0, // 完成任务后触发
    cooldownMs: 150_000, // 2分30秒
  },
]

/** 事件冷却记录：事件ID -> 最早可再次触发的时间戳 */
type EventCooldowns = Map<AmbientEventType, number>

/**
 * 环境事件调度器
 */
export class AmbientEventScheduler {
  private cooldowns: EventCooldowns = new Map()
  private lastTriggerTime = 0
  private readonly minIntervalMs: number
  private readonly maxIntervalMs: number

  constructor(minIntervalMs = 180_000, maxIntervalMs = 600_000) {
    this.minIntervalMs = minIntervalMs // 最小3分钟
    this.maxIntervalMs = maxIntervalMs // 最大10分钟
  }

  /**
   * 检查是否应该触发一个事件（基于时间间隔）
   */
  shouldTrigger(now: number): boolean {
    if (this.lastTriggerTime === 0) {
      this.lastTriggerTime = now
      return false
    }
    const elapsed = now - this.lastTriggerTime
    // 使用最小间隔作为基准（随机部分在实际使用时有变化，测试时保持确定性）
    return elapsed >= this.minIntervalMs
  }

  /**
   * 选择一个符合条件的事件（基于权重和冷却）
   */
  selectEvent(context: AmbientContext, now: number): AmbientEventType | null {
    const candidates = AMBIENT_EVENTS.filter(event => {
      // 检查冷却
      const cooldownEnd = this.cooldowns.get(event.id) ?? 0
      if (now < cooldownEnd) return false
      // 检查条件
      if (event.condition && !event.condition(context)) return false
      return true
    })

    if (candidates.length === 0) return null

    // 按权重随机选择
    const totalWeight = candidates.reduce((sum, e) => sum + e.weight, 0)
    let random = Math.random() * totalWeight
    for (const event of candidates) {
      random -= event.weight
      if (random <= 0) {
        this.cooldowns.set(event.id, now + event.cooldownMs)
        this.lastTriggerTime = now
        return event.id
      }
    }

    return null
  }

  /**
   * 重置调度器（切换场景时调用）
   */
  reset(): void {
    this.cooldowns.clear()
    this.lastTriggerTime = 0
  }

  /**
   * 手动标记某事件为冷却状态（完成任务后触发气球时用）
   */
  markTriggered(eventId: AmbientEventType, now: number): void {
    const event = AMBIENT_EVENTS.find(e => e.id === eventId)
    if (event) {
      this.cooldowns.set(eventId, now + event.cooldownMs)
    }
  }
}

/**
 * 获取事件的执行参数（坐标、动画时长等）
 */
export function eventExecutionParams(
  eventId: AmbientEventType,
  worldWidth: number,
  baselineY: number,
): Record<string, unknown> {
  switch (eventId) {
    case 'bird_fly':
      return {
        startX: -40,
        endX: worldWidth + 40,
        y: baselineY - 500 - Math.random() * 200,
        duration: 3000 + Math.random() * 2000,
      }
    case 'delivery':
      return {
        targetX: Math.random() * worldWidth,
        y: baselineY + 60,
        duration: 2000,
      }
    case 'balloon_float':
      return {
        startX: Math.random() * worldWidth,
        startY: baselineY + 100,
        endY: -100,
        duration: 4000 + Math.random() * 2000,
      }
    default:
      return {}
  }
}
