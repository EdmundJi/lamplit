<script setup lang="ts">
import CompanionControls from '../CompanionControls.vue'
import NeighbourSchedule from './NeighbourSchedule.vue'
import { useTownCompanionLife } from '../companion-life'
import type { ConversationNotice } from '../npc-conversation'
import { useTownMailSignal } from '../town-mail-signal'
import ResidentMoment from '../ResidentMoment.vue'
import TownEventsBoard from '../TownEventsBoard.vue'
import { useTownEventsStore } from '../town-events'
/**
 * 沉浸式小镇外壳：全屏容器 + 世界 HUD（跑步开关、dock）+ 面板窗口 + 世界能力（走到某处弹出的动作
 * 菜单 + 统一反馈）。不离开这个页面就能用到系统里的全部功能——面板走 panel.types.ts 的契约，渲染
 * 实现在 ./panels/**（另一个 agent 维护）；能力走 world-actions.ts 的注册表，业务侧后续会往里注册
 * 自己的能力，这里只负责触发和反馈的统一出口。
 */
import { computed, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronDown, ChevronUp, Film, Footprints, Grid3x3, HelpCircle, Minimize2, Rabbit, Volume2, VolumeX } from 'lucide-vue-next'
import { onDataChanged } from '../../../shared/data-sync'
import { useTownStore } from '../town.store'
import { useTownNpcStore } from '../town-npc.store'
import TownOnboarding from '../TownOnboarding.vue'
import { TownControls } from '../town-controls'
import type { TownGame, TownSelection, TownTravel, TownNearby } from '../town.engine'
import type { TownModel } from '../town.types'
import { worldPanels } from './panels/manifest'
import { worldBridgeKey } from './panel.types'
import type { WorldBridge, WorldEvent, WorldPanelKey } from './panel.types'
import { anchorForSelection, useImmersiveStore } from './immersive.store'
import { registerTownUi } from '../town-probe'
import {
  registerBuiltinWorldActions,
  resolveWorldAction,
  runWorldAction,
  worldActionsFor,
} from './world-actions'
import type { WorldActionContext } from './world-actions'
import WorldPanel from './WorldPanel.vue'
import WorldActionMenu from './WorldActionMenu.vue'
import WorldFeedback from './WorldFeedback.vue'

const store = useTownStore()
const mailSignal = useTownMailSignal()
let socialTicker: ReturnType<typeof setInterval> | null = null
const immersive = useImmersiveStore()
const townNpcStore = useTownNpcStore()
const observing = ref(false)
const scenic = ref(false)
const hudActionsOpen = ref(false)
function toggleScenic() {
  scenic.value = !scenic.value
  if (scenic.value) hudActionsOpen.value = false
  game?.setScenic?.(scenic.value)
}
/** 场景模式只收起导航与操作提示；退出、声音和恢复入口始终保持可见。 */
function recoverScenicHud() {
  if (!scenic.value) return
  scenic.value = false
  game?.setScenic?.(false)
}
function recoverScenicFromPointer(event: PointerEvent) {
  if (!scenic.value) return
  // 恢复按钮自己会在 click 中切换；此处抢先恢复会让 click 又把界面收回去。
  if ((event.target as HTMLElement | null)?.closest('.scenic-restore, .scenic-toggle')) return
  recoverScenicHud()
}
function recoverScenicFromFocus(event: FocusEvent) {
  if ((event.target as HTMLElement | null)?.closest('.scenic-toggle')) return
  recoverScenicHud()
}
const soundEnabled = ref(false)
function toggleSound() {
  soundEnabled.value = !soundEnabled.value
  game?.setSoundEnabled(soundEnabled.value)
}
const activeRoom = ref<string | null>(null)
const eventsStore = useTownEventsStore()
const showEvents = ref(false)
const travel = ref<TownTravel | null>(null)
const nearby = ref<TownNearby | null>(null)
const clockText = ref('')
let clockTimer: ReturnType<typeof setInterval> | null = null
/** 与服务端时钟的偏移：HUD 用它显示与引擎世界一致的“现在”。 */
let serverClockOffsetMs = 0
function syncServerClock(model: TownModel | null) {
  const parsed = model?.serverTime ? new Date(model.serverTime).getTime() : Number.NaN
  serverClockOffsetMs = Number.isFinite(parsed) ? parsed - Date.now() : 0
  refreshClockText()
}
function refreshClockText() {
  clockText.value = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', timeZone: self.value?.timezone ?? 'Asia/Shanghai' }).format(new Date(Date.now() + serverClockOffsetMs))
}
const destinations = [{ id: 'neighbourhood', label: '邻里住宅区' }, { id: 'home', label: '我的家' }, { id: 'academy', label: '学院' }, { id: 'gym', label: '健身房' }, { id: 'cafe', label: '咖啡馆' }, { id: 'terrace', label: '街角露台' }, { id: 'park', label: '公园' }, { id: 'plaza', label: '广场' }]
function visitPlace(id: string) {
  if (observing.value) { observing.value = false; game?.setObservation(false) }
  selection.value = null
  showEvents.value = false
  game?.travelTo?.(id)
}
function recoverPosition() {
  selection.value = null
  showEvents.value = false
  if (game?.recover?.()) feedback.value?.result(true, '已回到家门口的安全位置。')
  else void mountGame()
}
function stopTravel() { game?.cancelTravel?.() }
function interactNearby() { game?.interactNearby?.() }
function leaveRoom() { game?.exitRoom(); game?.exitAcademy() }
const router = useRouter()

const root = ref<HTMLElement | null>(null)
const canvas = ref<HTMLElement | null>(null)
const onboardingRef = ref<InstanceType<typeof TownOnboarding> | null>(null)
const engineError = ref('')
const nativeFullscreen = ref(false)
const selection = ref<TownSelection>(null)
const selectedNpc = computed(() => {
  const code = selection.value?.startsWith('npc:') ? selection.value.slice(4) : ''
  const npc = townNpcStore.byCode(code)
  return npc?.layer === 2 ? npc : null
})
const conversationCode = computed(() => {
  if (selectedNpc.value) return selectedNpc.value.code
  if (selection.value === 'npc:assistant' && immersive.windows.some(w => w.key === 'ai' && !w.minimized)) return 'GUIDE'
  if (selection.value === 'npc:postman' && immersive.windows.some(w => w.key === 'friends' && !w.minimized)) return 'POSTMAN'
  return null
})
const conversationNotice = ref<ConversationNotice | null>(null)
function onConversationChange(notice: ConversationNotice | null) {
  conversationNotice.value = notice
  if (notice?.phase === 'ended' && conversationCode.value === notice.npcCode) {
    selection.value = null
    if (notice.npcInitiated) feedback.value?.result(true, `${notice.name}先去忙了。${notice.reason ?? ''}`)
  }
}

const feedback = ref<InstanceType<typeof WorldFeedback> | null>(null)
const playerPosition = ref<{ x: number; y: number } | null>(null)
const distanceToGuide = ref<number | null>(null)
// 当前渲染的昼夜状态由引擎同步；动作菜单只读取它来给“切到晨/昏”命名。
const night = ref(new Date().getHours() >= 18 || new Date().getHours() < 6)
/** 仅“切到夜晚/白天”是临时预览；正常情况下引擎按用户时区连续推进。 */
const manualTimeOverride = ref(false)
function restoreAutomaticTime() {
  manualTimeOverride.value = false
  game?.setAutomaticTime()
}
const insideAcademy = ref(false)
/** 玩家手动关掉了当前锚点的动作菜单：在下一次锚点变化之前不再弹出。 */
const menuDismissed = ref(true)

let game: TownGame | null = null
function interactHomeObject(id: 'journal' | 'mailbox' | 'leash') { game?.interactHomeObject?.(id) }
function interactCompanion() { game?.interactCompanion?.() }
let controls: TownControls | null = null
let mountSequence = 0
let residentSignature = ''
let leavingImmersive = false

const residents = computed(() => store.model?.residents ?? [])
const self = computed(() => residents.value.find(item => item.isSelf) ?? null)
const companionLife = useTownCompanionLife({ userId: computed(() => self.value?.publicId ?? ''), room: activeRoom, game: () => game, openPanel: key => immersive.openPanel(key), feedback: text => feedback.value?.handle({ type: 'toast', text }) })
const currentAnchor = computed(() => anchorForSelection(selection.value, self.value?.publicId ?? null))
const anyPanelOpen = computed(() => immersive.windows.length > 0 || selectedNpc.value !== null)

const openWindows = computed(() => {
  const defs: { key: WorldPanelKey; x: number; y: number; z: number; def: (typeof worldPanels)[number] }[] = []
  for (const win of immersive.windows) {
    if (win.minimized) continue
    const def = worldPanels.find(panel => panel.key === win.key)
    if (def) defs.push({ key: win.key, x: win.x, y: win.y, z: win.z, def })
  }
  return defs
})
const minimizedWindows = computed(() => {
  const defs: { key: WorldPanelKey; def: (typeof worldPanels)[number] }[] = []
  for (const win of immersive.windows) {
    if (!win.minimized) continue
    const def = worldPanels.find(panel => panel.key === win.key)
    if (def) defs.push({ key: win.key, def })
  }
  return defs
})

function residentKey(model: TownModel | null) {
  return model ? model.residents.map(item => item.publicId).join(',') : ''
}

function residentName(publicId: string) {
  return residents.value.find(item => item.publicId === publicId)?.displayName
}

async function mountGame() {
  if (!canvas.value || !store.model) return
  const sequence = ++mountSequence
  engineError.value = ''
  try {
    const { createTownGame } = await import('../town.engine')
    if (sequence !== mountSequence) return
    game?.destroy()
    game = null
    const created = await createTownGame(canvas.value, store.model, {
      onConversationChange,
      onCompanionModeChange: companionLife.onModeChange,
      onCompanionPlaceChange: companionLife.onPlaceChange,
      onCompanionInteract: companionLife.onInteract,
      onTimeChange: isNight => { night.value = isNight },
      onSelect: value => { selection.value = value },
      onObservationChange: enabled => { observing.value = enabled },
      onTravelChange: value => { travel.value = value },
      onNearbyChange: value => { nearby.value = value },
      onAcademyChange: inside => { insideAcademy.value = inside; activeRoom.value = inside ? 'academy' : null },
      onRoomChange: room => { activeRoom.value = room; selection.value = null; travel.value = null },
      onPresenceReport: payload => { void store.reportPresence(payload) },
      onPlayerMove: (x, y) => { playerPosition.value = { x, y } },
      onDistanceToGuide: distance => { distanceToGuide.value = distance },
      // 室内点书桌/成就墙/宠物窝 → 走同一份世界能力注册表，而不是让场景自己知道该开哪个面板。
      // 护栏 A：把这一次主动搭话记到服务端，再用权威额度校正引擎的乐观值。
      onInitiativeSpent: () => { void townNpcStore.consumeInitiative().then(budget => game?.setInitiativeBudget(budget)).catch(() => {}) },
      onInteriorInteract: (actionId, id) => { void worldBridge.run(actionId, id) },
    })
    if (sequence !== mountSequence) { created.destroy(); return }
    game = created
    companionLife.sync()
    game.setLetterUnread?.(mailSignal.unreadCount)
    game.setRun(immersive.runMode)
    // 初次进入默认静音；模型重建时保留玩家已选声音和手动晨昏预览。
    game.setSoundEnabled(soundEnabled.value)
    if (manualTimeOverride.value) game.setNight(night.value)
    else game.setAutomaticTime()
    await townNpcStore.load()
    if (sequence !== mountSequence) return
    if (game) game.applyNpcs(townNpcStore.npcs, townNpcStore.budget)
    game?.applyEvents?.(eventsStore.events)
    if (conversationCode.value) game?.beginConversation?.(conversationCode.value)
    residentSignature = residentKey(store.model)
  } catch (error) {
    // Phaser 加载失败时不影响 dock/面板：HUD 照常可用，只是画面暂时空着。
    engineError.value = (error as Error).message || '小镇画面暂时加载不出来'
  }
}

function handleWorldEvent(event: WorldEvent) {
  if (event.type === 'companion') { void companionLife.act(event.action) }
  else if (event.type === 'mail-count') mailSignal.unreadCount = event.count
  else if (event.type === 'celebrate') { const id = residents.value.find(r => r.publicId === event.publicId)?.publicId ?? self.value?.publicId; if (id) { game?.celebrate(id); feedback.value?.handle({ ...event, publicId: id }, residentName(id)) } }
  else if (event.type === 'travel') visitPlace(event.place)
  else if (event.type === 'focus') { game?.focus(event.publicId); feedback.value?.handle(event) }
  else if (event.type === 'toast') feedback.value?.handle(event)
  else if (event.type === 'open') immersive.openPanel(event.panel)
  else if (event.type === 'close') immersive.closeTopmost()
  else if (event.type === 'night') {
    manualTimeOverride.value = true
    night.value = event.value
    game?.setNight(event.value)
  }
  else if (event.type === 'academy') {
    insideAcademy.value = event.value
    if (event.value) game?.enterAcademy()
    else game?.exitAcademy()
  }
}

function buildActionContext(payload?: unknown): WorldActionContext {
  return {
    bridge: worldBridge,
    anchor: currentAnchor.value,
    selfPublicId: self.value?.publicId ?? null,
    night: night.value,
    insideAcademy: insideAcademy.value,
    refresh: () => store.load({ silent: true }),
    payload,
  }
}

const worldBridge: WorldBridge = {
  emit: handleWorldEvent,
  get runMode() { return immersive.runMode },
  setRunMode(enabled: boolean) {
    immersive.setRunMode(enabled)
    game?.setRun(enabled)
    controls?.setRunMode(enabled ? 'run' : 'walk')
  },
  async run(actionId: string, payload?: unknown) {
    const result = await runWorldAction(actionId, buildActionContext(payload))
    if (result.message) feedback.value?.result(result.ok, result.message)
    return { ok: result.ok, message: result.message }
  },
}
provide(worldBridgeKey, worldBridge)

/** 当前锚点旁能做的事：手动关掉动作菜单后，要走到别处（或离开再回来）才会再弹出来。 */
const menuActions = computed(() => {
  if (menuDismissed.value || !currentAnchor.value) return []
  const ctx = buildActionContext()
  return worldActionsFor({ anchor: currentAnchor.value }).map(action => resolveWorldAction(action, ctx))
})

async function handleRunAction(actionId: string) {
  await worldBridge.run(actionId)
}

function dismissMenu() {
  menuDismissed.value = true
  const anchor = currentAnchor.value
  if (anchor) {
    const panel = worldPanels.find(item => item.anchor === anchor)
    if (panel) immersive.closePanel(panel.key)
  }
}

async function enterFullscreen() {
  try {
    await root.value?.requestFullscreen?.()
  } catch {
    // 请求被拒绝或平台不支持：容器本身已经是覆盖视口的固定定位，照样可用。
  }
}

function onFullscreenChange() {
  nativeFullscreen.value = document.fullscreenElement === root.value

}

async function exitImmersive() {
  if (leavingImmersive) return
  leavingImmersive = true
  if (document.fullscreenElement) { try { await document.exitFullscreen() } catch { /* 忽略 */ } }
  void router.push('/town')
}

function toggleObservation() {
  observing.value = !observing.value
  game?.setObservation(observing.value)
}

function showOnboarding() {
  onboardingRef.value?.restart()
}

function onKeydown(event: KeyboardEvent) {
  if (event.defaultPrevented) return
  const target = event.target as HTMLElement | null
  const typing = Boolean(target && ['INPUT', 'TEXTAREA'].includes(target.tagName))
  if (event.key === 'Escape') {
    if (scenic.value) { event.preventDefault(); toggleScenic(); return }
    // Esc 依次关：动作菜单 > 最上层窗口 > 退出沉浸模式。
    if (showEvents.value) { event.preventDefault(); showEvents.value = false }
    else if (travel.value?.phase === 'walking') { event.preventDefault(); game?.cancelTravel?.() }
    else if (selectedNpc.value) { event.preventDefault(); selection.value = null }
    else if (observing.value) { event.preventDefault(); toggleObservation() }
    else if (menuActions.value.length) { event.preventDefault(); dismissMenu() }
    else if (immersive.topmost) { event.preventDefault(); immersive.closeTopmost() }
    else if (activeRoom.value) { event.preventDefault(); if (!game?.cancelRoomAction?.()) leaveRoom() }
    else void exitImmersive()
    return
  }
  if (!typing && controls?.handleKeyDown(event)) {
    event.preventDefault()
  }
}

function onKeyup(event: KeyboardEvent) {
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
  syncServerClock(store.model)
  refreshClockText()
  clockTimer = setInterval(refreshClockText, 30_000)
  void eventsStore.load()
  immersive.hydrate(worldPanels)
  registerBuiltinWorldActions()

  // 初始化控制器
  controls = new TownControls({
    onRunModeChange: (mode) => {
      const enabled = mode === 'run'
      immersive.setRunMode(enabled)
      game?.setRun(enabled)
    },
    onShiftChange: () => {
      game?.setRun(controls?.isRunning ?? false)
    },
  })

  document.addEventListener('keydown', onKeydown)
  document.addEventListener('keyup', onKeyup)
  document.addEventListener('fullscreenchange', onFullscreenChange)

  if (store.model) await mountGame()
  else await store.load()
  store.startPolling()
})

watch(() => eventsStore.events, events => game?.applyEvents?.(events))

watch(selection, sel => {
  const anchor = anchorForSelection(sel, self.value?.publicId ?? null)
  menuDismissed.value = true
  // 到家、学院或普通地点先进入空间本身。业务面板只在明确的 NPC 交谈入口自动打开，
  // 其他功能仍可通过 dock 或“更多地点操作”主动进入。
  if (anchor === 'npc:assistant' || anchor === 'npc:postman') immersive.openForAnchor(anchor, worldPanels)
})

// 面板里做的事要在小镇里看得见：任务/目标等数据一变，就静默刷新一次小镇模型。
const stopDataSync = onDataChanged(['tasks', 'today', 'goals', 'attributes', 'achievements'], () => { void store.load({ silent: true }) })

watch(() => store.model, async model => {
  if (!model) return
  syncServerClock(model)
  if (game && residentKey(model) === residentSignature) game.applyModel(model)
  else await mountGame()
})

watch(() => store.lastCelebrations, celebrations => {
  if (!game) return
  for (const item of celebrations) game.celebrate(item.publicId)
})

// 诊断探针只能从 DOM 上刮到面板标题，刮不到 store 里的真实键名和 dock 状态。这里把权威值
// 喂进去，让 window.__town.snapshot().ui 是可断言的结构，而不是一堆界面文案。
const stopProbeUi = import.meta.env.DEV
  ? registerTownUi(() => ({
      route: '/town/immersive',
      panelsOpen: immersive.windows.filter(item => !item.minimized).map(item => item.key),
      panelsMinimized: immersive.windows.filter(item => item.minimized).map(item => item.key),
      dockCollapsed: immersive.dockCollapsed,
    }))
  : null

watch(() => mailSignal.unreadCount, count => game?.setLetterUnread?.(count))

watch(() => Boolean(activeRoom.value) || openWindows.value.length > 0 || Boolean(selectedNpc.value) || showEvents.value, opened => {
  if (opened) recoverScenicHud()
})

onBeforeUnmount(() => {
  if (socialTicker) clearInterval(socialTicker)
  mountSequence++
  stopProbeUi?.()
  if (clockTimer) clearInterval(clockTimer)
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('keyup', onKeyup)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  stopDataSync()
  store.stopPolling()
  controls?.destroy()
  controls = null
  game?.destroy()
  game = null
})
</script>

<template>
  <section ref="root" class="immersive-town" :class="{ 'is-scenic': scenic }" @focusin="recoverScenicFromFocus" @pointerdown.capture="recoverScenicFromPointer">
    <button v-if="scenic" class="scenic-restore" type="button" @click="recoverScenicHud">显示导航与提示 · Esc</button>
    <TownOnboarding
      v-if="self"
      ref="onboardingRef"
      :user-id="self.publicId"
      :player-position="playerPosition"
      :distance-to-guide="distanceToGuide"
      :any-panel-open="anyPanelOpen"
      :conversation-open="Boolean(selectedNpc) || selection === 'npc:assistant' || selection === 'npc:postman'"
    />

    <div ref="canvas" class="immersive-canvas" data-testid="immersive-canvas" />
    <p v-if="store.loading && !store.model" class="immersive-status" role="status">正在把小镇搬进沉浸模式…</p>
    <p v-else-if="store.error" class="immersive-status immersive-status--error" role="alert">{{ store.error }}</p>
    <p v-else-if="engineError" class="immersive-status immersive-status--error" role="alert">画面暂时加载不出来，下面的功能仍然能用。</p>

    <WorldFeedback ref="feedback" />
    <div v-if="activeRoom === 'home' && !openWindows.length" class="home-object-shortcuts" aria-label="家中的物品" @pointerdown.stop @keydown.stop>
      <button type="button" @click="interactHomeObject('journal')">翻手账</button>
      <button type="button" @click="interactHomeObject('mailbox')">取信</button>
      <button type="button" @click="interactHomeObject('leash')">{{ companionLife.companion.pet ? '拿牵引绳' : '选择伙伴' }}</button>
    </div>
    <div v-if="!activeRoom && !openWindows.length && !selectedNpc && !observing && companionLife.companion.mode !== 'home'" class="town-companion-hud">
      <CompanionControls :in-park="companionLife.inPark.value" :in-home="false" @home="visitPlace('home')" @choose="immersive.openPanel('partners')" @walk="companionLife.act('walk')" @stroke="interactCompanion" />
    </div>
    <div v-if="conversationNotice?.phase === 'leaving'" class="travel-status" role="status">{{ conversationNotice.name }}：{{ conversationNotice.reason }}</div>
    <div v-if="showEvents" class="immersive-events"><TownEventsBoard @close="showEvents = false" @visit="visitPlace" /></div>
    <div v-if="travel?.phase === 'walking'" class="travel-status" role="status">正在走向{{ travel.label }}<button type="button" @click="stopTravel">停下 · Esc</button></div>
    <div v-else-if="travel?.phase === 'blocked'" class="travel-status" role="status">暂时走不到{{ travel.label }}<button type="button" @click="stopTravel">知道了</button></div>
    <button v-if="nearby && !activeRoom && !observing && !selectedNpc && !openWindows.length && travel?.phase !== 'walking'" class="nearby-action" type="button" @click="interactNearby">{{ nearby.action }}{{ nearby.label }} · E</button>
    <div v-if="townNpcStore.error" class="town-roster-error" role="alert">{{ townNpcStore.error }} <button type="button" @click="mountGame">重新连接</button></div>
    <nav v-if="!observing && !activeRoom && !scenic" class="town-wayfinder" aria-label="小镇地点">
      <span>去哪里走走</span>
      <button v-for="place in destinations" :key="place.id" type="button" @click="visitPlace(place.id)">{{ place.label }}</button>
    </nav>
    <button v-if="activeRoom" class="town-leave-room" type="button" @click="leaveRoom">回到街上</button>
    <p v-if="!observing && !activeRoom && !scenic" class="town-controls-hint">方向键 / WASD 行走 · Shift 奔跑 · 滚轮缩放 · 拖动看风景</p>
    <div v-if="selectedNpc" class="immersive-moment"><ResidentMoment :npc="selectedNpc" :conversation-state="conversationNotice?.phase" :leaving-reason="conversationNotice?.phase === 'leaving' ? conversationNotice.reason : undefined" @close="selection = null" /></div>
    <div v-if="observing" class="observation-caption">
      <span>小镇正在过它的一天</span>
      <p>看居民散步、相遇，听几句路边闲谈。</p>
      <button type="button" @click="toggleObservation">回到我身边 · Esc</button>
    </div>

    <WorldPanel
      v-for="item in openWindows"
      :key="item.key"
      :def="item.def"
      :x="item.x"
      :y="item.y"
      :z="20 + item.z"
      @close="immersive.closePanel(item.key)"
      @minimize="immersive.minimizePanel(item.key)"
      @focus="immersive.focusPanel(item.key)"
      @move="(x, y) => immersive.movePanel(item.key, x, y)"
    />

    <WorldActionMenu
      v-if="menuActions.length"
      :actions="menuActions"
      @run="handleRunAction"
      @close="dismissMenu"
    />

    <header class="immersive-topbar" :class="{ 'is-actions-open': hudActionsOpen }">
      <button class="icon-button" type="button" title="退出沉浸模式" aria-label="退出沉浸模式" @click="exitImmersive"><Minimize2 :size="18" /></button>
      <div class="immersive-title"><strong>成长小镇</strong><span>{{ activeRoom ? '屋内时光' : '慢慢走，生活正在发生' }} · {{ clockText }}</span></div>
      <NeighbourSchedule :npcs="townNpcStore.npcs" :time="clockText" @visit="visitPlace" />
      <button class="secondary sound-toggle" type="button" :aria-pressed="soundEnabled" :title="soundEnabled ? '关闭环境声' : '打开环境声'" @click="toggleSound">
        <component :is="soundEnabled ? Volume2 : VolumeX" :size="16" />{{ soundEnabled ? '环境声开' : '环境声关' }}
      </button>
      <button v-if="manualTimeOverride" class="secondary" type="button" title="恢复按当前时区推进的晨昏" @click="restoreAutomaticTime">恢复随时间</button>
      <button class="secondary hud-actions-toggle" type="button" :aria-expanded="hudActionsOpen" aria-controls="immersive-secondary-actions" @click="hudActionsOpen = !hudActionsOpen">操作</button>
      <div id="immersive-secondary-actions" class="immersive-secondary-actions">
        <button class="secondary" type="button" aria-label="返回安全位置" title="卡住时直接回到家门口，任务数据不变" @click="recoverPosition">脱困</button>
        <button class="secondary" type="button" @click="immersive.openPanel('friends')">信箱<span v-if="mailSignal.unreadCount"> · {{ mailSignal.unreadCount }}</span></button>
        <button v-if="currentAnchor" class="secondary" type="button" aria-label="更多地点操作" @click="menuDismissed = !menuDismissed">更多</button>
        <button class="secondary" type="button" aria-label="小镇活动" @click="showEvents = !showEvents">活动</button>
        <button v-if="!nativeFullscreen" class="secondary fullscreen-button" type="button" title="全屏显示" @click="enterFullscreen">全屏</button>
        <button class="secondary scenic-toggle" type="button" :disabled="openWindows.length > 0 || Boolean(selectedNpc) || showEvents" @click="toggleScenic">{{ scenic ? '显示提示' : '收起界面' }}</button>
        <button class="secondary" type="button" :aria-pressed="observing" title="观察小镇：镜头脱离玩家自动巡游" aria-label="观察小镇" @click="toggleObservation"><Film :size="16" /></button>
        <button class="secondary" type="button" title="重新打开新手引导" @click="showOnboarding"><HelpCircle :size="16" /></button>
        <button class="secondary run-toggle" type="button" :aria-pressed="immersive.runMode" @click="worldBridge.setRunMode(!immersive.runMode)">
          <component :is="immersive.runMode ? Rabbit : Footprints" :size="16" />{{ immersive.runMode ? '奔跑中（R）' : '开始奔跑（R）' }}
        </button>
      </div>
    </header>

    <div v-if="minimizedWindows.length" class="immersive-minimized" aria-label="最小化的窗口">
      <button v-for="item in minimizedWindows" :key="item.key" type="button" class="secondary" @click="immersive.focusPanel(item.key)">
        <component :is="item.def.icon" :size="14" />{{ item.def.title }}
      </button>
    </div>

    <!-- dock 默认收起（M5-4），所以这个把手必须常驻可见——否则收起后就再也打不开了。 -->
    <div v-if="!scenic" class="immersive-dock-handle">
      <button
        type="button"
        class="secondary"
        :aria-expanded="!immersive.dockCollapsed"
        aria-controls="immersive-dock"
        :title="immersive.dockCollapsed ? '展开功能栏' : '收起功能栏'"
        @click="immersive.toggleDock()"
      >
        <component :is="immersive.dockCollapsed ? ChevronUp : ChevronDown" :size="14" />
        <Grid3x3 :size="14" />
        {{ immersive.dockCollapsed ? '功能' : '收起' }}
      </button>
    </div>

    <nav v-if="!scenic && !immersive.dockCollapsed" id="immersive-dock" class="immersive-dock" aria-label="小镇功能">
      <button
        v-for="panel in worldPanels"
        :key="panel.key"
        type="button"
        class="dock-button"
        :aria-pressed="immersive.isOpen(panel.key)"
        :title="panel.subtitle"
        @click="immersive.openPanel(panel.key)"
      >
        <component :is="panel.icon" :size="20" aria-hidden="true" />
        <span>{{ panel.title }}</span>
      </button>
    </nav>

  </section>
</template>

<style scoped>
.immersive-town { position: fixed; inset: 0; z-index: 40; display: flex; flex-direction: column; background: var(--forest); color: var(--on-forest); overflow: hidden; }
.immersive-events { position: absolute; z-index: 30; right: 20px; top: 90px; width: min(380px, calc(100% - 40px)); max-height: calc(100dvh - 180px); overflow: auto; }
.travel-status, .nearby-action { position: absolute; z-index: 8; bottom: 90px; left: 50%; transform: translateX(-50%); padding: 12px 18px; border-radius: 12px; background: #fff9ee; color: #355b44; box-shadow: var(--shadow); border: 1px solid #dccdb5; max-width: calc(100% - 32px); font-size: 13px; }
.travel-status button { margin-left: 12px; color: #355b44; background: #e9ebda; }
.immersive-moment { position: absolute; right: 20px; bottom: 76px; z-index: 7; max-height: calc(100dvh - 150px); overflow-y: auto; }
.observation-caption { position: absolute; left: 50%; bottom: 80px; transform: translateX(-50%); text-align: center; padding: 16px 24px; width: max-content; max-width: calc(100vw - 64px); border: 1px solid #ffffff25; border-radius: 16px; background: #172e2adb; backdrop-filter: blur(12px); color: #fff9ee; }
.observation-caption span { font-size: 16px; letter-spacing: .15em; }
.observation-caption p { font-size: 12px; opacity: .75; }
.observation-caption button { border: 1px solid #ffffff40; border-radius: 8px; background: transparent; color: inherit; padding: 8px 14px; cursor: pointer; }
.immersive-canvas { position: absolute; inset: 0; }
.immersive-canvas :deep(canvas) { display: block; width: 100%; height: 100%; image-rendering: pixelated; }
.immersive-status { position: absolute; top: 64px; left: 50%; transform: translateX(-50%); margin: 0; padding: 8px 16px; border-radius: 999px; background: rgb(0 0 0 / 45%); color: #fff; font-size: 13px; }
.immersive-status--error { background: color-mix(in srgb, var(--danger) 70%, black); }
.immersive-topbar { position: relative; z-index: 6; display: flex; align-items: center; gap: 14px; padding: 10px 16px; background: color-mix(in srgb, var(--forest-deep) 82%, transparent); backdrop-filter: blur(10px); }
.immersive-topbar .icon-button { background: color-mix(in srgb, #fff 12%, transparent); color: var(--on-forest); }
.immersive-secondary-actions { display: flex; align-items: center; gap: 14px; }
.hud-actions-toggle { display: none; }
.immersive-title { flex: 1; margin: 0; font-size: 13px; font-weight: 650; color: var(--on-forest); opacity: .9; }
.immersive-title strong { display: block; font-size: 15px; letter-spacing: .12em; }
.immersive-title span { display: block; font-size: 11px; margin-top: 4px; opacity: .65; }
.town-wayfinder { position: absolute; z-index: 6; left: 18px; top: 84px; display: flex; flex-wrap: wrap; align-items: center; gap: 4px; max-width: calc(100% - 36px); padding: 8px; border: 1px solid #ffffff30; border-radius: 14px; background: #18352be6; box-shadow: 0 8px 25px #142d2220; backdrop-filter: blur(12px); }
.town-wayfinder span { padding: 0 9px; font-size: 11px; color: #dbd3ba; }
.town-wayfinder button { border: 0; border-radius: 8px; background: transparent; color: #fff9ee; padding: 8px 10px; cursor: pointer; font-size: 12px; }
.town-wayfinder button:hover, .town-wayfinder button:focus-visible { background: #ffffff20; }
.town-controls-hint { position: absolute; bottom: 16px; left: 20px; margin: 0; padding: 8px 12px; border-radius: 10px; font-size: 11px; background: #18352bd9; color: #f2eddb; pointer-events: none; }
.town-leave-room { position: absolute; z-index: 8; top: 88px; left: 20px; border: 1px solid #ded8c4; border-radius: 10px; padding: 12px 16px; background: #fff9ee; color: #355b44; cursor: pointer; }
.town-roster-error { position: absolute; top: 145px; left: 20px; z-index: 8; padding: 12px; border-radius: 12px; background: #fff9ee; color: #59382c; }
.run-toggle[aria-pressed='true'] { background: var(--sun); color: var(--forest-deep); border-color: transparent; }
.immersive-minimized { position: relative; z-index: 6; display: flex; flex-wrap: wrap; gap: 8px; padding: 0 16px 8px; }
.immersive-minimized button { font-size: 12px; padding: 0 12px; }
.immersive-dock-handle { position: relative; z-index: 6; margin-top: auto; display: flex; justify-content: center; padding: 6px 16px calc(6px + env(safe-area-inset-bottom)); }
/* dock 展开时它自己带底部安全区内边距，把手就不用再留一份。 */
.immersive-town:has(.immersive-dock) .immersive-dock-handle { padding-bottom: 6px; }
.immersive-dock-handle button { gap: 4px; font-size: 12px; padding: 0 12px; }
.immersive-dock { position: relative; z-index: 6; display: flex; flex-wrap: wrap; justify-content: center; gap: 6px; padding: 10px 16px calc(10px + env(safe-area-inset-bottom)); background: color-mix(in srgb, var(--forest-deep) 82%, transparent); backdrop-filter: blur(10px); }
.dock-button { display: flex; flex-direction: column; align-items: center; gap: 3px; min-width: 64px; min-height: var(--control); padding: 6px 8px; border: 1px solid transparent; border-radius: var(--radius); background: transparent; color: var(--nav-faint); font-size: 11px; }
.dock-button:hover { background: color-mix(in srgb, #fff 10%, transparent); color: var(--on-forest); }
.dock-button[aria-pressed='true'] { background: color-mix(in srgb, var(--sun) 22%, transparent); color: var(--sun); border-color: color-mix(in srgb, var(--sun) 40%, transparent); }
@media (max-width: 760px) {
  .immersive-topbar { gap: 6px; padding: 8px; }
  .immersive-topbar .secondary { min-width: 36px; padding: 0 8px; }
  .immersive-topbar .run-toggle { font-size: 0; gap: 0; width: 40px; flex: none; }
  .immersive-topbar button[title="重新打开新手引导"] { display: none; }
  .immersive-title, .fullscreen-button { display: none; }
  .town-wayfinder { top: 76px; }
  .town-wayfinder span, .town-controls-hint { display: none; }
  .town-wayfinder button { padding: 8px; }
  .immersive-dock { justify-content: flex-start; overflow-x: auto; flex-wrap: nowrap; }
  .dock-button { min-width: 56px; flex: none; }
}
@media (max-width: 520px) {
  .immersive-title { display: none; }
  .hud-actions-toggle { display: inline-flex; }
  .immersive-secondary-actions { display: none; position: absolute; right: 8px; top: calc(100% + 6px); z-index: 12; width: min(252px, calc(100vw - 16px)); padding: 8px; border: 1px solid #ffffff35; border-radius: 12px; background: #18352bf2; box-shadow: var(--shadow); flex-wrap: wrap; justify-content: flex-end; }
  .immersive-topbar.is-actions-open .immersive-secondary-actions { display: flex; }
  .immersive-secondary-actions .secondary { min-width: 72px; }
  .sound-toggle { font-size: 0; gap: 0; width: 40px; flex: none; }
  .immersive-topbar > button[title="恢复按当前时区推进的晨昏"] { font-size: 11px; }
}
@media (prefers-reduced-motion: reduce) { .immersive-town * { transition: none !important; animation: none !important; } }
.scenic-restore { position: absolute; right: 18px; bottom: 16px; z-index: 60; border: 1px solid #ffffff4d; background: #24372ce8; color: #f8efda; padding: 8px 12px; min-height: 34px; font-size: 11px; box-shadow: 0 4px 16px #142d2266; }
.home-object-shortcuts { position: absolute; z-index: 8; right: 18px; bottom: 80px; display: flex; gap: 5px; padding: 7px; background: #f8f0dfed; border: 1px solid #cbbd9f; border-radius: 10px; }
.home-object-shortcuts button { color: #3f5e49; background: transparent; border: 0; padding: 8px 10px; cursor: pointer; font-size: 12px; }
.town-companion-hud { position: absolute; z-index: 8; right: 18px; bottom: 88px; }
@media (max-width: 520px) { .town-companion-hud { right: 12px; bottom: 155px; } .home-object-shortcuts { right: 12px; bottom: 72px; max-width: calc(100% - 24px); } }
</style>
