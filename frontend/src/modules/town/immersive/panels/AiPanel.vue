<script setup lang="ts">
import { inject, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { History, Plus, Send, Square } from 'lucide-vue-next'
import { api } from '../../../../shared/api/client'
import { postSse, SseRequestError } from '../../../../shared/api/sse'
import MarkdownDocument from '../../../../shared/ui/MarkdownDocument.vue'
import { worldBridgeKey } from '../panel.types'
import { openFullPage } from './shared'

type ChatMessage = { role: 'USER' | 'ASSISTANT'; text: string }
type SessionSummary = { publicId: string; scene: string; updatedAt: string; messageCount: number; lastMessage?: string | null }

const FULL_PAGE = '/ai'
const QUOTA_MESSAGE = 'AI 今天陪聊得有点累了，晚点或明天再来聊聊'

const bridge = inject(worldBridgeKey, undefined)

const session = ref('')
const draft = ref('')
const messages = ref<ChatMessage[]>([])
const history = ref<SessionSummary[]>([])
const loadingHistory = ref(false)
const busy = ref(false)
const error = ref('')
const listElement = ref<HTMLElement | null>(null)
let controller: AbortController | undefined

async function loadHistory() {
  loadingHistory.value = true
  try {
    history.value = await api.get<SessionSummary[]>('/ai/sessions')
  } catch {
    error.value = '历史对话暂时无法加载'
  } finally {
    loadingHistory.value = false
  }
}

async function scrollToBottom() {
  await nextTick()
  if (listElement.value) listElement.value.scrollTop = listElement.value.scrollHeight
}

async function ensureSession() {
  if (!session.value) session.value = (await api.post<{ publicId: string }>('/ai/sessions', { scene: 'STUDY' })).publicId
}

async function send(text?: string) {
  const value = (text ?? draft.value).trim()
  if (!value || busy.value) return
  busy.value = true
  error.value = ''
  draft.value = ''
  messages.value.push({ role: 'USER', text: value })
  await scrollToBottom()
  controller = new AbortController()
  try {
    await ensureSession()
    let assistant = ''
    await postSse(`/ai/sessions/${session.value}/messages:stream`, { message: value }, async event => {
      if (event.name === 'delta') {
        const delta = (event.data as { text?: string }).text ?? ''
        assistant += delta
        const last = messages.value.at(-1)
        if (last?.role === 'ASSISTANT') last.text = assistant
        else messages.value.push({ role: 'ASSISTANT', text: assistant })
        await scrollToBottom()
      } else if (event.name === 'safety') {
        error.value = (event.data as { message?: string }).message || '这段内容需要更谨慎的支持，建议联系可信任的人。'
      } else if (event.name === 'error') {
        const failure = event.data as { message?: string }
        error.value = failure.message || 'AI 暂时不可用，请稍后再试'
      }
    }, controller.signal)
    await loadHistory()
  } catch (caught) {
    if (caught instanceof SseRequestError) {
      error.value = caught.status === 429 ? QUOTA_MESSAGE : 'AI 暂时不可用，请稍后再试'
    } else if ((caught as { name?: string }).name !== 'AbortError') {
      error.value = 'AI 暂时不可用，请稍后再试'
    }
  } finally {
    busy.value = false
  }
}

async function openSession(item: SessionSummary) {
  if (busy.value) return
  error.value = ''
  try {
    session.value = item.publicId
    const loaded = await api.get<{ role: string; content: string }[]>(`/ai/sessions/${item.publicId}/messages`)
    messages.value = loaded
      .filter(row => row.role === 'USER' || row.role === 'ASSISTANT')
      .map(row => ({ role: row.role as ChatMessage['role'], text: row.content }))
    await scrollToBottom()
  } catch {
    error.value = '历史对话暂时无法打开'
  }
}

function reset() {
  controller?.abort()
  session.value = ''
  messages.value = []
  error.value = ''
}

/** 只掐断这一次回复，会话和已有消息都留着——区别于「新会话」那种连历史一起清空的重来。 */
function stop() {
  controller?.abort()
}

function preview(item: SessionSummary) {
  const text = item.lastMessage?.trim()
  if (!text) return '还没有消息'
  return text.length > 28 ? `${text.slice(0, 28)}…` : text
}

onMounted(loadHistory)
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="world-panel ai-panel">
    <div class="ai-toolbar">
      <button class="icon-button" type="button" aria-label="新会话" title="新会话" @click="reset"><Plus :size="15" /></button>
      <details v-if="history.length" class="history-pick">
        <summary aria-label="历史对话"><History :size="14" />历史（{{ history.length }}）</summary>
        <div>
          <button v-for="item in history" :key="item.publicId" type="button" :aria-pressed="session === item.publicId" @click="openSession(item)">
            {{ preview(item) }}
          </button>
        </div>
      </details>
    </div>

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

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <form class="ai-composer" @submit.prevent="send()">
      <label class="sr-only" for="ai-panel-input">给 AI 助手发消息</label>
      <textarea id="ai-panel-input" v-model="draft" maxlength="2000" rows="2" placeholder="描述你想推进的事情" :disabled="busy" @keydown.ctrl.enter="send()"></textarea>
      <button
        class="primary icon-button"
        :type="busy ? 'button' : 'submit'"
        :disabled="!busy && !draft.trim()"
        :aria-label="busy ? '停止生成' : '发送'"
        @click="busy && stop()"
      >
        <Square v-if="busy" :size="14" />
        <Send v-else :size="16" />
      </button>
    </form>

    <footer class="panel-footer">
      <button class="secondary" type="button" @click="openFullPage(bridge, FULL_PAGE)">打开完整页面</button>
    </footer>
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
.panel-footer { display: flex; justify-content: flex-end; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; }
</style>
