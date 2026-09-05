/**
 * NPC 日程系统：小助和邮递员按一天中的时间在不同地点活动，
 * 执行不同动作，表现不同心情。
 */

export type NpcId = 'assistant' | 'postman'
export type NpcMood = 'happy' | 'normal' | 'tired'
export type NpcActivity = 'idle' | 'reading' | 'sit' | 'phone' | 'walking'

export type NpcTimeSlot = {
  /** 开始小时 0-23 */
  startHour: number
  /** 结束小时 0-23（不含） */
  endHour: number
  /** 位置坐标（相对于特定地标的偏移） */
  location: { x: number; y: number }
  /** 执行的动作 */
  activity: NpcActivity
  /** 心情（可选，未指定时根据天气/时间计算） */
  mood?: NpcMood
  /** 位置描述（用于对话上下文） */
  locationLabel: string
}

export type NpcSchedule = {
  npcId: NpcId
  timeSlots: NpcTimeSlot[]
}

/** 小助的一天日程：上午在公告栏、中午咖啡馆、下午学院门口、晚上回家 */
export const ASSISTANT_SCHEDULE: NpcSchedule = {
  npcId: 'assistant',
  timeSlots: [
    { startHour: 0, endHour: 6, location: { x: -70, y: 0 }, activity: 'idle', mood: 'tired', locationLabel: '学院门口休息' },
    { startHour: 6, endHour: 9, location: { x: -120, y: -60 }, activity: 'reading', locationLabel: '公告栏张贴通知' },
    { startHour: 9, endHour: 12, location: { x: -70, y: 0 }, activity: 'idle', locationLabel: '学院门口' },
    { startHour: 12, endHour: 14, location: { x: -200, y: 40 }, activity: 'sit', locationLabel: '咖啡馆休息' },
    { startHour: 14, endHour: 18, location: { x: -70, y: 0 }, activity: 'reading', locationLabel: '学院门口看书' },
    { startHour: 18, endHour: 20, location: { x: -140, y: 20 }, activity: 'phone', locationLabel: '公园散步' },
    { startHour: 20, endHour: 24, location: { x: -70, y: 0 }, activity: 'idle', mood: 'tired', locationLabel: '学院门口准备回家' },
  ],
}

/** 邮递员的一天日程：上午送信、中午休息、下午继续送信、晚上回邮局 */
export const POSTMAN_SCHEDULE: NpcSchedule = {
  npcId: 'postman',
  timeSlots: [
    { startHour: 0, endHour: 7, location: { x: -120, y: 0 }, activity: 'idle', mood: 'tired', locationLabel: '邮局休息' },
    { startHour: 7, endHour: 12, location: { x: 0, y: 0 }, activity: 'walking', locationLabel: '街上送信' }, // patrol
    { startHour: 12, endHour: 13, location: { x: -120, y: 0 }, activity: 'sit', locationLabel: '邮局午休' },
    { startHour: 13, endHour: 18, location: { x: 0, y: 0 }, activity: 'walking', locationLabel: '街上送信' }, // patrol
    { startHour: 18, endHour: 19, location: { x: -200, y: 40 }, activity: 'sit', locationLabel: '咖啡馆休息' },
    { startHour: 19, endHour: 24, location: { x: -120, y: 0 }, activity: 'idle', mood: 'tired', locationLabel: '邮局准备下班' },
  ],
}

const SCHEDULES: Record<NpcId, NpcSchedule> = {
  assistant: ASSISTANT_SCHEDULE,
  postman: POSTMAN_SCHEDULE,
}

/**
 * 根据当前小时查找 NPC 应该在的时间段
 */
export function currentTimeSlot(npcId: NpcId, hour: number): NpcTimeSlot {
  const schedule = SCHEDULES[npcId]
  const slot = schedule.timeSlots.find(s => hour >= s.startHour && hour < s.endHour)
  return slot ?? schedule.timeSlots[0]
}

/**
 * 计算 NPC 当前心情（基于时间、天气、对话次数）
 */
export function calculateMood(params: {
  npcId: NpcId
  hour: number
  weather: 'clear' | 'rain' | 'snow'
  recentChatCount: number
  slotMood?: NpcMood
}): NpcMood {
  const { hour, weather, recentChatCount, slotMood } = params

  // 时间段指定的心情优先
  if (slotMood) return slotMood

  // 对话太多会疲惫
  if (recentChatCount > 15) return 'tired'

  // 天气影响
  if (weather === 'rain') return hour >= 18 ? 'tired' : 'normal'
  if (weather === 'snow') return 'normal'

  // 时间影响
  if (hour >= 6 && hour < 12) return 'happy' // 早晨精力充沛
  if (hour >= 12 && hour < 14) return 'normal' // 午休时间
  if (hour >= 14 && hour < 18) return 'happy' // 下午活跃
  if (hour >= 18 && hour < 22) return 'normal' // 傍晚放松
  return 'tired' // 深夜/凌晨
}

/**
 * 生成用于 NPC 对话的上下文信息（时间、地点、心情、天气）
 */
export function npcContextForChat(params: {
  npcId: NpcId
  hour: number
  weather: 'clear' | 'rain' | 'snow'
  recentChatCount: number
}): string {
  const { npcId, hour, weather, recentChatCount } = params
  const slot = currentTimeSlot(npcId, hour)
  const mood = calculateMood({ npcId, hour, weather, recentChatCount, slotMood: slot.mood })

  const weatherLabels = { clear: '晴天', rain: '雨天', snow: '雪天' }
  const moodLabels = { happy: '心情不错', normal: '心情平静', tired: '有点疲惫' }
  const activityLabels: Record<NpcActivity, string> = {
    idle: '站着',
    reading: '正在看资料',
    sit: '坐着休息',
    phone: '在打电话',
    walking: '正在走动',
  }

  return `现在是${hour}点，${weatherLabels[weather]}。你${moodLabels[mood]}，在${slot.locationLabel}${activityLabels[slot.activity]}。`
}

/**
 * NPC 是否应该避雨（下雨时躲到屋檐下）
 */
export function shouldSeekShelter(npcId: NpcId, hour: number, weather: 'clear' | 'rain' | 'snow'): boolean {
  if (weather !== 'rain') return false
  const slot = currentTimeSlot(npcId, hour)
  // 在咖啡馆、邮局、室内已经是避雨状态
  return slot.locationLabel.includes('门口') || slot.locationLabel.includes('公告栏') || slot.locationLabel.includes('公园')
}

/**
 * 获取避雨位置（下雨时的替代位置）
 */
export function shelterLocation(npcId: NpcId): { x: number; y: number } {
  if (npcId === 'assistant') return { x: -90, y: -40 } // 学院屋檐下
  return { x: -120, y: 0 } // 邮局门口
}
