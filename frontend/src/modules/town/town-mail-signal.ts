import { defineStore } from 'pinia'
import { api } from '../../shared/api/client'

/** Outside the mailbox only fetch a count, never private letter bodies. */
export const useTownMailSignal = defineStore('townMailSignal', {
  state: () => ({ unreadCount: 0, loading: false }),
  actions: {
    async load() {
      if (this.loading) return
      this.loading = true
      try {
        const result = await api.get<{ unreadCount: number }>('/town/letters/unread')
        this.unreadCount = Number.isFinite(result?.unreadCount) ? Math.max(0, result.unreadCount) : 0
      } catch { /* Preserve the last count; the mailbox exposes actionable loading errors. */ }
      finally { this.loading = false }
    },
  },
})
