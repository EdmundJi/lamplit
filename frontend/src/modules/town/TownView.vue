<script setup lang="ts">
import type { ConversationNotice } from './npc-conversation'
import { useTownMailSignal } from './town-mail-signal'
import ResidentMoment from './ResidentMoment.vue'
import TownEventsBoard from './TownEventsBoard.vue'
import { useTownEventsStore } from './town-events'
import { computed, defineAsyncComponent, h, provide, onBeforeUnmount, onErrorCaptured, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { Building2, Film, Flame, Footprints, GraduationCap, HelpCircle, Mail, Maximize2, Moon, Rabbit, Sparkles, Sun, RefreshCw, X } from 'lucide-vue-next'
import { encouragement } from '../../shared/encouragement'
import { useTownStore } from './town.store'
import { activityFor, activityLabels, dimensionLabels, floorsForLevel } from './building-kit'
import NpcDialogue from './NpcDialogue.vue'
import TownOnboarding from './TownOnboarding.vue'
import { useNpcChatStore } from './npc-chat'
import { useTownNpcStore } from './town-npc.store'
import { TownControls, type RunMode } from './town-controls'
import { worldPanels } from './immersive/panels/manifest'
import { registerBuiltinWorldActions, runWorldAction } from './immersive/world-actions'
import type { TownGame, TownSelection, TownTravel, TownNearby } from './town.engine'
import type { TownModel } from './town.types'
import { worldBridgeKey, type WorldBridge, type WorldPanelKey, type WorldAnchor } from './immersive/panel.types'

const store = useTownStore()
const mailSignal = useTownMailSignal()
let socialTicker: ReturnType<typeof setInterval> | null = null
const eventsStore = useTownEventsStore()
const eventsOpen = ref(false)
function openEvents() { activePanel.value = null; eventsOpen.value = true; panelVisible.value = true }
const npcChatStore = useNpcChatStore()
const townNpcStore = useTownNpcStore()
const router = useRouter()
const canvas = ref<HTMLElement | null>(null)
const onboardingRef = ref<InstanceType<typeof TownOnboarding> | null>(null)
const night = ref(new Date().getHours() >= 18 || new Date().getHours() < 6)
const selection = ref<TownSelection>(null)
const panelVisible = ref(true)
const activePanel = ref<WorldPanelKey | null>(null)
const activeRoom = ref<string | null>(null)
const feedback = ref('')
const travelStatus = ref<TownTravel | null>(null)
const nearby = ref<TownNearby | null>(null)
const travelMessage = computed(() => {
  const status = travelStatus.value
  if (!status) return ''
  return status.phase === 'walking' ? `正在走向${status.label}…` : status.phase === 'arrived' ? `已到达${status.label}` : `去${status.label}的路被挡住了，请换条路或重试。`
})
function recoverPosition() {
  selection.value = null
  if (!activePanel.value) closePanel()
  if (game?.recover?.()) feedback.value = '已回到家门口的安全位置。'
  else void mountGame()
}
function cancelTravel() { game?.cancelTravel?.(); travelStatus.value = null }
function interactNearby() { game?.interactNearby?.() }

const places = [
  { id: 'home', label: '我的家' }, { id: 'academy', label: '学院' },
  { id: 'gym', label: '健身房' }, { id: 'cafe', label: '咖啡馆' }, { id: 'park', label: '公园' },
]
const panelBodies = Object.fromEntries(worldPanels.map(def => [def.key, defineAsyncComponent({
  loader: async () => (await def.loader()).default,
  loadingComponent: { render: () => h('p', { role: 'status' }, '正在打开…') },
  errorComponent: { render: () => h('p', { role: 'alert' }, '面板暂时打不开，请关闭后重试。') },
  delay: 100, timeout: 20000,
})]))
const panelDef = computed(() => worldPanels.find(def => def.key === activePanel.value))
function closePanel() { panelVisible.value = false }
function openPanel(key: WorldPanelKey) {
  eventsOpen.value = false
  activePanel.value = key
  panelVisible.value = true
}
function select(value: TownSelection) {
  if (value === null) return
  eventsOpen.value = false
  selection.value = value
  activePanel.value = null
  panelVisible.value = true
  const target = worldPanels.find(def => def.anchor === value)
  if (target && ['park', 'plaza', 'street', 'npc:postman'].includes(value)) openPanel(target.key)
}
function travelTo(place: string) {
  observing.value = false
  game?.setObservation(false)
  closePanel()
  game?.travelTo?.(place)
}
function exitRoom() { game?.exitRoom?.(); game?.exitAcademy?.() }

const selectedNpc = computed(() => {
  const code = selection.value?.startsWith('npc:') ? selection.value.slice(4) : ''
  const npc = townNpcStore.byCode(code)
  return npc?.layer === 2 ? npc : null
})
const conversationCode = computed(() => {
  if (!panelVisible.value || eventsOpen.value) return null
  if (!activePanel.value && selectedNpc.value) return selectedNpc.value.code
  if (!activePanel.value && selection.value === 'npc:assistant') return 'GUIDE'
  if ((!activePanel.value && selection.value === 'npc:postman-chat') || (activePanel.value === 'friends' && selection.value === 'npc:postman')) return 'POSTMAN'
  return null
})
const conversationNotice = ref<ConversationNotice | null>(null)
function onConversationChange(notice: ConversationNotice | null) {
  conversationNotice.value = notice
  if (notice?.phase === 'ended' && conversationCode.value === notice.npcCode) {
    selection.value = null
    if (!activePanel.value) closePanel()
    if (notice.npcInitiated) feedback.value = `${notice.name}先去忙了。${notice.reason ?? ''}`
  }
}
function interruptNpc(code: string, reason: string) { game?.interruptConversation?.(code, reason) }

const engineError = ref('')
const insideAcademy = ref(false)
const playerPosition = ref<{ x: number; y: number } | null>(null)
const distanceToGuide = ref<number | null>(null)
// 观察模式：镜头脱离玩家在兴趣点之间巡游（plan D4）。既是录 demo 的工具，也是一个真功能。
const observing = ref(false)
// A NpcDialogue that throws while mounting/streaming falls back to the old static card below,
// instead of leaving the panel blank — reset whenever the selection changes so a fresh open retries.
const npcDialogueError = ref(false)
let game: TownGame | null = null
let controls: TownControls | null = null
let mountSequence = 0
let residentSignature = ''

function residentKey(model: TownModel | null) {
  return model ? model.residents.map(item => item.publicId).join(',') : ''
}

const residents = computed(() => store.model?.residents ?? [])
const selected = computed(() => residents.value.find(item => item.publicId === selection.value) ?? null)
const self = computed(() => residents.value.find(item => item.isSelf) ?? null)
const awakeCount = computed(() => residents.value.filter(item => activityFor(item) !== 'resting').length)
const studying = computed(() => residents.value.filter(item => activityFor(item) === 'done'))
const unread = computed(() => store.model?.unread ?? 0)
// TownControls 是普通对象，不是响应式的：用 computed 读它的 getter 会因为没有任何依赖而
// 求值一次就永久缓存，按钮的 aria-pressed/文案再也不会更新。改成由控制器的回调回写 ref。
const running = ref(false)
const runMode = ref<RunMode>('walk')
const anyPanelOpen = computed(() => panelVisible.value && (activePanel.value !== null || selection.value !== null || eventsOpen.value))
const assistantLine = computed(() => {
  if (!self.value) return ''
  const activity = activityFor(self.value)
  if (activity === 'done') return encouragement('taskCompleted')
  if (activity === 'working') return encouragement('taskStarted')
  if (activity === 'planned') return '今天的安排已经排好了，先挑最小的那一件开始，房子就会亮灯。'
  return '还没有今天的安排。要不要让我帮你把目标拆成今天能做的一小步？'
})
const nextFloorLevel = computed(() => (selected.value ? (floorsForLevel(selected.value.level) + 1) * 2 + 1 : 0))
const postmanLine = computed(() => (unread.value > 0 ? `有 ${unread.value} 封新信在等你，是朋友们捎来的话。` : '今天没有新的信，朋友们都在各忙各的。'))
// 小助的开场白：优先用昨晚反思的问候语，没有就退回原来按今日状态生成的那句鼓励。
const guideOpener = computed(() => npcChatStore.reflection?.greeting || assistantLine.value)
const isDialogueOpen = computed(() => !npcDialogueError.value && (selection.value === 'npc:assistant' || selection.value === 'npc:postman' || selection.value === 'npc:postman-chat'))

async function mountGame() {
  if (!canvas.value || !store.model) return
  const sequence = ++mountSequence
  engineError.value = ''
  try {
    const { createTownGame } = await import('./town.engine')
    if (sequence !== mountSequence) return
    game?.destroy()
    game = null
    const created = await createTownGame(canvas.value, store.model, {
      onConversationChange,
      onSelect: select,
      onObservationChange: enabled => { observing.value = enabled },
      onTravelChange: status => { travelStatus.value = status },
      onNearbyChange: value => { nearby.value = value },
      onRoomChange: room => { activeRoom.value = room; insideAcademy.value = room === 'academy' },
      onAcademyChange: inside => { insideAcademy.value = inside },
      onPresenceReport: payload => { void store.reportPresence(payload) },
      onPlayerMove: (x, y) => { playerPosition.value = { x, y } },
      onDistanceToGuide: distance => { distanceToGuide.value = distance },
      // 护栏 A：把这一次主动搭话记到服务端，再用权威额度校正引擎的乐观值。
      onInitiativeSpent: () => { void townNpcStore.consumeInitiative().then(budget => game?.setInitiativeBudget(budget)).catch(() => {}) },
      onInteriorInteract: (actionId, id) => { void openInteriorTarget(actionId, id) },
    })
    if (sequence !== mountSequence) { created.destroy(); return }
    game = created
    game.setLetterUnread?.(mailSignal.unreadCount)
    game.applyEvents?.(eventsStore.events)
    if (conversationCode.value) game?.beginConversation?.(conversationCode.value)
    game.setNight(night.value)
    game.setRun(running.value)
    residentSignature = residentKey(store.model)
    // 名册单独拉：/town 的载荷保持原样，小镇社会是加在旁边的一层，拉不到也不该让画面起不来。
    await townNpcStore.load()
    if (sequence === mountSequence && game) game.applyNpcs(townNpcStore.npcs, townNpcStore.budget)
  } catch (error) {
    engineError.value = (error as Error).message || '小镇画面初始化失败'
  }
}

// 普通页与沉浸页复用能力和面板内容，家具动作无需离开小镇。
registerBuiltinWorldActions()
const worldBridge: WorldBridge = {
  emit(event) {
    if (event.type === 'mail-count') mailSignal.unreadCount = event.count
    else if (event.type === 'open') openPanel(event.panel)
    else if (event.type === 'close') closePanel()
    else if (event.type === 'celebrate') { const id = residents.value.find(r => r.publicId === event.publicId)?.publicId ?? self.value?.publicId; if (id) game?.celebrate(id); void store.load() }
    else if (event.type === 'toast') feedback.value = event.text
    else if (event.type === 'focus') game?.focus(event.publicId)
    else if (event.type === 'travel') travelTo(event.place)
    else if (event.type === 'night') { night.value = event.value; game?.setNight(event.value) }
    else if (event.type === 'academy') { if (event.value) travelTo('academy'); else exitRoom() }
  },
  get runMode() { return running.value },
  setRunMode(enabled) { controls?.setRunMode(enabled ? 'run' : 'walk') },
  async run(actionId, payload) {
    const result = await runWorldAction(actionId, {
      bridge: worldBridge,
      anchor: activeRoom.value as WorldAnchor | null,
      selfPublicId: self.value?.publicId ?? null,
      night: night.value,
      insideAcademy: insideAcademy.value,
      refresh: () => store.load(),
      payload,
    })
    if (result.message) feedback.value = result.message
    return result
  },
}
provide(worldBridgeKey, worldBridge)
async function openInteriorTarget(actionId: string, id: string) {
  await worldBridge.run(actionId, id)
}

function toggleObservation() {
  observing.value = !observing.value
  game?.setObservation(observing.value)
}

function toggleNight() {
  night.value = !night.value
  game?.setNight(night.value)
}

function toggleRun() {
  controls?.toggleRunMode()
}

/** 把控制器的实际跑步状态（持久模式 + Shift 临时加速）同步给界面和引擎。 */
function syncRunning() {
  running.value = controls?.isRunning ?? false
  game?.setRun(running.value)
}

async function reload() {
  await store.load()
}

function showOnboarding() {
  onboardingRef.value?.restart()
}

/** An action run from inside the dialogue (task start/complete/defer/skip) touches server state,
 * so refresh the town straight away instead of waiting for the next 30s poll. */
async function onNpcAction() {
  await store.load()
}

function onKeyDown(event: KeyboardEvent) {
  if (event.defaultPrevented) return
  if (event.key === 'Escape' && travelStatus.value?.phase === 'walking') { cancelTravel(); event.preventDefault(); return }
  if (event.key === 'Escape' && panelVisible.value) { closePanel(); event.preventDefault(); return }
  if (controls?.handleKeyDown(event)) {
    event.preventDefault()
  }
}

function onKeyUp(event: KeyboardEvent) {
  if (controls?.handleKeyUp(event)) {
    event.preventDefault()
  }
}

watch(conversationCode, (code, previous) => {
  if (previous) game?.endConversation?.(previous)
  if (code) game?.beginConversation?.(code)
})

onMounted(async () => {
  void mailSignal.load()
  socialTicker = setInterval(() => {
    void mailSignal.load()
    void eventsStore.load()
    void townNpcStore.load().then(() => game?.applyNpcs(townNpcStore.npcs, townNpcStore.budget))
  }, 60_000)
  void npcChatStore.loadReflection()
  void eventsStore.load()

  // 初始化控制器
  controls = new TownControls({
    onRunModeChange: mode => {
      runMode.value = mode
      syncRunning()
    },
    onShiftChange: () => {
      syncRunning()
    },
  })

  // 注册键盘事件
  document.addEventListener('keydown', onKeyDown)
  document.addEventListener('keyup', onKeyUp)

  if (store.model) await mountGame()
  else await store.load()
  store.startPolling()
})

watch(() => eventsStore.events, events => { game?.applyEvents?.(events) })

watch(selection, () => { npcDialogueError.value = false })

// NpcDialogue talks to a still-shifting SSE contract; if it throws, fall back to the old
// static card (with its RouterLink) instead of leaving the panel blank.
onErrorCaptured(error => {
  if (!activePanel.value && !eventsOpen.value && (selection.value === 'npc:assistant' || selection.value === 'npc:postman')) {
    console.error('NpcDialogue 渲染失败，退回静态提示', error)
    npcDialogueError.value = true
    return false
  }
  return true
})

// Same residents as before → just update the running scene in place; a different resident
// list (friends added/removed, solo mode toggled) rebuilds the game from scratch.
watch(() => store.model, async model => {
  if (!model) return
  if (game && residentKey(model) === residentSignature) {
    game.applyModel(model)
  } else {
    await mountGame()
  }
})

watch(() => store.lastCelebrations, celebrations => {
  if (!game) return
  for (const item of celebrations) game.celebrate(item.publicId)
})

watch(() => mailSignal.unreadCount, count => game?.setLetterUnread?.(count))

onBeforeUnmount(() => {
  if (socialTicker) clearInterval(socialTicker)
  document.removeEventListener('keydown', onKeyDown)
  document.removeEventListener('keyup', onKeyUp)
  mountSequence++
  store.stopPolling()
  controls?.destroy()
  controls = null
  game?.destroy()
  game = null
})
</script>

<template>
  <section class="page page--scene town-page">
    <TownOnboarding
      v-if="self"
      ref="onboardingRef"
      :user-id="self.publicId"
      :player-position="playerPosition"
      :distance-to-guide="distanceToGuide"
      :any-panel-open="anyPanelOpen"
      :conversation-open="Boolean(selectedNpc) || selection === 'npc:assistant' || selection === 'npc:postman'"
    />

    <header class="page-head">
      <div>
        <p class="eyebrow">一步一步，自成风景</p>
        <h1>成长小镇</h1>
      </div>
      <div class="actions">
        <button class="secondary" type="button" aria-label="返回安全位置" title="卡住时直接回到家门口，任务数据不变" @click="recoverPosition">脱困</button>
        <button class="secondary" type="button" @click="toggleNight">
          <component :is="night ? Sun : Moon" :size="17" />{{ night ? '切到白天' : '切到夜晚' }}
        </button>
        <button class="secondary run-toggle" type="button" :aria-pressed="runMode === 'run'" @click="toggleRun">
          <component :is="runMode === 'run' ? Rabbit : Footprints" :size="17" />{{ runMode === 'run' ? '奔跑中' : '开始奔跑' }}
        </button>
        <button class="secondary" type="button" :aria-pressed="observing" title="镜头脱离玩家，在小镇的几个热闹处之间缓慢巡游" @click="toggleObservation">
          <Film :size="17" />{{ observing ? '退出观察' : '观察小镇' }}
        </button>
        <button class="secondary" type="button" @click="select('npc:assistant')"><Sparkles :size="17" />找小助</button>
        <button class="secondary" type="button" title="重新打开新手引导" @click="showOnboarding"><HelpCircle :size="17" />帮助</button>
        <button class="secondary" type="button" title="走到咖啡馆外的互动露台" @click="travelTo('terrace')"><Film :size="17" />街角露台</button>
        <button class="secondary" type="button" title="进入沉浸模式" @click="router.push('/town/immersive')"><Maximize2 :size="17" />沉浸模式</button>
        <button class="icon-button" type="button" title="刷新" aria-label="刷新" :disabled="store.loading" @click="reload"><RefreshCw :size="17" /></button>
      </div>
    </header>

    <p v-if="store.error" class="error" role="alert">{{ store.error }}</p>
    <p v-else-if="engineError" class="error" role="alert">{{ engineError }}</p>

    <nav class="town-places actions" aria-label="小镇地点">
      <button v-for="place in places" :key="place.id" class="secondary" type="button" :disabled="!store.model || !!activeRoom" :title="`走到${place.label}${place.id === 'park' ? '' : '并进入'}`" @click="travelTo(place.id)">{{ place.label }}</button>
      <button v-if="activeRoom" class="secondary" type="button" @click="exitRoom">回到小镇</button>
      <button class="secondary" type="button" @click="openEvents">活动</button>
      <button class="secondary" type="button" @click="openPanel('friends')">信箱<span v-if="mailSignal.unreadCount"> · {{ mailSignal.unreadCount }}</span></button>
      <button class="secondary" type="button" @click="openPanel('today')">今天</button>
      <button class="secondary" type="button" @click="openPanel('partners')">伙伴</button>
      <button class="secondary" type="button" @click="openPanel('ai')">AI 助手</button>
      <button class="secondary" type="button" :aria-expanded="panelVisible" aria-controls="town-info-panel" @click="panelVisible = !panelVisible">{{ panelVisible ? '收起面板' : '打开面板' }}</button>
    </nav>
    <div class="town-navigation">
      <p v-if="travelMessage" class="town-travel-status" role="status">{{ travelMessage }}</p>
      <button v-if="travelStatus?.phase === 'walking'" class="secondary" type="button" @click="cancelTravel">取消前往</button>
      <button v-if="nearby" class="secondary" type="button" @click="interactNearby">{{ nearby.action }} · {{ nearby.label }}（E）</button>
    </div>
    <p v-if="conversationNotice?.phase === 'leaving'" class="town-conversation-departure" role="status">{{ conversationNotice.name }}：{{ conversationNotice.reason }}</p>
    <p v-if="feedback" class="muted" role="status">{{ feedback }}</p>
    <div class="town-stage">
      <div ref="canvas" class="town-canvas" data-testid="town-canvas" />
      <div v-if="store.loading && !store.model" class="town-loading" role="status">正在把大家的房子搬进小镇…</div>

      <aside v-if="panelVisible" id="town-info-panel" class="town-panel" :class="{ 'has-dialogue': !activePanel && isDialogueOpen, 'has-feature': activePanel, 'has-core-feature': activePanel && ['today', 'partners', 'ai'].includes(activePanel) }" aria-label="小镇面板" @pointerdown.stop @keydown.esc.stop="closePanel" @keydown.stop>
        <div class="town-panel-bar"><button v-if="activePanel === 'friends'" class="secondary" type="button" @click="select('npc:postman-chat')">和邮递员聊聊</button><span>{{ eventsOpen ? '小镇活动' : panelDef?.title ?? '小镇见闻' }}</span><button class="town-panel-close" type="button" aria-label="关闭面板" @click="closePanel"><X :size="16" /></button></div>
        <div class="town-panel-content">
        <TownEventsBoard v-if="eventsOpen" @close="closePanel" @visit="travelTo" />
        <component :is="panelBodies[activePanel]" v-else-if="activePanel" :key="activePanel" />

        <template v-else-if="selected">
          <p class="eyebrow">{{ selected.isSelf ? '这是你的房子' : '邻居' }}</p>
          <h2>{{ selected.displayName }}<small v-if="selected.title"> · {{ selected.title }}</small></h2>
          <dl class="town-facts">
            <div><dt>综合等级</dt><dd>LV.{{ selected.level }} · {{ floorsForLevel(selected.level) + 1 }} 层</dd></div>
            <div><dt>主要方向</dt><dd>{{ selected.dominantDimension ? dimensionLabels[selected.dominantDimension] : '还在摸索' }}</dd></div>
            <div><dt>今天</dt><dd>{{ activityLabels[activityFor(selected)] }}<template v-if="selected.todayPlanned">（{{ selected.todayDone }}/{{ selected.todayPlanned }}）</template></dd></div>
            <div><dt>最长连续</dt><dd><Flame :size="14" /> {{ selected.longestStreak }} 天</dd></div>
            <div v-if="floorsForLevel(selected.level) < 4"><dt>下一层</dt><dd>升到 LV.{{ nextFloorLevel }} 加盖</dd></div>
          </dl>
          <button v-if="selected.isSelf" class="button primary" type="button" @click="openPanel('today')">继续今天的行动</button>
          <RouterLink v-else class="button secondary" :to="`/friends/${selected.publicId}`">看看 TA 的成长</RouterLink>
        </template>

        <ResidentMoment v-else-if="selectedNpc" :npc="selectedNpc" :conversation-state="conversationNotice?.phase" :leaving-reason="conversationNotice?.phase === 'leaving' ? conversationNotice.reason : undefined" @close="closePanel" />
        <template v-else-if="selection === 'npc:assistant'">
          <NpcDialogue v-if="!npcDialogueError" npc="GUIDE" display-name="小助" :leaving-reason="conversationNotice?.phase === 'leaving' ? conversationNotice.reason : undefined" @interrupt="reason => interruptNpc('GUIDE', reason)" :opener="guideOpener" @close="closePanel" @action="onNpcAction" />
          <template v-else>
            <p class="eyebrow">学院门口的向导</p>
            <h2><Sparkles :size="20" /> 小助</h2>
            <p class="town-speech">{{ assistantLine }}</p>
            <button class="button primary" type="button" @click="openPanel('ai')">找小助聊聊今天</button>
          </template>
        </template>

        <template v-else-if="(selection === 'npc:postman' || selection === 'npc:postman-chat')">
          <NpcDialogue v-if="!npcDialogueError" npc="POSTMAN" display-name="邮递员" :leaving-reason="conversationNotice?.phase === 'leaving' ? conversationNotice.reason : undefined" @interrupt="reason => interruptNpc('POSTMAN', reason)" :opener="postmanLine" @close="closePanel" @action="onNpcAction" />
          <template v-else>
            <p class="eyebrow">街上的邮递员</p>
            <h2><Mail :size="20" /> 邮递员</h2>
            <p class="town-speech">{{ postmanLine }}</p>
            <RouterLink class="button" :class="unread > 0 ? 'primary' : 'secondary'" to="/friends/chat">去看看信件</RouterLink>
          </template>
        </template>

        <template v-else-if="selection === 'academy'">
          <p class="eyebrow">大家一起变好的地方</p>
          <h2><GraduationCap :size="20" /> 成长学院</h2>
          <p class="town-speech">今天有 {{ studying.length }} 位邻居完成了任务，正在学院里读书。</p>
          <ul v-if="studying.length" class="town-roll">
            <li v-for="item in studying" :key="item.publicId">{{ item.isSelf ? '我' : item.displayName }} · {{ item.todayDone }}/{{ item.todayPlanned }}</li>
          </ul>
          <RouterLink class="button secondary" to="/friends">邀请朋友搬来小镇</RouterLink>
        </template>

        <template v-else>
          <p class="eyebrow">小镇现状</p>
          <h2><Building2 :size="20" /> {{ residents.length }} 户人家 · {{ townNpcStore.npcs.length }} 位邻里</h2>
          <p class="muted">今天已经开张 {{ awakeCount }} 户。点一栋房子、一个小人，看看背后的故事。</p>
          <ul class="town-legend">
            <li><strong>门面</strong>是主人最擅长的方向：书店是知识，健身房是健康，公寓是职场，冰淇淋店是关系，面包房是心境。</li>
            <li><strong>楼层</strong>随综合等级增加，每两级加一层。</li>
            <li><strong>卷帘门</strong>放下来，说明主人今天还没动手。</li>
            <li><strong>屋顶装置</strong>来自连续记录，中断不会拆掉已经装好的东西。</li>
            <li>去<strong>学院</strong>读书的小人，是今天已经完成任务的人。</li>
          </ul>
        </template>
        </div>
      </aside>

      <p class="town-hint">拖动平移 · 滚轮缩放 · 点击房子和小人 · 方向键/WASD 移动，按 R 或右上角按钮切换跑步</p>
    </div>

    <details class="town-directory"><summary>地点与居民 · 也可以从这里探索</summary><div class="actions"><button class="secondary" @click="travelTo('academy')">成长学院</button><button class="secondary" @click="select('npc:assistant')">小助</button><button class="secondary" @click="select('npc:postman')">邮递员</button><button v-for="resident in residents" :key="resident.publicId" class="secondary" @click="resident.isSelf ? travelTo('home') : select(resident.publicId)">{{ resident.isSelf ? '我的家' : resident.displayName }}</button></div></details>
    <p class="town-credits muted">像素美术：LimeZu（limezu.itch.io），已获授权使用。</p>
  </section>
</template>

<style scoped>
.town-page { width: min(100%, 1280px); }
.town-stage { position: relative; }
.town-canvas { width: 100%; height: clamp(480px, 72vh, 820px); border-radius: var(--radius); overflow: hidden; background: #78a95f; box-shadow: var(--shadow); touch-action: none; }
.town-canvas :deep(canvas) { display: block; image-rendering: pixelated; }
.town-loading { position: absolute; inset: 0; display: grid; place-items: center; color: #fff; font-weight: 700; background: rgb(30 40 30 / 35%); border-radius: var(--radius); }
.town-hint { position: absolute; left: 14px; bottom: 12px; margin: 0; padding: 5px 10px; border-radius: 999px; font-size: 12px; color: #fff; background: rgb(20 20 20 / 45%); backdrop-filter: blur(6px); pointer-events: none; }
.town-panel { position: absolute; top: 14px; right: 14px; width: min(320px, calc(100% - 28px)); display: grid; gap: 12px; padding: 18px; border: 1px solid color-mix(in srgb, var(--border) 60%, transparent); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 92%, transparent); backdrop-filter: blur(12px); box-shadow: var(--shadow); pointer-events: auto; user-select: none; }
.town-panel.has-dialogue { width: min(360px, calc(100% - 28px)); padding: 10px; background: transparent; border-color: transparent; box-shadow: none; backdrop-filter: none; }
.town-panel-close { position: absolute; top: 10px; right: 10px; width: 30px; height: 30px; min-height: 0; display: grid; place-items: center; padding: 0; border: 0; border-radius: 999px; background: transparent; color: var(--muted); user-select: none; }
.town-panel-close:hover { background: var(--surface-muted); color: var(--ink); }
.town-panel h2 { margin: 0; display: flex; align-items: center; gap: 8px; font-size: 22px; }
.town-panel h2 small { font-size: 13px; font-weight: 600; color: var(--muted); }
.town-panel .eyebrow { margin: 0; }
.town-speech { margin: 0; padding: 12px 14px; border-radius: var(--radius); background: color-mix(in srgb, var(--primary-soft) 60%, var(--surface)); color: var(--ink); font-size: 14px; line-height: 1.65; }
.town-facts { margin: 0; display: grid; gap: 8px; }
.town-facts div { display: flex; justify-content: space-between; gap: 12px; font-size: 14px; }
.town-facts dt { color: var(--muted); }
.town-facts dd { margin: 0; display: inline-flex; align-items: center; gap: 4px; font-weight: 650; text-align: right; }
.town-roll { margin: 0; padding: 0 0 0 18px; font-size: 13px; line-height: 1.7; }
.town-legend { margin: 0; padding: 0 0 0 18px; display: grid; gap: 6px; font-size: 13px; line-height: 1.6; color: var(--muted); }
.town-legend strong { color: var(--ink); }
.town-credits { margin: 14px 0 0; font-size: 12px; }
.run-toggle[aria-pressed='true'] { background: var(--primary-strong); color: var(--surface); border-color: var(--primary-strong); }
@media (max-width: 760px) {
  .town-canvas { height: clamp(420px, 60vh, 640px); }
  .town-panel { top: auto; bottom: 44px; left: 14px; right: 14px; width: auto; max-height: 55%; overflow: auto; }

}
.town-page { width: 100%; max-width: 1600px; }
.town-stage { border: 6px solid var(--forest); border-radius: var(--radius-scene); background: var(--forest); overflow: hidden; }
.town-canvas { border-radius: calc(var(--radius-scene) - 6px); box-shadow: none; height: clamp(480px, 65vh, 820px); }
.town-panel { border-radius: var(--radius-panel); padding: 22px; box-shadow: var(--shadow); }
.town-directory { margin-top: 18px; padding: 14px 18px; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); }
.town-directory summary { cursor: pointer; color: var(--primary-strong); font-size: 13px; }
.town-directory .actions { padding-top: 14px; }
@media (max-width:760px) { .town-stage { border-width: 4px; } .town-canvas { height: 56vh; min-height: 380px; } .town-page .page-head .actions { gap: 6px; } .town-page .page-head .actions button { font-size: 11px; padding-inline: 10px; } }
/* 面板的滚动只发生在内容区，关闭与返回入口始终可用。 */
.town-page .page-head { flex-wrap: wrap; gap: 16px; }
.town-page .page-head > div:first-child { flex: 0 0 auto; }
.town-page h1 { white-space: nowrap; }
.town-page .page-head .actions { flex: 1 1 580px; flex-wrap: wrap; justify-content: flex-end; }
.town-page .actions button { white-space: nowrap; }
.town-places { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.town-panel, .town-panel.has-dialogue { box-sizing: border-box; width: min(360px, calc(100% - 28px)); max-height: calc(100% - 68px); display: flex; flex-direction: column; gap: 0; padding: 0; overflow: hidden; background: var(--surface); border: 1px solid var(--border); box-shadow: var(--shadow); }
.town-panel.has-feature { width: min(560px, calc(100% - 28px)); }
.town-panel-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 8px 12px; flex: 0 0 auto; border-bottom: 1px solid var(--border); font-size: 13px; }
.town-panel-close { position: static; flex: 0 0 30px; }
.town-panel-content { min-width: 0; min-height: 0; padding: 14px; overflow: auto; overflow-wrap: anywhere; display: grid; gap: 12px; }
.town-panel.has-core-feature :deep(.panel-footer button:last-child) { display: none; }
.town-navigation { min-height: 44px; display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.town-travel-status { margin: 0; font-size: 13px; color: var(--ink); }
.town-panel-content :deep(.npc-dialogue) { width: 100%; max-width: 100%; box-sizing: border-box; }
.town-hint { max-width: calc(100% - 28px); box-sizing: border-box; }
@media (max-width: 760px) {
  .town-page .page-head .actions { justify-content: flex-start; }
  .town-panel, .town-panel.has-dialogue, .town-panel.has-feature { top: 12px; bottom: auto; left: 12px; right: 12px; width: calc(100% - 24px); max-height: calc(100% - 76px); }
  .town-hint { font-size: 10px; border-radius: 8px; }
}
</style>
