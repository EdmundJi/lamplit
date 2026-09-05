import { describe, it, expect, beforeEach } from 'vitest'
import { AmbientEventScheduler, AMBIENT_EVENTS, eventExecutionParams } from './ambient-events'
import type { AmbientContext } from './ambient-events'

describe('ambient-events', () => {
  describe('AmbientEventScheduler', () => {
    let scheduler: AmbientEventScheduler
    let now: number

    beforeEach(() => {
      scheduler = new AmbientEventScheduler(1000, 2000) // 短间隔用于测试
      now = Date.now()
    })

    it('does not trigger immediately on first check', () => {
      expect(scheduler.shouldTrigger(now)).toBe(false)
    })

    it('triggers after minimum interval', () => {
      expect(scheduler.shouldTrigger(now)).toBe(false) // first call initializes
      expect(scheduler.shouldTrigger(now + 500)).toBe(false)
      expect(scheduler.shouldTrigger(now + 1500)).toBe(true)
    })

    it('selects event based on weight and conditions', () => {
      const context: AmbientContext = { weather: 'clear', hour: 10, recentCompletions: 0 }
      scheduler.shouldTrigger(now)
      const event = scheduler.selectEvent(context, now + 2000)
      expect(event).toBeTruthy()
      expect(AMBIENT_EVENTS.find(e => e.id === event)).toBeTruthy()
    })

    it('respects event cooldowns', () => {
      const context: AmbientContext = { weather: 'clear', hour: 10, recentCompletions: 0 }
      scheduler.shouldTrigger(now)
      const first = scheduler.selectEvent(context, now + 2000)
      expect(first).toBeTruthy()

      // Same event should not trigger again immediately
      const eventConfig = AMBIENT_EVENTS.find(e => e.id === first)
      const second = scheduler.selectEvent(context, now + 3000)
      // If only one event type matches, second could be null
      if (second === first) {
        // This means cooldown wasn't respected
        expect(now + 3000).toBeGreaterThanOrEqual(now + 2000 + (eventConfig?.cooldownMs ?? 0))
      }
    })

    it('filters events by condition', () => {
      const context: AmbientContext = { weather: 'rain', hour: 3, recentCompletions: 0 }
      scheduler.shouldTrigger(now)
      const event = scheduler.selectEvent(context, now + 2000)
      if (event) {
        const config = AMBIENT_EVENTS.find(e => e.id === event)
        if (config?.condition) {
          expect(config.condition(context)).toBe(true)
        }
      }
    })

    it('resets cooldowns and trigger time', () => {
      scheduler.shouldTrigger(now)
      scheduler.reset()
      expect(scheduler.shouldTrigger(now + 3000)).toBe(false)
    })

    it('manually marks event as triggered', () => {
      scheduler.markTriggered('balloon_float', now)
      const context: AmbientContext = { weather: 'clear', hour: 10, recentCompletions: 1 }
      const event = scheduler.selectEvent(context, now + 1000)
      expect(event).not.toBe('balloon_float')
    })
  })

  describe('eventExecutionParams', () => {
    it('returns bird_fly params with valid coordinates', () => {
      const params = eventExecutionParams('bird_fly', 2000, 800)
      expect(params).toHaveProperty('startX')
      expect(params).toHaveProperty('endX')
      expect(params).toHaveProperty('y')
      expect(params).toHaveProperty('duration')
      expect((params.startX as number)).toBeLessThan(0)
      expect((params.endX as number)).toBeGreaterThan(2000)
    })

    it('returns delivery params', () => {
      const params = eventExecutionParams('delivery', 2000, 800)
      expect(params).toHaveProperty('targetX')
      expect(params).toHaveProperty('y')
      expect(params).toHaveProperty('duration')
    })

    it('returns balloon_float params', () => {
      const params = eventExecutionParams('balloon_float', 2000, 800)
      expect(params).toHaveProperty('startX')
      expect(params).toHaveProperty('startY')
      expect(params).toHaveProperty('endY')
      expect(params).toHaveProperty('duration')
    })

    it('returns empty object for events without specific params', () => {
      const params = eventExecutionParams('npc_stretch', 2000, 800)
      expect(params).toEqual({})
    })
  })
})
