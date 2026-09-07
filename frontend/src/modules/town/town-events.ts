import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useAuthStore } from '../auth/auth.store'
import { api } from '../../shared/api/client'

export type TownEventView = {
  publicId: string; kind: string; venue: string; hostName: string
  startsAt: string; endsAt: string | null; dimension: string | null
  phase?: 'UPCOMING' | 'ONGOING' | 'ENDED' | 'CANCELLED'
  response?: 'UNDECIDED' | 'GOING' | 'SKIPPED'
  attendedAt?: string | null; serverTime?: string
}
export const useTownEventsStore = defineStore('townEvents', () => {
  const auth = useAuthStore()
  const events = ref<TownEventView[]>([])
  const loading = ref(false)
  const error = ref('')
  let generation = 0
  watch(() => auth.user?.publicId, () => { generation++; events.value = []; loading.value = false; error.value = '' }, { flush: 'sync' })
  async function load() {
    if (loading.value) return
    const token = ++generation
    loading.value = true; error.value = ''
    try {
      const result = await api.get<TownEventView[]>('/town/events')
      if (token === generation) events.value = Array.isArray(result) ? result : []
    } catch {
      if (token === generation) error.value = '暂时没能读到活动公告，请再试一次。'
    } finally { if (token === generation) loading.value = false }
  }
  return { events, loading, error, load }
})
export function eventTitle(kind: string): string {
  return ({ READING: '树荫共读', WALK: '黄昏散步', TEA: '街角茶会', EXERCISE: '一起舒展', READING_CIRCLE: '树荫共读', PARK_WALK: '公园散步', COFFEE_CHAT: '街角闲谈' } as Record<string, string>)[kind] ?? '邻里小聚'
}
