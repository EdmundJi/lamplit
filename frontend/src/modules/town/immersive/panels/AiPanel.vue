<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { History, Plus, Send, Square } from 'lucide-vue-next'
import { api } from '../../../../shared/api/client'
import { postSse, SseRequestError } from '../../../../shared/api/sse'
import MarkdownDocument from '../../../../shared/ui/MarkdownDocument.vue'
import AiNextStep from './AiNextStep.vue'

type ChatMessage = { role: 'USER' | 'ASSISTANT'; text: string }
type SessionSummary = { publicId: string; scene: string; updatedAt: string; messageCount: number; lastMessage?: string | null }

const QUOTA_MESSAGE = 'AI 今天陪聊得有点累了，晚点或明天再来聊聊'

const session = ref('')
const draft = ref('')
const messages = ref<ChatMessage[]>([])
const history = ref<SessionSummary[]>([])
const loadingHistory = ref(false)
const historyError = ref('')
const openingSession = ref(false)
const busy = ref(false)
const error = ref('')
const listElement = ref<HTMLElement | null>(null)
let controller: AbortController | undefined
let operation = 0
let historyRequest = 0
let disposed = false
let recoverUnsent: (() => void) | undefined

function current(id: number) {
  return !disposed && id === operation
}

// Invalidate before aborting: even synchronous abort handlers belong to the old operation.
function invalidate() {
  recoverUnsent?.()
  recoverUnsent = undefined
  operation++
  controller?.abort()
  controller = undefined
  busy.value = false
  openingSession.value = false
  return operation
}

async function loadHistory() {
  const id = ++historyRequest
  loadingHistory.value = true
  historyError.value = ''
  try {
    const loaded = await api.get<SessionSummary[]>('/ai/sessions')
    if (!disposed && id === historyRequest) history.value = loaded
  } catch {
    if (!disposed && id === historyRequest) historyError.value = '历史对话暂时无法加载'
  } finally {
    if (!disposed && id === historyRequest) loadingHistory.value = false
  }
}

async function scrollToBottom(id: number) {
  await nextTick()
  if (current(id) && listElement.value) listElement.value.scrollTop = listElement.value.scrollHeight
}

async function send() {
  const value = draft.value.trim()
  if (!value || busy.value || openingSession.value || disposed) return
  const id = invalidate()
  const requestController = new AbortController()
  controller = requestController
  const active = () => current(id) && !requestController.signal.aborted
  let acceptingEvents = true
  let assistant = ''
  let failed = false
  busy.value = true
  error.value = ''
  draft.value = ''
  const userIndex = messages.value.length
  messages.value.push({ role: 'USER', text: value })
  recoverUnsent = () => {
    if (current(id)) {
      draft.value = value
      messages.value.splice(userIndex)
    }
  }
  void scrollToBottom(id)
  try {
    // Capture the destination locally; a late creation must never replace a new session.
    let targetSession = session.value
    if (!targetSession) {
      const created = await api.post<{ publicId: string }>('/ai/sessions', { scene: 'STUDY' })
      if (!active()) return
      targetSession = created.publicId
      session.value = targetSession
    }
    if (!active()) return
    // From this point the server may have accepted the message; don't roll back on stop.
    recoverUnsent = undefined
    await postSse(`/ai/sessions/${encodeURIComponent(targetSession)}/messages:stream`, { message: value }, event => {
      if (!active() || !acceptingEvents) return
      if (event.name === 'delta') {
        assistant += (event.data as { text?: string }).text ?? ''
        const reply = { role: 'ASSISTANT' as const, text: assistant }
        if (messages.value.length === userIndex + 1) messages.value.push(reply)
        else messages.value[userIndex + 1] = reply
        void scrollToBottom(id)
      } else if (event.name === 'safety') {
        error.value = (event.data as { message?: string }).message || '这段内容需要更谨慎的支持，建议联系可信任的人。'
      } else if (event.name === 'error') {
        failed = true
        error.value = (event.data as { message?: string }).message || 'AI 暂时不可用，请稍后再试'
        acceptingEvents = false
      } else if (event.name === 'done') {
        acceptingEvents = false
      }
    }, requestController.signal)
    if (active()) void loadHistory()
  } catch (caught) {
    if (!active()) return
    if ((caught as { name?: string } | null)?.name !== 'AbortError') {
      failed = true
      error.value = caught instanceof SseRequestError && caught.status === 429
        ? QUOTA_MESSAGE : 'AI 暂时不可用，请稍后再试'
    }
  } finally {
    acceptingEvents = false
    if (active()) {
      recoverUnsent = undefined
      // Preserve a failed, unanswered draft for explicit retry; never auto-resend.
      if (failed && !assistant) {
        draft.value = value
        messages.value.splice(userIndex)
      }
      busy.value = false
      controller = undefined
    }
  }
}

async function openSession(item: SessionSummary, event?: Event) {
  (event?.currentTarget as HTMLElement | null)?.closest('details')?.removeAttribute('open')
  if (disposed) return
  const id = invalidate()
  openingSession.value = true
  error.value = ''
  try {
    const loaded = await api.get<{ role: string; content: string }[]>(`/ai/sessions/${encodeURIComponent(item.publicId)}/messages`)
    if (!current(id)) return
    const nextMessages = loaded
      .filter(row => row.role === 'USER' || row.role === 'ASSISTANT')
      .map(row => ({ role: row.role as ChatMessage['role'], text: row.content }))
    // Commit both together. Failure leaves the previous conversation coherent and usable.
    session.value = item.publicId
    messages.value = nextMessages
    draft.value = ''
    void scrollToBottom(id)
  } catch {
    if (current(id)) error.value = '历史对话暂时无法打开，请重新选择重试'
  } finally {
    if (current(id)) openingSession.value = false
  }
}

function reset() {
  invalidate()
  session.value = ''
  messages.value = []
  draft.value = ''
  error.value = ''
}

function stop() {
  invalidate()
}

function preview(item: SessionSummary) {
  const text = item.lastMessage?.trim()
  if (!text) return '还没有消息'
  return text.length > 28 ? `${text.slice(0, 28)}…` : text
}

onMounted(loadHistory)
onBeforeUnmount(() => {
  disposed = true
  invalidate()
  historyRequest++
})
</script>

<template>
  <section class="world-panel ai-panel">
    <div class="ai-toolbar">
      <button class="icon-button" type="button" aria-label="新会话" title="新会话" @click="reset"><Plus :size="15" /></button>
      <details v-if="history.length" class="history-pick">
        <summary aria-label="历史对话"><History :size="14" />历史（{{ history.length }}）</summary>
        <div>
          <button v-for="item in history" :key="item.publicId" type="button" :aria-pressed="session === item.publicId" @click="openSession(item, $event)">
            {{ preview(item) }}
          </button>
        </div>
      </details>
    </div>

    <p v-if="loadingHistory" role="status">正在加载历史对话…</p>
    <p v-if="historyError" class="error" role="alert">
      {{ historyError }}
      <button class="secondary" type="button" :disabled="loadingHistory" @click="loadHistory">重试加载历史</button>
    </p>
    <p v-if="openingSession" role="status">正在打开对话…</p>

    <div ref="listElement" class="ai-chat" aria-live="polite">
      <div v-if="!messages.length" class="empty">
        <p>把还没理清的想法说给它听，比如“今天精力不太够，帮我把目标缩成一件事”。</p>
      </div>
      <div v-for="(item, index) in messages" :key="index" class="ai-message" :class="item.role.toLowerCase()">
        <span class="ai-role">{{ item.role === 'USER' ? '你' : 'AI' }}</span>
        <MarkdownDocument v-if="item.role === 'ASSISTANT'" :text="item.text" />
        <p v-else class="ai-user-text">{{ item.text }}</p>
      </div>
    </div>

    <AiNextStep v-if="session && messages.some(item => item.role === 'ASSISTANT' && item.text.trim())" :key="session" :session="session" :disabled="busy || openingSession" :can-replace="!draft.trim()" />

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <form class="ai-composer" @submit.prevent="send()">
      <label class="sr-only" for="ai-panel-input">给 AI 助手发消息</label>
      <textarea id="ai-panel-input" v-model="draft" maxlength="2000" rows="2" placeholder="描述你想推进的事情" :disabled="busy || openingSession" @keydown.ctrl.enter="send()"></textarea>
      <button
        class="primary icon-button"
        :type="busy ? 'button' : 'submit'"
        :disabled="openingSession || (!busy && !draft.trim())"
        :aria-label="busy ? '停止生成' : '发送'"
        @click="busy && stop()"
      >
        <Square v-if="busy" :size="14" />
        <Send v-else :size="16" />
      </button>
    </form>

  </section>
</template>

<style scoped>
.world-panel { display: grid; gap: 10px; width: 100%; color: var(--ink); }
.ai-toolbar { display: flex; align-items: center; gap: 8px; }
.history-pick { position: relative; }
.history-pick summary { list-style: none; cursor: pointer; display: inline-flex; align-items: center; gap: 5px; padding: 0 10px; min-height: 30px; border: 1px solid var(--border); border-radius: var(--radius); font-size: 12px; color: var(--muted); }
.history-pick summary::-webkit-details-marker { display: none; }
.history-pick > div { position: absolute; left: 0; top: calc(100% + 4px); z-index: 5; display: grid; gap: 4px; padding: 6px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-raised); box-shadow: var(--shadow-soft); min-width: 220px; max-height: 200px; overflow-y: auto; }
.history-pick button { text-align: left; font-size: 12px; min-height: 28px; padding: 0 8px; border: 0; background: transparent; color: var(--ink); }
.history-pick button:hover, .history-pick button[aria-pressed='true'] { background: var(--primary-soft); }
.ai-chat { min-height: 220px; max-height: 340px; overflow-y: auto; display: grid; gap: 10px; align-content: start; padding: 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }
.ai-message { display: grid; gap: 3px; font-size: 13px; }
.ai-message.user { justify-items: end; }
.ai-role { font-size: 11px; font-weight: 700; color: var(--muted); }
.ai-user-text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.ai-composer { display: grid; grid-template-columns: minmax(0, 1fr) var(--control); gap: 8px; align-items: end; }
.ai-composer textarea { min-height: 44px; max-height: 96px; resize: vertical; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); padding: 8px 10px; font-size: 13px; }
.spinning { animation: ai-spin .8s linear infinite; }
@keyframes ai-spin { to { transform: rotate(360deg); } }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; }
</style>
