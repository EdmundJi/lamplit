<script setup lang="ts">
/**
 * 沉浸式小镇外壳：全屏容器 + 世界 HUD（跑步开关、dock）+ 面板窗口 + 世界能力（走到某处弹出的动作
 * 菜单 + 统一反馈）。不离开这个页面就能用到系统里的全部功能——面板走 panel.types.ts 的契约，渲染
 * 实现在 ./panels/**（另一个 agent 维护）；能力走 world-actions.ts 的注册表，业务侧后续会往里注册
 * 自己的能力，这里只负责触发和反馈的统一出口。
 */
import { computed, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Film, Footprints, HelpCircle, Minimize2, Rabbit } from 'lucide-vue-next'
import { onDataChanged } from '../../../shared/data-sync'
import { useTownStore } from '../town.store'
import { useTownNpcStore } from '../town-npc.store'
import TownOnboarding from '../TownOnboarding.vue'
import { TownControls } from '../town-controls'
import type { TownGame, TownSelection } from '../town.engine'
import type { TownModel } from '../town.types'
import { worldPanels } from './panels/manifest'
import { worldBridgeKey } from './panel.types'
import type { WorldBridge, WorldEvent, WorldPanelKey } from './panel.types'
import { anchorForSelection, useImmersiveStore } from './immersive.store'
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
const immersive = useImmersiveStore()
const townNpcStore = useTownNpcStore()
const observing = ref(false)
const router = useRouter()

const root = ref<HTMLElement | null>(null)
const canvas = ref<HTMLElement | null>(null)
const onboardingRef = ref<InstanceType<typeof TownOnboarding> | null>(null)
const engineError = ref('')
const nativeFullscreen = ref(false)
const selection = ref<TownSelection>(null)
const feedback = ref<InstanceType<typeof WorldFeedback> | null>(null)
const playerPosition = ref<{ x: number; y: number } | null>(null)
const distanceToGuide = ref<number | null>(null)
// TownGame 只有 setNight/enterAcademy/exitAcademy 这几个 setter，没有对应的查询方法，
// 这两个状态由外壳自己记着，能力调用时读它们、改了它们再驱动引擎。
const night = ref(new Date().getHours() >= 18 || new Date().getHours() < 6)
const insideAcademy = ref(false)
/** 玩家手动关掉了当前锚点的动作菜单：在下一次锚点变化之前不再弹出。 */
const menuDismissed = ref(false)

let game: TownGame | null = null
let controls: TownControls | null = null
let mountSequence = 0
let residentSignature = ''
let leavingImmersive = false

const residents = computed(() => store.model?.residents ?? [])
const self = computed(() => residents.value.find(item => item.isSelf) ?? null)
const currentAnchor = computed(() => anchorForSelection(selection.value, self.value?.publicId ?? null))
const anyPanelOpen = computed(() => immersive.windows.length > 0)

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
      onSelect: value => { selection.value = value },
      onAcademyChange: inside => { insideAcademy.value = inside },
      onPlayerMove: (x, y) => { playerPosition.value = { x, y } },
      onDistanceToGuide: distance => { distanceToGuide.value = distance },
    })
    if (sequence !== mountSequence) { created.destroy(); return }
    game = created
    game.setRun(immersive.runMode)
    await townNpcStore.load()
    if (game) game.applyNpcs(townNpcStore.npcs, townNpcStore.budget)
    game.setNight(night.value)
    residentSignature = residentKey(store.model)
  } catch (error) {
    // Phaser 加载失败时不影响 dock/面板：HUD 照常可用，只是画面暂时空着。
    engineError.value = (error as Error).message || '小镇画面暂时加载不出来'
  }
}

function handleWorldEvent(event: WorldEvent) {
  if (event.type === 'celebrate') { game?.celebrate(event.publicId); feedback.value?.handle(event, residentName(event.publicId)) }
  else if (event.type === 'focus') { game?.focus(event.publicId); feedback.value?.handle(event) }
  else if (event.type === 'toast') feedback.value?.handle(event)
  else if (event.type === 'open') immersive.openPanel(event.panel)
  else if (event.type === 'close') immersive.closeTopmost()
  else if (event.type === 'night') { night.value = event.value; game?.setNight(event.value) }
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
  if (!document.fullscreenElement && !leavingImmersive) { leavingImmersive = true; void router.push('/town') }
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
  const target = event.target as HTMLElement | null
  const typing = Boolean(target && ['INPUT', 'TEXTAREA'].includes(target.tagName))
  if (event.key === 'Escape') {
    // Esc 依次关：动作菜单 > 最上层窗口 > 退出沉浸模式。
    if (menuActions.value.length) { event.preventDefault(); dismissMenu() }
    else if (immersive.topmost) { event.preventDefault(); immersive.closeTopmost() }
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

onMounted(async () => {
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
  await enterFullscreen()
  if (store.model) await mountGame()
  else await store.load()
  store.startPolling()
})

watch(selection, sel => {
  const anchor = anchorForSelection(sel, self.value?.publicId ?? null)
  menuDismissed.value = false
  immersive.openForAnchor(anchor, worldPanels)
})

// 面板里做的事要在小镇里看得见：任务/目标等数据一变，就静默刷新一次小镇模型。
const stopDataSync = onDataChanged(['tasks', 'today', 'goals', 'attributes', 'achievements'], () => { void store.load({ silent: true }) })

watch(() => store.model, async model => {
  if (!model) return
  if (game && residentKey(model) === residentSignature) game.applyModel(model)
  else await mountGame()
})

watch(() => store.lastCelebrations, celebrations => {
  if (!game) return
  for (const item of celebrations) game.celebrate(item.publicId)
})

onBeforeUnmount(() => {
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
  <section ref="root" class="immersive-town">
    <TownOnboarding
      v-if="self"
      ref="onboardingRef"
      :user-id="self.publicId"
      :player-position="playerPosition"
      :distance-to-guide="distanceToGuide"
      :any-panel-open="anyPanelOpen"
    />

    <div ref="canvas" class="immersive-canvas" data-testid="immersive-canvas" />
    <p v-if="store.loading && !store.model" class="immersive-status" role="status">正在把小镇搬进沉浸模式…</p>
    <p v-else-if="store.error" class="immersive-status immersive-status--error" role="alert">{{ store.error }}</p>
    <p v-else-if="engineError" class="immersive-status immersive-status--error" role="alert">画面暂时加载不出来，下面的功能仍然能用。</p>

    <WorldFeedback ref="feedback" />

    <WorldPanel
      v-for="item in openWindows"
      :key="item.key"
      :def="item.def"
      :x="item.x"
      :y="item.y"
      :z="item.z"
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

    <header class="immersive-topbar">
      <button class="icon-button" type="button" title="退出沉浸模式" aria-label="退出沉浸模式" @click="exitImmersive"><Minimize2 :size="18" /></button>
      <p class="immersive-title">成长小镇 · 沉浸模式<template v-if="!nativeFullscreen">（窗口内全屏）</template></p>
      <button class="secondary" type="button" :aria-pressed="observing" title="观察小镇：镜头脱离玩家自动巡游" aria-label="观察小镇" @click="toggleObservation"><Film :size="16" /></button>
      <button class="secondary" type="button" title="重新打开新手引导" @click="showOnboarding"><HelpCircle :size="16" /></button>
      <button class="secondary run-toggle" type="button" :aria-pressed="immersive.runMode" @click="worldBridge.setRunMode(!immersive.runMode)">
        <component :is="immersive.runMode ? Rabbit : Footprints" :size="16" />{{ immersive.runMode ? '奔跑中（R）' : '开始奔跑（R）' }}
      </button>
    </header>

    <div v-if="minimizedWindows.length" class="immersive-minimized" aria-label="最小化的窗口">
      <button v-for="item in minimizedWindows" :key="item.key" type="button" class="secondary" @click="immersive.focusPanel(item.key)">
        <component :is="item.def.icon" :size="14" />{{ item.def.title }}
      </button>
    </div>

    <nav class="immersive-dock" aria-label="小镇功能">
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
.immersive-canvas { position: absolute; inset: 0; }
.immersive-canvas :deep(canvas) { display: block; width: 100%; height: 100%; image-rendering: pixelated; }
.immersive-status { position: absolute; top: 64px; left: 50%; transform: translateX(-50%); margin: 0; padding: 8px 16px; border-radius: 999px; background: rgb(0 0 0 / 45%); color: #fff; font-size: 13px; }
.immersive-status--error { background: color-mix(in srgb, var(--danger) 70%, black); }
.immersive-topbar { position: relative; z-index: 6; display: flex; align-items: center; gap: 14px; padding: 10px 16px; background: color-mix(in srgb, var(--forest-deep) 82%, transparent); backdrop-filter: blur(10px); }
.immersive-topbar .icon-button { background: color-mix(in srgb, #fff 12%, transparent); color: var(--on-forest); }
.immersive-title { flex: 1; margin: 0; font-size: 13px; font-weight: 650; color: var(--on-forest); opacity: .9; }
.run-toggle[aria-pressed='true'] { background: var(--sun); color: var(--forest-deep); border-color: transparent; }
.immersive-minimized { position: relative; z-index: 6; display: flex; flex-wrap: wrap; gap: 8px; padding: 0 16px 8px; }
.immersive-minimized button { font-size: 12px; padding: 0 12px; }
.immersive-dock { position: relative; z-index: 6; margin-top: auto; display: flex; flex-wrap: wrap; justify-content: center; gap: 6px; padding: 10px 16px calc(10px + env(safe-area-inset-bottom)); background: color-mix(in srgb, var(--forest-deep) 82%, transparent); backdrop-filter: blur(10px); }
.dock-button { display: flex; flex-direction: column; align-items: center; gap: 3px; min-width: 64px; min-height: var(--control); padding: 6px 8px; border: 1px solid transparent; border-radius: var(--radius); background: transparent; color: var(--nav-faint); font-size: 11px; }
.dock-button:hover { background: color-mix(in srgb, #fff 10%, transparent); color: var(--on-forest); }
.dock-button[aria-pressed='true'] { background: color-mix(in srgb, var(--sun) 22%, transparent); color: var(--sun); border-color: color-mix(in srgb, var(--sun) 40%, transparent); }
@media (max-width: 760px) {
  .immersive-title { display: none; }
  .immersive-dock { justify-content: flex-start; overflow-x: auto; flex-wrap: nowrap; }
  .dock-button { min-width: 56px; flex: none; }
}
@media (prefers-reduced-motion: reduce) { .immersive-town * { transition: none !important; animation: none !important; } }
</style>
