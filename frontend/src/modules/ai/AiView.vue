<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ClipboardCheck, History, Loader2, Plus, Send, ShieldCheck, SlidersHorizontal, Timer } from 'lucide-vue-next'
import { api } from '../../shared/api/client'
import { postSse } from '../../shared/api/sse'
import MarkdownDocument from '../../shared/ui/MarkdownDocument.vue'

type ChatMessage = { role: 'USER' | 'ASSISTANT'; text: string }
type SessionSummary = {
  publicId: string
  scene: string
  status: string
  createdAt: string
  updatedAt: string
  messageCount: number
  lastRole?: string | null
  lastMessage?: string | null
  lastMessageAt?: string | null
}
type ServerMessage = { publicId: string; role: string; content: string; riskLevel: string; model?: string | null; status: string; createdAt: string }

const sceneOptions = [
  { value: 'STUDY', label: '学习' },
  { value: 'FITNESS', label: '身体照顾' },
  { value: 'CAREER', label: '职场' },
  { value: 'EMOTIONAL_SUPPORT', label: '情绪支持' },
]

const suggestionChecks = [
  { icon: Timer, title: '时间', body: '每条建议应该能在 5-60 分钟内开始，超过范围就继续拆小。' },
  { icon: SlidersHorizontal, title: '难度', body: '优先选择 1-3 级难度，不把低精力日安排成高强度日。' },
  { icon: ShieldCheck, title: '边界', body: '健康、情绪和安全相关内容只做一般支持，不替代专业服务。' },
  { icon: ClipboardCheck, title: '确认', body: 'AI 只给草稿，勾选、编辑并确认后才进入正式任务。' },
]

const scene = ref('STUDY')
const session = ref('')
const message = ref('')
const messages = ref<ChatMessage[]>([])
const history = ref<SessionSummary[]>([])
const loadingHistory = ref(false)
const loadingSession = ref(false)
const busy = ref(false)
const error = ref('')
const crisis = ref<{ message: string } | null>(null)
let controller: AbortController | undefined
let openSessionRequest = 0

const sceneNames: Record<string, string> = Object.fromEntries(sceneOptions.map(option => [option.value, option.label]))

async function ensureSession() {
  if (!session.value) session.value = (await api.post<{ publicId: string }>('/ai/sessions', { scene: scene.value })).publicId
}

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

async function openSession(item: SessionSummary) {
  controller?.abort()
  const requestId = ++openSessionRequest
  loadingSession.value = true
  error.value = ''
  crisis.value = null
  try {
    session.value = item.publicId
    scene.value = item.scene
    const loaded = await api.get<ServerMessage[]>(`/ai/sessions/${item.publicId}/messages`)
    if (requestId !== openSessionRequest) return
    messages.value = loaded
      .filter(row => row.role === 'USER' || row.role === 'ASSISTANT')
      .map(row => ({ role: row.role as ChatMessage['role'], text: row.content }))
  } catch {
    if (requestId !== openSessionRequest) return
    error.value = '历史对话暂时无法打开'
  } finally {
    if (requestId === openSessionRequest) loadingSession.value = false
  }
}

function preview(item: SessionSummary) {
  const text = item.lastMessage?.trim()
  if (!text) return '还没有消息'
  return text.length > 64 ? `${text.slice(0, 64)}…` : text
}

function timeLabel(value?: string | null) {
  if (!value) return '刚创建'
  return new Date(value).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

async function send() {
  const text = message.value.trim()
  if (!text || busy.value || loadingSession.value) return
  busy.value = true
  error.value = ''
  controller = new AbortController()
  try {
    await ensureSession()
    messages.value.push({ role: 'USER', text })
    message.value = ''
    let assistant = ''
    await postSse(`/ai/sessions/${session.value}/messages:stream`, { message: text }, event => {
      if (event.name === 'delta') {
        assistant += (event.data as { text?: string }).text ?? ''
        const last = messages.value.at(-1)
        if (last?.role === 'ASSISTANT') last.text = assistant
        else messages.value.push({ role: 'ASSISTANT', text: assistant })
      }
      if (event.name === 'safety') crisis.value = event.data as { message: string }
      if (event.name === 'error') error.value = 'AI 暂时不可用，请稍后再试。'
    }, controller.signal)
    await loadHistory()
  } catch {
    error.value = 'AI 暂时不可用，请稍后再试。'
  } finally {
    busy.value = false
  }
}

function reset() {
  controller?.abort()
  openSessionRequest++
  loadingSession.value = false
  session.value = ''
  messages.value = []
  crisis.value = null
  error.value = ''
}

onMounted(loadHistory)
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="page ai-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">内容由 AI 生成，请核对后使用</p>
        <h1>AI 助手</h1>
      </div>
      <button class="secondary" @click="reset">
        <Plus :size="17" />
        新会话
      </button>
    </header>

    <section v-if="crisis" class="crisis" role="alert">
      <h2>先确保此刻安全</h2>
      <p>{{ crisis.message }}</p>
      <div class="actions">
        <a class="primary button" href="tel:110">联系本地紧急服务</a>
        <button class="secondary">联系可信任的人</button>
      </div>
    </section>

    <template v-else>
      <div class="ai-workspace">
        <div class="chat-column">
          <div class="scene-tabs" aria-label="选择场景">
            <button v-for="option in sceneOptions" :key="option.value" type="button" :aria-pressed="scene === option.value" @click="scene = option.value">
              {{ option.label }}
            </button>
          </div>

          <div class="chat" aria-live="polite">
            <div v-for="(m, i) in messages" :key="i" :class="['message', m.role.toLowerCase()]">
              <span>{{ m.role === 'USER' ? '你' : 'AI' }}</span>
              <div v-if="m.role === 'ASSISTANT'" class="message-body">
                <MarkdownDocument :text="m.text" />
              </div>
              <p v-else class="message-body user-text">{{ m.text }}</p>
            </div>
            <div v-if="!messages.length && !loadingSession" class="empty">
              <h2>从一个具体问题开始</h2>
              <p>例如：把本周目标拆成两项 25 分钟以内的行动，并说明为什么这么安排。</p>
            </div>
            <div v-if="loadingSession" class="session-loading" role="status" aria-live="polite">
              <Loader2 :size="18" class="spinning" />
              正在打开历史对话
            </div>
          </div>

          <p v-if="error" class="error">{{ error }} <RouterLink to="/goals">手动创建任务</RouterLink></p>
          <form class="composer" @submit.prevent="send">
            <label class="sr-only" for="ai-message">输入消息</label>
            <textarea id="ai-message" v-model="message" maxlength="4000" placeholder="描述你想推进的事情" @keydown.ctrl.enter="send"></textarea>
            <button class="primary icon-button" :disabled="busy || loadingSession" title="发送" aria-label="发送">
              <Send :size="18" />
            </button>
          </form>
        </div>

        <aside class="ai-side">
          <section class="history-panel" aria-labelledby="history-title">
            <div class="panel-title">
              <div>
                <p class="eyebrow">历史对话</p>
                <h2 id="history-title">选择后继续聊</h2>
              </div>
              <button class="icon-button" type="button" aria-label="刷新历史对话" @click="loadHistory">
                <Loader2 v-if="loadingHistory" :size="17" class="spinning" />
                <History v-else :size="17" />
              </button>
            </div>
            <div v-if="history.length" class="history-list">
              <button
                v-for="item in history"
                :key="item.publicId"
                type="button"
                class="history-item"
                :aria-pressed="session === item.publicId"
                :disabled="loadingSession && session !== item.publicId"
                @click="openSession(item)"
              >
                <span class="history-meta">
                  <strong>{{ sceneNames[item.scene] ?? item.scene }}</strong>
                  <small>{{ timeLabel(item.lastMessageAt ?? item.updatedAt) }} · {{ item.messageCount }} 条</small>
                </span>
                <span class="history-preview">{{ preview(item) }}</span>
              </button>
            </div>
            <p v-else class="history-empty">{{ loadingHistory ? '正在读取历史…' : '暂无历史对话' }}</p>
          </section>

          <section class="suggestion-panel" aria-labelledby="suggestion-title">
            <p class="eyebrow">建议解释</p>
            <h2 id="suggestion-title">采纳前先看四件事</h2>
            <div class="check-list">
              <article v-for="item in suggestionChecks" :key="item.title">
                <component :is="item.icon" :size="18" />
                <div>
                  <strong>{{ item.title }}</strong>
                  <p>{{ item.body }}</p>
                </div>
              </article>
            </div>
            <div class="example-box">
              <strong>推荐提问方式</strong>
              <p>“我今天只有 20 分钟，精力偏低，请把目标缩成一项可完成任务，并说明耗时、难度和风险。”</p>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </section>
</template>

<style scoped>
.ai-page { max-width: 1120px; }
.ai-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 24px; align-items: start; }
.chat-column { min-width: 0; }
.scene-tabs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius); margin-bottom: 14px; padding: 4px; background: var(--surface-muted); }
.scene-tabs button { min-width: 0; border: 0; border-radius: calc(var(--radius) - 2px); background: transparent; color: var(--muted); padding: 0 8px; }
.scene-tabs button[aria-pressed='true'] { background: var(--surface); color: var(--primary); font-weight: 800; box-shadow: var(--shadow-soft); }
.chat { min-height: 420px; padding: 16px; border: 1px solid var(--border); border-radius: calc(var(--radius) + 4px); background: color-mix(in srgb, var(--surface) 88%, transparent); box-shadow: var(--shadow-soft); }
.message { display: grid; grid-template-columns: 36px 1fr; gap: 10px; padding: 12px 0; }
.message > span { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 11px; background: var(--surface-muted); font-size: 11px; font-weight: 800; }
.message.assistant > span { background: linear-gradient(135deg, var(--primary), var(--accent)); color: white; }
.message-body { margin: 5px 0 0; }
.user-text { white-space: pre-wrap; line-height: 1.7; }
.composer { display: grid; grid-template-columns: 1fr 44px; gap: 10px; padding-top: 16px; }
.composer textarea { min-height: 84px; resize: vertical; border: 1px solid var(--border); border-radius: var(--radius); padding: 12px; background: var(--surface); color: var(--ink); }
.ai-side { display: grid; gap: 14px; }
.history-panel, .suggestion-panel { display: grid; gap: 14px; padding: 16px; border: 1px solid var(--border); border-radius: calc(var(--radius) + 4px); background: color-mix(in srgb, var(--surface) 90%, transparent); box-shadow: var(--shadow-soft); }
.panel-title { display: flex; align-items: start; justify-content: space-between; gap: 12px; }
.panel-title h2 { margin: 0; font-size: 18px; }
.spinning { animation: spin .8s linear infinite; }
.history-list { display: grid; gap: 8px; }
.history-item { min-height: 72px; display: grid; gap: 7px; padding: 12px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); text-align: left; }
.history-item[aria-pressed='true'] { border-color: var(--primary); background: color-mix(in srgb, var(--primary-soft) 72%, var(--surface)); box-shadow: inset 3px 0 0 var(--primary); }
.history-meta { display: grid; gap: 3px; }
.history-meta strong { font-size: 14px; }
.history-meta small { color: var(--muted); font-size: 12px; }
.history-preview { color: var(--muted); font-size: 13px; line-height: 1.45; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.history-empty { margin: 0; color: var(--muted); font-size: 13px; }
.session-loading { display: inline-flex; align-items: center; gap: 8px; margin-top: 12px; color: var(--muted); font-size: 13px; }
.message.assistant .message-body { min-width: 0; }
.suggestion-panel h2 { margin: 0; font-size: 18px; }
.check-list { display: grid; gap: 12px; }
.check-list article { display: grid; grid-template-columns: 22px 1fr; gap: 10px; padding-bottom: 12px; border-bottom: 1px solid var(--surface-muted); }
.check-list article:last-child { border-bottom: 0; padding-bottom: 0; }
.check-list svg { color: var(--primary); margin-top: 2px; }
.check-list p, .example-box p { margin: 4px 0 0; color: var(--muted); line-height: 1.55; font-size: 13px; }
.example-box { padding: 12px; border: 1px solid color-mix(in srgb, var(--primary) 20%, var(--border)); border-left: 3px solid var(--primary); border-radius: var(--radius); background: color-mix(in srgb, var(--primary-soft) 55%, var(--surface)); }
.crisis { border: 2px solid var(--danger); background: color-mix(in srgb, var(--danger) 7%, var(--surface)); padding: 24px; }
.crisis h2 { color: var(--danger); }
.button { display: inline-flex; align-items: center; text-decoration: none; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; }
@media (prefers-reduced-motion: no-preference) {
  .message, .suggestion-panel, .scene-tabs { animation: message-enter var(--motion-medium) ease-out both; }
  .history-panel { animation: message-enter var(--motion-medium) ease-out both; }
  .message.assistant > span { animation: assistant-breathe 2.8s ease-in-out infinite; }
  .composer textarea:focus { box-shadow: 0 0 0 4px color-mix(in srgb, var(--primary) 10%, transparent); }
  .check-list article { transition: transform var(--motion-fast) ease; }
  .check-list article:hover { transform: translateX(3px); }
  .history-item { transition: transform var(--motion-fast) ease, background-color var(--motion-fast) ease, border-color var(--motion-fast) ease; }
  .history-item:hover { transform: translateY(-1px); }
}
.message.assistant {
  align-items: start;
}
.message.assistant > span {
  margin-top: 2px;
}
.message.assistant .message-body {
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: 0 1px 0 rgb(0 0 0 / 2%), var(--shadow-soft);
}
.message.user .message-body {
  padding: 2px 0 0;
}
@keyframes message-enter { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: translateY(0); } }
@keyframes assistant-breathe { 0%, 100% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--primary) 0%, transparent); } 50% { box-shadow: 0 0 0 5px color-mix(in srgb, var(--primary) 14%, transparent); } }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 900px) {
  .ai-workspace { grid-template-columns: 1fr; }
}
@media (max-width: 900px) {
  .ai-side { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .scene-tabs { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .history-item { min-height: 62px; }
}
</style>
