import { defineStore } from 'pinia'
import { router } from '../../app/router'
import { api } from '../../shared/api/client'
import { postSse } from '../../shared/api/sse'
import { notifyDataChanged } from '../../shared/data-sync'
import { encouragement, type EncouragementMoment } from '../../shared/encouragement'
import { randomUUID } from '../../shared/uuid'

/** The two NPCs the town supports today. The path segment is the uppercase code itself. */
export type NpcCode = 'GUIDE' | 'POSTMAN'

export type NpcOption = { label: string }

export type NpcActionType =
  | 'START_TASK'
  | 'COMPLETE_TASK'
  | 'DEFER_TASK'
  | 'SKIP_TASK'
  | 'OPEN_TODAY'
  | 'OPEN_GOALS'
  | 'OPEN_AI'
  | 'OPEN_FRIENDS'

export type NpcAction = {
  type: NpcActionType
  scheduleId?: string
  label: string
  taskTitle?: string
}

export type NpcMessageStatus = 'COMPLETED' | 'BLOCKED'

export type NpcMessage = {
  publicId: string
  role: 'USER' | 'ASSISTANT'
  content: string
  options: NpcOption[]
  actions: NpcAction[]
  status: NpcMessageStatus
  createdAt: string
  /** True while an ASSISTANT message is still streaming and has no final status yet. */
  pending?: boolean
  /** True once a `safety` event (or a BLOCKED status) landed on this message. */
  blocked?: boolean
}

export type NpcReflection = {
  publicId: string
  localDate: string
  greeting: string
  insights: string[]
}

export type NpcActionResult = {
  ok: boolean
  message: string
  scheduleStatus?: string
}

type ServerMessage = {
  publicId: string
  role: 'USER' | 'ASSISTANT'
  content: string
  options?: NpcOption[]
  actions?: NpcAction[]
  status: NpcMessageStatus
  createdAt: string
}

type NpcChatState = {
  messages: NpcMessage[]
  loading: boolean
  sending: boolean
  streaming: boolean
  error: string
  revision: number
  draft: string
  leavingReason: string | null
  interruptPending: boolean
}

const QUOTA_MESSAGE = '小助今天说累了，明天再聊'

const TASK_EVENT_TYPES: Partial<Record<NpcActionType, string>> = {
  START_TASK: 'STARTED',
  COMPLETE_TASK: 'COMPLETED',
  DEFER_TASK: 'DEFERRED',
  SKIP_TASK: 'SKIPPED',
}

const ACTION_MOMENTS: Partial<Record<NpcActionType, EncouragementMoment>> = {
  START_TASK: 'taskStarted',
  COMPLETE_TASK: 'taskCompleted',
  DEFER_TASK: 'taskDeferred',
  SKIP_TASK: 'taskSkipped',
}

const NAV_PATHS: Partial<Record<NpcActionType, string>> = {
  OPEN_TODAY: '/today',
  OPEN_GOALS: '/goals',
  OPEN_AI: '/ai',
  OPEN_FRIENDS: '/friends',
}

/** Streams are keyed by store state to isolate separate Pinia instances. */
const controllers = new WeakMap<NpcChatState, AbortController>()

function emptyState(): NpcChatState {
  return { messages: [], loading: false, sending: false, streaming: false, error: '', revision: 0, draft: '', leavingReason: null, interruptPending: false }
}

function normalizeServerMessage(row: ServerMessage): NpcMessage {
  return {
    publicId: row.publicId,
    role: row.role,
    content: row.content,
    options: row.options ?? [],
    actions: row.actions ?? [],
    status: row.status,
    createdAt: row.createdAt,
    blocked: row.status === 'BLOCKED',
  }
}

function isQuotaCode(code?: string) {
  if (!code) return false
  const normalized = code.toUpperCase()
  return normalized.includes('QUOTA') || normalized.includes('RATE_LIMIT') || normalized.includes('429') || normalized === 'TOO_MANY_REQUESTS'
}

/** Mirrors TodayView's default deferred time: tomorrow, same local minute. */
function localDateTime(value: number): string {
  const date = new Date(value - new Date(value).getTimezoneOffset() * 60000)
  return date.toISOString().slice(0, 16)
}

function defaultDeferredStartAt(): string {
  return new Date(localDateTime(Date.now() + 86400000)).toISOString()
}

export const useNpcChatStore = defineStore('npc-chat', {
  state: () => ({
    byNpc: { GUIDE: emptyState(), POSTMAN: emptyState() } as Record<NpcCode, NpcChatState>,
    reflection: null as NpcReflection | null,
    reflectionLoaded: false,
  }),
  actions: {
    async history(npc: NpcCode) {
      const state = this.byNpc[npc]
      this.abort(npc)
      const revision = state.revision
      state.loading = true
      state.error = ''
      try {
        const rows = await api.get<ServerMessage[]>(`/town/npc/${npc}/messages`)
        if (state.revision === revision) state.messages = rows.map(normalizeServerMessage)
      } catch {
        if (state.revision === revision) state.error = '对话记录暂时无法加载'
      } finally {
        if (state.revision === revision) state.loading = false
      }
    },

    async send(npc: NpcCode, text: string) {
      const trimmed = text.trim()
      const state = this.byNpc[npc]
      if (!trimmed || state.sending || state.leavingReason !== null) return
      const revision = ++state.revision
      state.loading = false

      state.error = ''
      state.sending = true
      state.streaming = true
      state.messages.push({
        publicId: `local-${randomUUID()}`,
        role: 'USER',
        content: trimmed,
        options: [],
        actions: [],
        status: 'COMPLETED',
        createdAt: new Date().toISOString(),
      })
      const assistant: NpcMessage = {
        publicId: '',
        role: 'ASSISTANT',
        content: '',
        options: [],
        actions: [],
        status: 'COMPLETED',
        createdAt: new Date().toISOString(),
        pending: true,
      }
      state.messages.push(assistant)
      // Work through the reactive proxy: mutating the raw object would not notify Vue.
      const live = state.messages[state.messages.length - 1] as NpcMessage

      const controller = new AbortController()
      controllers.set(state, controller)
      let terminal = false
      const current = () => state.revision === revision && !controller.signal.aborted
      try {
        await postSse(`/town/npc/${npc}/chat:stream`, { message: trimmed }, event => {
          if (!current() || terminal) return
          if (event.name === 'meta') {
            const meta = event.data as { messagePublicId?: string }
            if (meta.messagePublicId) live.publicId = meta.messagePublicId
          } else if (event.name === 'delta') {
            const delta = (event.data as { text?: string }).text ?? ''
            live.content += delta
          } else if (event.name === 'safety') {
            const safety = event.data as { message?: string }
            live.content = safety.message ?? live.content
            live.blocked = true
            live.status = 'BLOCKED'
          } else if (event.name === 'done') {
            const done = event.data as { messagePublicId?: string; status?: NpcMessageStatus; options?: NpcOption[]; actions?: NpcAction[]; control?: unknown }
            if (done.messagePublicId) live.publicId = done.messagePublicId
            if (done.status) { live.status = done.status; live.blocked = done.status === 'BLOCKED' }
            live.options = done.options ?? []
            live.actions = done.actions ?? []
            live.pending = false
            terminal = true
            state.sending = false
            state.streaming = false
            const control = done.control as { type?: unknown; reason?: unknown } | null | undefined
            if (control?.type === '/interrupt' && typeof control.reason === 'string' && control.reason.trim()) {
              // Only structured control on this live done is executable. Never inspect prose/history.
              live.options = []
              live.actions = []
              state.leavingReason = control.reason.replaceAll('/interrupt', '').trim() || '这次先聊到这里，下次再见。'
              state.interruptPending = true
            }
          } else if (event.name === 'error') {
            const failure = event.data as { code?: string; message?: string }
            state.error = isQuotaCode(failure.code) ? QUOTA_MESSAGE : (failure.message || '对话暂时不可用，请稍后再试')
            live.pending = false
            terminal = true
          }
        }, controller.signal)
      } catch (caught) {
        if (current() && !terminal && (caught as { name?: string }).name !== 'AbortError') {
          const request = caught as { status?: number; code?: string }
          state.error = request.status === 429 || isQuotaCode(request.code) ? QUOTA_MESSAGE : '对话暂时不可用，请稍后再试'
        }
      } finally {
        if (current()) {
          live.pending = false
          state.sending = false
          state.streaming = false
          controllers.delete(state)
        }
      }
    },

    abort(npc: NpcCode) {
      const state = this.byNpc[npc]
      ++state.revision
      controllers.get(state)?.abort()
      controllers.delete(state)
      state.loading = false
      state.leavingReason = null
      state.interruptPending = false
      state.sending = false
      state.streaming = false
      const last = state.messages.at(-1)
      if (last?.pending) last.pending = false
    },

    /** Consume before emitting to a parent that may synchronously close the dialogue. */
    consumeInterrupt(npc: NpcCode): string | null {
      const state = this.byNpc[npc]
      if (!state.interruptPending) return null
      state.interruptPending = false
      return state.leavingReason
    },

    async runAction(action: NpcAction, extra: { deferredStartAt?: string; completionRatio?: number } = {}): Promise<NpcActionResult> {
      const navPath = NAV_PATHS[action.type]
      if (navPath) {
        await router.push(navPath)
        return { ok: true, message: action.label || '已跳转' }
      }

      const eventType = TASK_EVENT_TYPES[action.type]
      if (eventType && action.scheduleId) {
        const payload: Record<string, unknown> = { eventType }
        if (action.type === 'DEFER_TASK') payload.deferredStartAt = extra.deferredStartAt ?? defaultDeferredStartAt()
        if (action.type === 'COMPLETE_TASK' && extra.completionRatio !== undefined) payload.completionRatio = extra.completionRatio
        try {
          const result = await api.post<{ scheduleStatus?: string }>(`/task-schedules/${action.scheduleId}/events`, payload, { 'Idempotency-Key': randomUUID() })
          notifyDataChanged(['tasks', 'today'])
          const moment = ACTION_MOMENTS[action.type]
          return { ok: true, message: moment ? encouragement(moment) : '已记录', scheduleStatus: result.scheduleStatus }
        } catch {
          return { ok: false, message: '操作未成功，请重试' }
        }
      }

      return { ok: false, message: '暂不支持这个操作' }
    },

    async loadReflection() {
      if (this.reflectionLoaded) return this.reflection
      try {
        this.reflection = await api.get<NpcReflection>('/town/reflection/latest')
      } catch {
        // The endpoint is still "假定" (tentative): tolerate 404 and any other failure alike.
        this.reflection = null
      } finally {
        this.reflectionLoaded = true
      }
      return this.reflection
    },
  },
})
