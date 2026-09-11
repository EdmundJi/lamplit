<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, ClipboardCheck, History, Loader2, Plus, Send, ShieldCheck, SlidersHorizontal, Sparkles, Timer } from 'lucide-vue-next'
import { disclose as vDisclose } from '../../shared/ui/interaction/disclose'
import SegmentedControl from '../../shared/ui/interaction/SegmentedControl.vue'
import SnapSlider from '../../shared/ui/interaction/SnapSlider.vue'
import { api } from '../../shared/api/client'
import { postSse } from '../../shared/api/sse'
import MarkdownDocument from '../../shared/ui/MarkdownDocument.vue'
import EmptyState from '../../shared/ui/EmptyState.vue'
import { pickThinkingMessage } from './ai-thinking'
import { saveGoalDraft, type GoalDraft } from './goal-draft'

type ChatMessage = { role: 'USER' | 'ASSISTANT'; text: string; model?: string }
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
const router = useRouter()
const session = ref('')
const message = ref('')
const messages = ref<ChatMessage[]>([])
const history = ref<SessionSummary[]>([])
const loadingHistory = ref(false)
const loadingSession = ref(false)
const busy = ref(false)
const thinking = ref(false)
const thinkingMessage = ref('')
const error = ref('')
const crisis = ref<{ message: string } | null>(null)
const goalDraft = ref<GoalDraft | null>(null)
const generatingGoal = ref(false)
const goalDraftError = ref('')
let controller: AbortController | undefined
let openSessionRequest = 0
let thinkingTimer: ReturnType<typeof setInterval> | undefined

const sceneNames: Record<string, string> = Object.fromEntries(sceneOptions.map(option => [option.value, option.label]))
const dimensionOptions = [
  { code: 'KNOWLEDGE', name: '智力' },
  { code: 'HEALTH', name: '体力' },
  { code: 'CAREER', name: '执行力' },
  { code: 'RELATIONSHIP', name: '社交力' },
  { code: 'WELLBEING', name: '心境力' },
]
const goalDraftJson = computed(() => goalDraft.value ? JSON.stringify({
  title: goalDraft.value.title,
  description: goalDraft.value.description,
  dimensionCode: goalDraft.value.dimensionCode,
  durationDays: goalDraft.value.durationDays,
  weeklyFocus: goalDraft.value.weeklyFocus,
  starterTasks: goalDraft.value.starterTasks,
}, null, 2) : '')

function stopThinking() {
  thinking.value = false
  clearInterval(thinkingTimer)
  thinkingTimer = undefined
}

function startThinking() {
  stopThinking()
  thinkingMessage.value = pickThinkingMessage()
  thinking.value = true
  thinkingTimer = setInterval(() => {
    thinkingMessage.value = pickThinkingMessage(thinkingMessage.value)
  }, 2400)
}

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
  stopThinking()
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
      .map(row => ({
        role: row.role as ChatMessage['role'],
        text: row.content,
        model: row.model || undefined,
      }))
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
  startThinking()
  try {
    await ensureSession()
    messages.value.push({ role: 'USER', text })
    message.value = ''
    let assistant = ''
    let assistantModel: string | undefined
    await postSse(`/ai/sessions/${session.value}/messages:stream`, { message: text }, event => {
      if (event.name === 'meta') {
        const meta = event.data as { model?: string | null }
        assistantModel = meta.model || undefined
      }
      if (event.name === 'delta') {
        const delta = (event.data as { text?: string }).text ?? ''
        if (delta) stopThinking()
        assistant += delta
        const last = messages.value.at(-1)
        if (last?.role === 'ASSISTANT') {
          last.text = assistant
          last.model = assistantModel
        } else {
          messages.value.push({ role: 'ASSISTANT', text: assistant, model: assistantModel })
        }
      }
      if (event.name === 'safety') {
        stopThinking()
        crisis.value = event.data as { message: string }
      }
      if (event.name === 'error') {
        stopThinking()
        const failure = event.data as { message?: string }
        error.value = failure.message || 'AI 暂时不可用，请稍后再试。'
      }
    }, controller.signal)
    await loadHistory()
  } catch {
    error.value = 'AI 暂时不可用，请稍后再试。'
  } finally {
    stopThinking()
    busy.value = false
  }
}

async function generateGoalTemplate() {
  if (!session.value || !messages.value.length || generatingGoal.value) return
  generatingGoal.value = true
  goalDraftError.value = ''
  try {
    goalDraft.value = await api.post<GoalDraft>('/ai/goal-template', { sessionPublicId: session.value })
  } catch {
    goalDraftError.value = '目标草案暂时无法生成，请继续聊几句后重试。'
  } finally {
    generatingGoal.value = false
  }
}

async function fillGoalForm() {
  if (!goalDraft.value) return
  saveGoalDraft(goalDraft.value)
  await router.push('/goals?source=ai')
}

function reset() {
  controller?.abort()
  stopThinking()
  openSessionRequest++
  loadingSession.value = false
  session.value = ''
  messages.value = []
  crisis.value = null
  goalDraft.value = null
  goalDraftError.value = ''
  error.value = ''
}

onMounted(loadHistory)
onBeforeUnmount(() => {
  controller?.abort()
  stopThinking()
})
</script>

<template>
  <section class="page page--talk ai-page">
    <header class="page-head">
      <h1>AI 助手</h1>
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
          <SegmentedControl v-model="scene" :options="sceneOptions" label="选择场景" class="scene-tabs" />

          <div class="chat" aria-live="polite">
            <div v-for="(m, i) in messages" :key="i" :class="['message', m.role.toLowerCase()]">
              <span>{{ m.role === 'USER' ? '你' : 'AI' }}</span>
              <div v-if="m.role === 'ASSISTANT'" class="message-body">
                <MarkdownDocument :text="m.text" />
                <small v-if="m.model" class="message-model">{{ m.model }}</small>
              </div>
              <p v-else class="message-body user-text">{{ m.text }}</p>
            </div>
            <div v-if="thinking" class="message assistant thinking-message" role="status" aria-live="polite" aria-label="AI 正在思考">
              <span>AI</span>
              <div class="message-body thinking-body" aria-hidden="true">
                <span class="thinking-dots">
                  <i></i><i></i><i></i>
                </span>
                <Transition name="thinking-copy" mode="out-in">
                  <p :key="thinkingMessage">{{ thinkingMessage }}</p>
                </Transition>
              </div>
            </div>
            <EmptyState
              v-if="!messages.length && !loadingSession && !thinking"
              sprite="dog_labrador_brown_idle_1"
              title="从一个具体问题开始"
              description="例如：把本周目标拆成两项 25 分钟以内的行动，并说明为什么这么安排。"
            />
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
          <p class="ai-disclaimer">内容由 AI 生成，请核对后使用</p>
        </div>

        <aside class="ai-side">
          <section class="goal-draft-panel" aria-labelledby="goal-draft-title">
            <h2 id="goal-draft-title" class="section-title">任务推荐</h2>
            <button v-if="!goalDraft" class="secondary generate-goal" type="button" :disabled="!session || !messages.length || generatingGoal" @click="generateGoalTemplate">
              <Loader2 v-if="generatingGoal" :size="17" class="spinning" />
              <Sparkles v-else :size="17" />
              {{ generatingGoal ? '正在生成' : '根据对话生成' }}
            </button>
            <p v-if="goalDraftError" class="draft-error" role="alert">{{ goalDraftError }}</p>
            <form v-if="goalDraft" class="goal-draft-form" @submit.prevent="fillGoalForm">
              <label class="field"><span>目标名称</span><input v-model="goalDraft.title" maxlength="160" required /></label>
              <label class="field"><span>完成标准</span><textarea v-model="goalDraft.description" maxlength="1000" required></textarea></label>
              <div class="draft-split">
                <label class="field"><span>成长属性</span><select v-model="goalDraft.dimensionCode"><option v-for="item in dimensionOptions" :key="item.code" :value="item.code">{{ item.name }}</option></select></label>
                <label class="field"><span>持续天数 · {{ goalDraft.durationDays }}</span><SnapSlider v-model="goalDraft.durationDays" :min="14" :max="84" :step="7" :value-text="`${goalDraft.durationDays} 天`" /></label>
              </div>
              <label class="field"><span>每周重点</span><textarea v-model="goalDraft.weeklyFocus" maxlength="300" required></textarea></label>
              <div class="starter-tasks">
                <strong>起步任务</strong>
                <div v-for="(task, index) in goalDraft.starterTasks" :key="index" class="starter-task">
                  <input v-model="task.title" :aria-label="`起步任务 ${index + 1} 名称`" maxlength="160" required />
                  <SnapSlider v-model="task.estimatedMinutes" :aria-label="`起步任务 ${index + 1} 分钟`" :min="5" :max="60" :step="5" :value-text="`${task.estimatedMinutes} 分钟`" />
                  <SnapSlider v-model="task.difficulty" :aria-label="`起步任务 ${index + 1} 难度`" :min="1" :max="3" :step="1" :value-text="`难度 ${task.difficulty}`" />
                </div>
              </div>
              <details v-disclose class="json-preview"><summary>查看 JSON</summary><pre>{{ goalDraftJson }}</pre></details>
              <div class="draft-actions"><button class="secondary" type="button" :disabled="generatingGoal" @click="generateGoalTemplate">重新生成</button><button class="secondary" type="submit">一键填入<ArrowRight :size="16" /></button></div>
            </form>
            <p v-else-if="!messages.length" class="draft-empty">完成一段对话后即可生成。</p>
          </section>

          <section class="history-panel" aria-labelledby="history-title">
            <div class="panel-title">
              <h2 id="history-title" class="section-title">历史对话</h2>
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
            <h2 id="suggestion-title" class="section-title">建议解释</h2>
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
.scene-tabs { margin-bottom: 14px; }
.chat { min-height: 420px; padding: 16px; border: 1px solid var(--border); border-radius: calc(var(--radius) + 4px); background: color-mix(in srgb, var(--surface) 88%, transparent); }
.message { display: grid; grid-template-columns: 36px 1fr; gap: 10px; padding: 12px 0; }
.message > span { width: 32px; height: 32px; display: grid; place-items: center; border-radius: var(--radius-card); background: var(--surface-muted); font-size: 11px; font-weight: 800; }
.message.assistant > span { background: linear-gradient(135deg, var(--primary), var(--accent)); color: white; }
.message-body { margin: 5px 0 0; }
.user-text { white-space: pre-wrap; line-height: 1.7; }
.composer { display: grid; grid-template-columns: minmax(0, 1fr) var(--control); align-items: end; gap: 10px; padding-top: 16px; }
.composer textarea { min-height: 84px; resize: vertical; border: 1px solid var(--border); border-radius: var(--radius); padding: 12px; background: var(--surface); color: var(--ink); }
.composer .icon-button { align-self: end; }
.ai-disclaimer { margin: 8px 0 0; color: var(--muted); font-size: 12px; }
.ai-side { display: grid; }
.history-panel, .suggestion-panel, .goal-draft-panel { display: grid; gap: 14px; }
.ai-side > section:first-child .section-title, .ai-side > section:first-child .panel-title { margin-top: 0; }
.panel-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 32px 0 12px; padding-bottom: 8px; border-bottom: 1px solid var(--border); }
.panel-title .section-title { flex: 1; margin: 0; padding: 0; border: 0; }
.spinning { animation: spin .8s linear infinite; }
.history-list { display: grid; gap: 8px; }
.history-item { min-height: 72px; display: grid; gap: 7px; padding: 12px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); text-align: left; }
.history-item[aria-pressed='true'] { border-color: var(--primary); background: color-mix(in srgb, var(--primary-soft) 72%, var(--surface)); box-shadow: inset 3px 0 0 var(--primary); }
.history-meta { display: grid; gap: 3px; }
.history-meta strong { font-size: 14px; }
.history-meta small { color: var(--muted); font-size: 12px; }
.history-preview { color: var(--muted); font-size: 13px; line-height: 1.45; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.history-empty { margin: 0; color: var(--muted); font-size: 13px; }
.generate-goal { width: 100%; }
.draft-empty, .draft-error { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.5; }
.draft-error { color: var(--danger); }
.goal-draft-form { display: grid; gap: 11px; }
.goal-draft-form .field { gap: 5px; }
.goal-draft-form .field span { font-size: 12px; }
.goal-draft-form .field textarea { min-height: 74px; }
.draft-split { display: grid; grid-template-columns: minmax(0, 1fr) 92px; gap: 8px; }
.starter-tasks { display: grid; gap: 7px; }
.starter-tasks > strong { font-size: 12px; }
.starter-task { display: grid; grid-template-columns: minmax(0, 1fr) 62px 82px; gap: 6px; }
.starter-task input, .starter-task select { min-width: 0; min-height: var(--control); border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); padding: 7px; font-size: 11px; }
.json-preview { border-top: 1px solid var(--border); padding-top: 9px; }
.json-preview summary { color: var(--primary); cursor: pointer; font-size: 12px; font-weight: 700; }
.json-preview pre { max-height: 220px; overflow: auto; margin: 9px 0 0; padding: 10px; border-radius: var(--radius); background: var(--surface-muted); color: var(--ink); font: 11px/1.55 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.draft-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.session-loading { display: inline-flex; align-items: center; gap: 8px; margin-top: 12px; color: var(--muted); font-size: 13px; }
.message.assistant .message-body { min-width: 0; }
.thinking-message { min-height: 66px; }
.message.assistant .thinking-body { min-height: 54px; display: flex; align-items: center; gap: 12px; padding-block: 11px; color: var(--muted); }
.thinking-body p { min-width: 0; margin: 0; line-height: 1.5; overflow-wrap: anywhere; }
.thinking-dots { width: 42px; height: 28px; flex: none; display: flex; align-items: center; justify-content: center; gap: 5px; border: 1px solid color-mix(in srgb, var(--primary) 18%, var(--border)); border-radius: 999px; background: color-mix(in srgb, var(--primary-soft) 42%, var(--surface)); }
.thinking-dots i { width: 6px; height: 6px; border-radius: 50%; background: var(--primary); }
.thinking-dots i:nth-child(2) { background: var(--amber); }
.thinking-dots i:nth-child(3) { background: var(--accent); }
.thinking-copy-enter-active, .thinking-copy-leave-active { transition: opacity var(--motion-fast) var(--ease), transform var(--motion-fast) var(--ease); }
.thinking-copy-enter-from { opacity: 0; transform: translateY(3px); }
.thinking-copy-leave-to { opacity: 0; transform: translateY(-3px); }
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
  .message { animation: message-enter var(--motion-medium) var(--ease) both; }
  .thinking-dots i { animation: thinking-dot 1.15s var(--ease) infinite; }
  .thinking-dots i:nth-child(2) { animation-delay: 140ms; }
  .thinking-dots i:nth-child(3) { animation-delay: 280ms; }
  .composer textarea:focus { box-shadow: inset 0 0 0 2px var(--primary); }
  .history-item { transition: background-color var(--motion-fast) var(--ease), border-color var(--motion-fast) var(--ease); }
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
}
.message.user .message-body {
  padding: 2px 0 0;
}
.message-model {
  display: block;
  margin-top: 9px;
  color: var(--muted);
  font-size: 11px;
}
@keyframes message-enter { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@keyframes thinking-dot { 0%, 60%, 100% { opacity: .42; transform: translateY(0); } 30% { opacity: 1; transform: translateY(-3px); } }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 900px) {
  .ai-workspace { grid-template-columns: 1fr; }
  .ai-side { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .history-item { min-height: 62px; }
  .starter-task { grid-template-columns: 1fr 62px; }
  .starter-task select { grid-column: 1 / -1; }
}
.ai-page { max-width: 1264px; }
.ai-workspace { gap: 24px; grid-template-columns: minmax(0, 1fr) 300px; }
.chat-column { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-panel); padding: 20px; }
.chat { border: 0; padding: 10px 0; min-height: 360px; background: transparent; }
.scene-tabs { border: 0; }
.message > span { border-radius: 50%; }
.composer { border-top: 1px solid var(--border); }
.composer textarea { background: var(--surface-muted); border-radius: var(--radius); }
@media (max-width: 900px) { .ai-workspace { grid-template-columns: minmax(0,1fr); } .chat-column { padding: 16px; } }
</style>
