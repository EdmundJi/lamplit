import { describe, it, expect } from 'vitest'
import {
  currentTimeSlot,
  calculateMood,
  npcContextForChat,
  shouldSeekShelter,
  shelterLocation,
  ASSISTANT_SCHEDULE,
  POSTMAN_SCHEDULE,
} from './npc-schedule'

describe('npc-schedule', () => {
  describe('currentTimeSlot', () => {
    it('returns the correct slot for assistant at different times', () => {
      expect(currentTimeSlot('assistant', 3).locationLabel).toBe('学院门口休息')
      expect(currentTimeSlot('assistant', 7).locationLabel).toBe('公告栏张贴通知')
      expect(currentTimeSlot('assistant', 12).locationLabel).toBe('咖啡馆休息')
      expect(currentTimeSlot('assistant', 15).locationLabel).toBe('学院门口看书')
      expect(currentTimeSlot('assistant', 19).locationLabel).toBe('公园散步')
      expect(currentTimeSlot('assistant', 22).locationLabel).toBe('学院门口准备回家')
    })

    it('returns the correct slot for postman at different times', () => {
      expect(currentTimeSlot('postman', 5).locationLabel).toBe('邮局休息')
      expect(currentTimeSlot('postman', 9).locationLabel).toBe('街上送信')
      expect(currentTimeSlot('postman', 12).locationLabel).toBe('邮局午休')
      expect(currentTimeSlot('postman', 15).locationLabel).toBe('街上送信')
      expect(currentTimeSlot('postman', 18).locationLabel).toBe('咖啡馆休息')
      expect(currentTimeSlot('postman', 21).locationLabel).toBe('邮局准备下班')
    })

    it('falls back to first slot when no match', () => {
      const slot = currentTimeSlot('assistant', 25)
      expect(slot).toBe(ASSISTANT_SCHEDULE.timeSlots[0])
    })
  })

  describe('calculateMood', () => {
    it('respects slot mood when specified', () => {
      expect(calculateMood({ npcId: 'assistant', hour: 3, weather: 'clear', recentChatCount: 0, slotMood: 'tired' })).toBe('tired')
    })

    it('returns tired when chat count exceeds threshold', () => {
      expect(calculateMood({ npcId: 'assistant', hour: 10, weather: 'clear', recentChatCount: 20 })).toBe('tired')
    })

    it('adjusts mood based on weather', () => {
      expect(calculateMood({ npcId: 'assistant', hour: 10, weather: 'rain', recentChatCount: 0 })).toBe('normal')
      expect(calculateMood({ npcId: 'assistant', hour: 20, weather: 'rain', recentChatCount: 0 })).toBe('tired')
    })

    it('varies mood by time of day', () => {
      expect(calculateMood({ npcId: 'assistant', hour: 8, weather: 'clear', recentChatCount: 0 })).toBe('happy')
      expect(calculateMood({ npcId: 'assistant', hour: 13, weather: 'clear', recentChatCount: 0 })).toBe('normal')
      expect(calculateMood({ npcId: 'assistant', hour: 16, weather: 'clear', recentChatCount: 0 })).toBe('happy')
      expect(calculateMood({ npcId: 'assistant', hour: 20, weather: 'clear', recentChatCount: 0 })).toBe('normal')
      expect(calculateMood({ npcId: 'assistant', hour: 2, weather: 'clear', recentChatCount: 0 })).toBe('tired')
    })
  })

  describe('npcContextForChat', () => {
    it('generates context string with time, weather, mood, and location', () => {
      const context = npcContextForChat({ npcId: 'assistant', hour: 10, weather: 'clear', recentChatCount: 0 })
      expect(context).toContain('现在是10点')
      expect(context).toContain('晴天')
      expect(context).toContain('学院门口')
    })

    it('reflects rainy weather', () => {
      const context = npcContextForChat({ npcId: 'postman', hour: 14, weather: 'rain', recentChatCount: 0 })
      expect(context).toContain('雨天')
    })

    it('reflects tired mood from high chat count', () => {
      const context = npcContextForChat({ npcId: 'assistant', hour: 10, weather: 'clear', recentChatCount: 20 })
      expect(context).toContain('有点疲惫')
    })
  })

  describe('shouldSeekShelter', () => {
    it('returns false when weather is clear', () => {
      expect(shouldSeekShelter('assistant', 10, 'clear')).toBe(false)
    })

    it('returns true when raining and NPC is outdoors', () => {
      expect(shouldSeekShelter('assistant', 10, 'rain')).toBe(true) // 学院门口
      expect(shouldSeekShelter('assistant', 7, 'rain')).toBe(true) // 公告栏
      expect(shouldSeekShelter('assistant', 19, 'rain')).toBe(true) // 公园
    })

    it('returns false when raining but NPC is already sheltered', () => {
      expect(shouldSeekShelter('assistant', 13, 'rain')).toBe(false) // 咖啡馆
      expect(shouldSeekShelter('postman', 12, 'rain')).toBe(false) // 邮局
    })
  })

  describe('shelterLocation', () => {
    it('returns shelter coords for assistant', () => {
      const loc = shelterLocation('assistant')
      expect(loc).toEqual({ x: -90, y: -40 })
    })

    it('returns shelter coords for postman', () => {
      const loc = shelterLocation('postman')
      expect(loc).toEqual({ x: -120, y: 0 })
    })
  })
})
