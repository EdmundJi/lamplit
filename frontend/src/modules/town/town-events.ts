import { defineStore } from 'pinia'
import { api } from '../../shared/api/client'

export type TownEventView = {
  publicId: string; kind: string; venue: string; hostName: string
  startsAt: string; endsAt: string | null; dimension: string | null
}
export const useTownEventsStore = defineStore('townEvents', {
  state: () => ({ events: [] as TownEventView[], loading: false, error: '' }),
  actions: {
    async load() {
      if (this.loading) return
      this.loading = true
      this.error = ''
      try {
        const events = await api.get<TownEventView[]>('/town/events')
        this.events = Array.isArray(events) ? events : []
      } catch (error) {
        this.error = (error as Error).message || '暂时没能读到活动公告'
      } finally { this.loading = false }
    },
  },
})
export function eventTitle(kind: string): string {
  return ({ READING: '树荫共读', WALK: '黄昏散步', TEA: '街角茶会', EXERCISE: '一起舒展', READING_CIRCLE: '树荫共读', PARK_WALK: '公园散步', COFFEE_CHAT: '街角闲谈' } as Record<string, string>)[kind] ?? '邻里小聚'
}
