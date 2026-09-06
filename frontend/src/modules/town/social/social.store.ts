import { computed, ref } from 'vue'
import { api } from '../../../shared/api/client'
import { CONFIDANT_MAX_LENGTH, type TownLetterInbox, type TownLetter } from './social.types'

/** Per-mailbox memory, deliberately not a singleton or persisted Pinia store. */
export function useTownSocialStore() {
  const letters = ref<TownLetter[]>([])
  const unreadCount = ref(0)
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')
  const reading = ref(new Set<string>())
  const readErrors = ref<Record<string, string>>({})
  const draft = ref('')
  const sending = ref(false)
  const sendError = ref('')
  const feedback = ref('')
  let disposed = false
  const canSend = computed(() => !sending.value && !!draft.value.trim() && draft.value.trim().length <= CONFIDANT_MAX_LENGTH)

  async function refresh(): Promise<void> {
    // Serialize reads against inbox snapshots so a stale GET cannot undo an acknowledged read.
    if (disposed || loading.value || reading.value.size) return
    loading.value = true
    loadError.value = ''
    try {
      const inbox = await api.get<TownLetterInbox>('/town/letters')
      if (disposed) return
      letters.value = inbox.letters
      unreadCount.value = inbox.unreadCount
      loaded.value = true
      readErrors.value = {}
    } catch {
      if (!disposed) loadError.value = '暂时无法收取信件，请重试。'
    } finally {
      if (!disposed) loading.value = false
    }
  }

  async function markRead(publicId: string): Promise<void> {
    const letter = letters.value.find(item => item.publicId === publicId)
    if (disposed || loading.value || !letter || letter.readAt || reading.value.has(publicId)) return
    reading.value.add(publicId)
    delete readErrors.value[publicId]
    try {
      await api.post<void>(`/town/letters/${encodeURIComponent(publicId)}/read`)
      if (disposed) return
      letter.readAt = new Date().toISOString()
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    } catch {
      if (!disposed) readErrors.value[publicId] = '已读状态未保存，请重试。'
    } finally {
      if (!disposed) reading.value.delete(publicId)
    }
  }

  async function send(): Promise<void> {
    if (disposed || sending.value) return
    sendError.value = ''
    feedback.value = ''
    const message = draft.value.trim()
    if (!message || message.length > CONFIDANT_MAX_LENGTH) {
      sendError.value = `请写下 1–${CONFIDANT_MAX_LENGTH} 字再寄出。`
      return
    }
    sending.value = true
    try {
      await api.post<void>('/town/confidant', { message })
      if (disposed) return
      draft.value = ''
      feedback.value = '信已寄出。最早隔天由邮递员送来回信，届时请再收取信件。'
    } catch {
      // Never echo server errors: they may contain submitted private text.
      if (!disposed) sendError.value = '暂未确认寄出，草稿已保留。请稍后再试。'
    } finally {
      if (!disposed) sending.value = false
    }
  }

  function dispose() {
    disposed = true
    letters.value = []
    draft.value = ''
    unreadCount.value = 0
    readErrors.value = {}
    reading.value.clear()
    loadError.value = sendError.value = feedback.value = ''
    loading.value = sending.value = loaded.value = false
  }

  return { letters, unreadCount, loaded, loading, loadError, reading, readErrors,
    draft, sending, sendError, feedback, canSend, refresh, markRead, send, dispose }
}
