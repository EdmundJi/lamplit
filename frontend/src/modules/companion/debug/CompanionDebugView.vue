<script setup lang="ts">
/**
 * `/town/debug` - the "先跑给自己看" god view from docs/01-requirements.md. Development-only: a
 * standalone route outside UserLayout (so it never affects /town's own transition or nav), meant
 * to be hidden or deleted wholesale before anything ships. It reads the exact same
 * `GET /town/companion` the normal page already calls (see companion.api.ts) - nothing here can
 * see another user's world, and nothing here writes anything back. Every number this page shows
 * is the authoritative one the simulation is actually using; there is no separate debug-only
 * computation to drift out of sync with it.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { companionApi } from '../companion.api'
import type { Memory } from '../companion.types'
import type { DebugConversation, DebugResidentState, DebugWorld } from './companion-debug.types'
import { backoffRemainingSeconds, memoriesByOwner, personalityDrift, relationshipMatrix, sharedMemoryTopics } from './companion-debug.presentation'

const RESIDENT_ORDER = ['owner', 'student', 'artist', 'gardener']

const world = ref<DebugWorld | null>(null)
const loading = ref(false)
const error = ref('')
const lastFetchedAt = ref<number | null>(null)
const autoRefresh = ref(true)
const now = ref(Date.now())

async function load() {
  loading.value = true
  try {
    const snapshot = await companionApi.load()
    world.value = (snapshot.world as DebugWorld) ?? null
    error.value = snapshot.world ? '' : '还没有加入这条小街 - 先去 /town 搬进来，再回这里看内心。'
  } catch (caught) {
    error.value = (caught as Error)?.message || '拉取失败，稍后重试。'
  } finally {
    loading.value = false
    lastFetchedAt.value = Date.now()
  }
}

let ticker: ReturnType<typeof setInterval> | undefined
let poller: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  void load()
  ticker = setInterval(() => { now.value = Date.now() }, 1000)
  poller = setInterval(() => { if (autoRefresh.value && !document.hidden) void load() }, 4000)
})
onBeforeUnmount(() => { clearInterval(ticker); clearInterval(poller) })

const residentIds = computed(() => RESIDENT_ORDER.filter(id => world.value?.residentStates?.some(state => state.id === id)))
const allActorIds = computed(() => world.value ? [...residentIds.value, 'self'] : [])
const statesById = computed(() => new Map((world.value?.residentStates || []).map(state => [state.id, state as DebugResidentState])))
const nameOf = (id: string) => id === 'self' ? world.value?.avatar.name || '你的小人' : world.value?.residents.find(actor => actor.id === id)?.name || id
const roleOf = (id: string) => id === 'self' ? '你的小人 · 第五个居民' : world.value?.residents.find(actor => actor.id === id)?.role || ''

const locationsById = computed(() => new Map((world.value?.locations || []).map(loc => [loc.id, loc])))
const positionsById = computed(() => new Map((world.value?.positions || []).map(pos => [pos.id, pos])))
const placeNames: Record<string, string> = { street: '小街', cafe: '咖啡馆', garden: '花园' }
function placeLabel(placeId?: string | null): string {
  if (!placeId) return '—'
  const location = locationsById.value.get(placeId)
  if (!location) return placeId
  if (location.kind === 'home') return `${location.ownerId ? nameOf(location.ownerId) : '？'}的家`
  return placeNames[location.kind] || location.kind
}
function positionLabel(positionId?: string | null): string {
  if (!positionId) return '—'
  const position = positionsById.value.get(positionId)
  if (!position) return positionId
  const owner = position.ownerId ? ` · 归${nameOf(position.ownerId)}专属` : ''
  return `${positionId}（${position.kind}${owner}）`
}

// 人格与漂移
const personalityRows = computed(() => residentIds.value.map(id => {
  const state = statesById.value.get(id)
  return state ? { id, drift: personalityDrift(state) } : null
}).filter((row): row is { id: string; drift: ReturnType<typeof personalityDrift> } => row !== null))

// 记忆
const memoryGroups = computed(() => world.value ? memoriesByOwner(world.value.memories) : new Map<string, Memory[]>())
const sharedTopics = computed(() => world.value ? sharedMemoryTopics(world.value.memories) : [])
function sourceLabel(memory: Memory) {
  const label = { seed: '搬来前 · 初始', observed: '亲历/观察', heard: '听说 · 转述', reflection: '反思' }[memory.sourceType] || memory.sourceType
  return memory.sourceId && memory.sourceId !== memory.ownerId ? `${label} · 来自${nameOf(memory.sourceId)}` : label
}

// 关系（不对称矩阵）
const matrix = computed(() => world.value ? relationshipMatrix(world.value.residentStates as DebugResidentState[], allActorIds.value) : new Map())

// 此刻发生
function activeConversationFor(id: string): DebugConversation | undefined {
  return world.value?.conversations?.find(conversation => conversation.status === 'active' && conversation.participantIds.includes(id))
}
const openConversationId = ref<string | null>(null)
function toggleConversation(id: string) { openConversationId.value = openConversationId.value === id ? null : id }
const openConversation = computed(() => world.value?.conversations?.find(conversation => conversation.id === openConversationId.value))
function planCountdown(endsAt?: string) {
  if (!endsAt) return ''
  const seconds = Math.round((Date.parse(endsAt) - now.value) / 1000)
  if (seconds <= 0) return '已到时间'
  return `还剩 ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}
function projectTitle(id?: string | null) { return world.value?.projects?.find(project => project.id === id)?.title }

// 模型
const backoffSeconds = computed(() => backoffRemainingSeconds(world.value?.modelRetryAfter, now.value))

function dateTime(at?: string | null) {
  if (!at) return '—'
  const timezone = world.value?.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone
  return new Intl.DateTimeFormat('zh-CN', { timeZone: timezone, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(at))
}
function since(at: string | null) {
  if (!at) return '—'
  return dateTime(new Date(at).toISOString())
}
function driftClass(delta: number | null) {
  if (delta === null || delta === 0) return 'flat'
  return delta > 0 ? 'up' : 'down'
}
</script>

<template>
  <main class="debug-page" aria-labelledby="debug-title">
    <header class="debug-head">
      <div>
        <p class="eyebrow">开发期 · 上帝视角 · 上线前应整体隐藏</p>
        <h1 id="debug-title">/town/debug</h1>
      </div>
      <div class="debug-tools">
        <a class="text-button" href="/town">回到小街</a>
        <label class="auto-toggle"><input type="checkbox" v-model="autoRefresh"/>每 4 秒自动刷新</label>
        <button class="secondary" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '立即刷新' }}</button>
        <span v-if="lastFetchedAt" class="fetched-at" role="status">上次拉取 {{ new Date(lastFetchedAt).toLocaleTimeString('zh-CN', { hourCycle: 'h23' }) }}</span>
      </div>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>

    <template v-if="world">
      <details open class="section">
        <summary>世界状态 <span class="count">rev {{ world.revision }}</span></summary>
        <div class="kv-grid">
          <div><span>天气 / 时段</span><strong>{{ world.weather === 'rain' ? '雨' : '晴' }} · {{ world.period }}</strong></div>
          <div><span>时区</span><strong>{{ world.timezone }}</strong></div>
          <div><span>入住时间</span><strong>{{ dateTime(world.joinedAt) }}</strong></div>
          <div><span>最近更新</span><strong>{{ dateTime(world.updatedAt) }}</strong></div>
          <div><span>simulationVersion</span><strong>{{ world.simulationVersion }}</strong></div>
          <div><span>intentRevision / eventSequence</span><strong>{{ world.intentRevision }} / {{ world.eventSequence }}</strong></div>
          <div><span>lastEncounterSlot</span><strong>{{ world.lastEncounterSlot }}</strong></div>
          <div><span>simulatedAt</span><strong>{{ dateTime(world.simulatedAt) }}</strong></div>
        </div>
      </details>

      <details open class="section">
        <summary>人格与漂移 <span class="count">{{ personalityRows.length }} 人</span></summary>
        <p class="hint">初值来自后端 Personality.INITIAL 的种子表；「现在」是这次响应里的实际值。漂移允许是负的——责任感能升也能降，这是判断涌不涌现的唯一标准。</p>
        <div class="scroll-x">
          <table class="grid-table">
            <thead><tr><th>居民</th><th v-for="dim in ['外向', '尽责', '敏感', '易感']" :key="dim">{{ dim }}</th><th>当下状态（非人格）</th></tr></thead>
            <tbody>
              <tr v-for="row in personalityRows" :key="row.id">
                <td class="row-label"><strong>{{ nameOf(row.id) }}</strong><small>{{ roleOf(row.id) }}</small></td>
                <td v-for="cell in row.drift" :key="cell.key" class="drift-cell" :class="driftClass(cell.delta)">
                  <span class="drift-current">{{ cell.current }}</span>
                  <span v-if="cell.initial !== null" class="drift-detail">{{ cell.initial }} → {{ cell.current }}
                    <template v-if="cell.delta! > 0">↑ +{{ cell.delta }}</template>
                    <template v-else-if="cell.delta! < 0">↓ {{ cell.delta }}</template>
                    <template v-else>持平</template>
                  </span>
                </td>
                <td class="now-state">
                  <span>精力 {{ Math.round(statesById.get(row.id)?.energy ?? 0) }}</span>
                  <span>社交 {{ Math.round(statesById.get(row.id)?.social ?? 0) }}</span>
                  <span>好奇 {{ Math.round(statesById.get(row.id)?.curiosity ?? 0) }}</span>
                  <span class="mood">「{{ statesById.get(row.id)?.mood || '—' }}」</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>

      <details open class="section">
        <summary>他们怎么看彼此（不对称） <span class="count">{{ allActorIds.length }} × {{ allActorIds.length }}</span></summary>
        <p class="hint">行 = 「谁」对「列」的私有好感，说没说出口用 ✓ 标出。A 对 B 与 B 对 A 是两个不同的数——如果这张表看起来对称，说明呈现出错了。</p>
        <div class="scroll-x">
          <table class="grid-table matrix">
            <thead><tr><th>好感 ↓行对→列</th><th v-for="to in allActorIds" :key="to">{{ nameOf(to) }}</th></tr></thead>
            <tbody>
              <tr v-for="from in allActorIds" :key="from">
                <td class="row-label"><strong>{{ nameOf(from) }}</strong></td>
                <td v-for="to in allActorIds" :key="to" class="matrix-cell">
                  <span v-if="from === to" class="diag">—</span>
                  <template v-else>
                    <span v-if="matrix.get(from)?.get(to)?.value === null" class="unknown">未知</span>
                    <span v-else>{{ matrix.get(from)?.get(to)?.value }}<span v-if="matrix.get(from)?.get(to)?.expressed" class="expressed" title="已经说出口过">✓</span></span>
                  </template>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>

      <details open class="section">
        <summary>此刻发生着什么</summary>
        <div class="scroll-x">
          <table class="grid-table">
            <thead><tr><th>居民</th><th>位置</th><th>此刻</th><th>计划</th><th>心里想着</th><th>对话</th></tr></thead>
            <tbody>
              <tr v-for="id in allActorIds" :key="id">
                <td class="row-label"><strong>{{ nameOf(id) }}</strong><small>{{ roleOf(id) }}</small></td>
                <td>{{ placeLabel(statesById.get(id)?.positionId) || placeLabel((id === 'self' ? world.avatar : world.residents.find(a => a.id === id))?.place) }}<br><small>{{ positionLabel(statesById.get(id)?.positionId) }}</small></td>
                <td>{{ (id === 'self' ? world.avatar : world.residents.find(a => a.id === id))?.label || '—' }}</td>
                <td v-if="statesById.get(id)?.plan">
                  <strong>{{ statesById.get(id)!.plan!.action }}</strong> @ {{ placeLabel(statesById.get(id)!.plan!.place) }}
                  <div class="plan-reason">{{ statesById.get(id)!.plan!.reason }}<template v-if="projectTitle(statesById.get(id)!.plan!.targetId)"> · {{ projectTitle(statesById.get(id)!.plan!.targetId) }}</template></div>
                  <small>{{ planCountdown(statesById.get(id)!.plan!.endsAt) }}</small>
                </td>
                <td v-else>—</td>
                <td>{{ statesById.get(id)?.thought || statesById.get(id)?.goal || '—' }}</td>
                <td>
                  <button v-if="activeConversationFor(id)" class="text-button" @click="toggleConversation(activeConversationFor(id)!.id)">
                    与{{ activeConversationFor(id)!.participantIds.filter(p => p !== id).map(nameOf).join('、') }}
                    {{ openConversationId === activeConversationFor(id)!.id ? '收起' : `展开（${activeConversationFor(id)!.turns.length} 句）` }}
                  </button>
                  <span v-else class="muted">没在聊</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="openConversation" class="conversation-detail">
          <p class="hint">{{ placeLabel(openConversation.place) }} · {{ openConversation.status === 'active' ? '进行中' : '已结束' }} · mode={{ openConversation.mode }}</p>
          <ol>
            <li v-for="(turn, index) in openConversation.turns" :key="index"><strong>{{ nameOf(turn.speakerId) }}</strong><span>{{ turn.text }}</span><small>{{ dateTime(turn.at) }}</small></li>
          </ol>
        </div>
      </details>

      <details open class="section">
        <summary>模型在干什么</summary>
        <div class="kv-grid">
          <div><span>状态文案</span><strong>{{ world.modelStatus }}</strong></div>
          <div><span>今日调用</span><strong>{{ world.modelCallsToday }}</strong></div>
          <div><span>今日失败</span><strong :class="{ danger: world.modelFailuresToday > 0 }">{{ world.modelFailuresToday }}</strong></div>
          <div><span>连续失败</span><strong :class="{ danger: world.modelConsecutiveFailures > 0 }">{{ world.modelConsecutiveFailures }}</strong></div>
          <div><span>失败退避</span><strong :class="{ danger: backoffSeconds > 0 }">{{ backoffSeconds > 0 ? `退避中，还剩 ${backoffSeconds}s` : '未退避' }}</strong></div>
          <div><span>预算日</span><strong>{{ world.modelBudgetDay }}</strong></div>
          <div><span>对话是否启用模型</span><strong>{{ world.modelConversationsEnabled ? '是' : '否（走规则）' }}</strong></div>
          <div><span>modelSequence</span><strong>{{ world.modelSequence }}</strong></div>
          <div><span>最近一次请求</span><strong>{{ dateTime(world.modelRequestedAt) }}</strong></div>
        </div>
      </details>

      <details class="section">
        <summary>他们各自记住了什么 <span class="count">{{ world.memories.length }} 条</span></summary>
        <details v-if="sharedTopics.length" open class="sub-section shared-topics">
          <summary>同源记忆对照 <span class="count">{{ sharedTopics.length }} 组</span></summary>
          <p class="hint">同一件事，不同的人各自写下了自己那一版。</p>
          <article v-for="group in sharedTopics" :key="group.topicId" class="topic-group">
            <p class="topic-id">topicId = {{ group.topicId }}</p>
            <div class="topic-entries">
              <div v-for="entry in group.entries" :key="entry.id" class="topic-entry">
                <header><strong>{{ nameOf(entry.ownerId) }}</strong><span class="importance">重要度 {{ entry.importance ?? '—' }}</span></header>
                <p>{{ entry.text }}</p>
                <small>{{ sourceLabel(entry) }} · {{ dateTime(entry.at) }}</small>
              </div>
            </div>
          </article>
        </details>
        <details v-for="id in allActorIds" :key="id" class="sub-section">
          <summary>{{ nameOf(id) }} <span class="count">{{ memoryGroups.get(id)?.length || 0 }} 条</span></summary>
          <div class="scroll-x">
            <table class="grid-table memory-table">
              <thead><tr><th>时间</th><th>来源</th><th>重要度</th><th>topicId</th><th>内容</th></tr></thead>
              <tbody>
                <tr v-for="memory in memoryGroups.get(id) || []" :key="memory.id">
                  <td><small>{{ dateTime(memory.at) }}</small></td>
                  <td><span class="tag" :class="`tag-${memory.sourceType}`">{{ sourceLabel(memory) }}</span></td>
                  <td>{{ memory.importance ?? '—' }}</td>
                  <td><small>{{ memory.topicId || '—' }}</small></td>
                  <td>{{ memory.text }}</td>
                </tr>
                <tr v-if="!(memoryGroups.get(id) || []).length"><td colspan="5" class="muted">还没有记忆。</td></tr>
              </tbody>
            </table>
          </div>
        </details>
      </details>
    </template>
    <p v-else-if="!loading && !error" class="muted">正在加载…</p>
  </main>
</template>

<style scoped>
.debug-page { min-height: 100dvh; box-sizing: border-box; padding: 20px 24px 64px; background: var(--canvas); color: var(--ink); font-size: 13px; }
.debug-head { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid var(--border); }
.debug-head h1 { margin: 2px 0 0; font-size: 20px; font-family: monospace; }
.debug-tools { display: flex; align-items: center; gap: 12px; margin-left: auto; flex-wrap: wrap; font-size: 12px; }
.auto-toggle { display: flex; align-items: center; gap: 5px; color: var(--muted); }
.fetched-at { color: var(--muted); }
.text-button { background: none; border: 0; color: var(--primary-strong); cursor: pointer; padding: 2px 4px; font: inherit; text-decoration: underline; }
.section { border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); margin-bottom: 14px; padding: 4px 14px 14px; }
.section > summary { cursor: pointer; font-weight: 700; padding: 10px 0; list-style: revert; }
.count { font-weight: 400; color: var(--muted); margin-left: 6px; font-size: 12px; }
.hint { color: var(--muted); font-size: 12px; margin: 0 0 10px; }
.kv-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 8px 16px; }
.kv-grid > div { display: flex; flex-direction: column; gap: 2px; padding: 6px 0; border-bottom: 1px dashed var(--border); }
.kv-grid span { color: var(--muted); font-size: 11px; }
.kv-grid strong { font-variant-numeric: tabular-nums; }
.danger { color: var(--danger); }
.scroll-x { overflow-x: auto; }
.grid-table { border-collapse: collapse; width: 100%; min-width: 560px; }
.grid-table th, .grid-table td { border: 1px solid var(--border); padding: 6px 9px; text-align: left; vertical-align: top; font-size: 12px; }
.grid-table thead th { background: var(--surface-muted); position: sticky; top: 0; }
.row-label strong { display: block; }
.row-label small { color: var(--muted); }
.drift-cell { min-width: 96px; }
.drift-current { display: block; font-weight: 700; font-variant-numeric: tabular-nums; }
.drift-detail { display: block; font-size: 11px; color: var(--muted); }
.drift-cell.up .drift-detail { color: var(--accent-strong); }
.drift-cell.down .drift-detail { color: var(--danger); }
.now-state { display: flex; flex-direction: column; gap: 2px; font-size: 11px; color: var(--muted); }
.now-state .mood { color: var(--ink); }
.matrix th, .matrix td { text-align: center; }
.matrix thead th:first-child { text-align: left; }
.matrix-cell .unknown, .matrix-cell .diag { color: var(--muted); }
.expressed { margin-left: 3px; color: var(--amber); font-weight: 700; }
.plan-reason { font-size: 11px; color: var(--muted); margin-top: 2px; }
.muted { color: var(--muted); }
.conversation-detail { margin-top: 12px; padding: 10px 12px; background: var(--surface-muted); border-radius: var(--radius); }
.conversation-detail ol { list-style: none; margin: 6px 0 0; padding: 0; display: grid; gap: 8px; }
.conversation-detail li { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
.conversation-detail small { color: var(--muted); margin-left: auto; }
.sub-section { border: 1px solid var(--border); border-radius: var(--radius); padding: 4px 10px 10px; margin-bottom: 10px; background: var(--surface-raised); }
.sub-section > summary { cursor: pointer; font-weight: 650; padding: 8px 0; }
.shared-topics { background: color-mix(in srgb, var(--amber) 8%, var(--surface-raised)); }
.topic-group { border-top: 1px dashed var(--border); padding: 10px 0; }
.topic-id { font-size: 11px; color: var(--muted); margin: 0 0 6px; font-family: monospace; }
.topic-entries { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 8px; }
.topic-entry { border: 1px solid var(--border); border-radius: var(--radius); padding: 8px 10px; background: var(--surface); }
.topic-entry header { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 4px; }
.topic-entry .importance { font-size: 11px; color: var(--muted); }
.topic-entry p { margin: 0 0 6px; line-height: 1.6; }
.topic-entry small { color: var(--muted); }
.tag { display: inline-block; padding: 1px 6px; border-radius: 999px; font-size: 11px; background: var(--surface-muted); color: var(--muted); }
.tag-reflection { background: color-mix(in srgb, var(--amber) 20%, var(--surface-muted)); color: var(--tone-celebrate-ink); }
.tag-heard { background: color-mix(in srgb, var(--tone-blue) 16%, var(--surface-muted)); }
.tag-seed { background: color-mix(in srgb, var(--accent) 16%, var(--surface-muted)); }
.memory-table td:last-child { min-width: 260px; }
</style>
