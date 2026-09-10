<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Coins, HandHeart, House, Minus, MoreHorizontal, PawPrint, Utensils, X } from 'lucide-vue-next'
import { api } from '../../shared/api/client'
import { notifyDataChanged, onDataChanged } from '../../shared/data-sync'
import { useDesktopPetStore } from './desktop-pet.store'
import { randomPetDialogue } from './pet-dialogues'
import { petVariantStorageKey, variantIndexForKind } from './pet-variants'
import type { InteractionResult, PartnerProfile, Pet, ShopItem } from './partner.types'
import type RivePetComponent from './RivePet.vue'

type Dialogue = { text: string; meta: string }
type Point = { x: number; y: number }

// The Rive runtime is a heavy chunk (canvas + wasm loader); it is not needed for
// first paint, so it is code-split and only fetched once DesktopPet actually
// resolves a pet to render, well after the idle-deferred profile load below.
// Unwrap `.default` explicitly rather than relying on Vue's automatic ESM-module
// interop detection (`__esModule` / `Symbol.toStringTag`), which real Vite dynamic
// imports satisfy but a plain `vi.mock` factory object in tests does not.
const RivePet = defineAsyncComponent(() => import('./RivePet.vue').then(module => module.default ?? module))

const props = withDefaults(defineProps<{ standalone?: boolean }>(), { standalone: false })
const store = useDesktopPetStore()
const profile = ref<PartnerProfile | null>(null)
const pet = ref<Pet | null>(null)
const petRenderer = ref<InstanceType<typeof RivePetComponent> | null>(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const dialogue = ref<Dialogue | null>(null)
const menuOpen = ref(false)
const feedOpen = ref(false)
const minimized = ref(false)
const isDesktop = ref(false)
const position = ref<Point>({ x: 24, y: 24 })
const variantIndex = ref(0)
const foodItems = computed(() => (profile.value?.shopItems ?? []).filter(item => (
  item.itemType === 'FOOD' && (!item.speciesCode || item.speciesCode === pet.value?.speciesCode)
)))
const frameStyle = computed(() => {
  if (props.standalone) return undefined
  const offsetX = minimized.value ? frameWidth - ballWidth : 0
  const offsetY = minimized.value ? frameHeight - ballHeight : 0
  return { transform: `translate3d(${position.value.x + offsetX}px, ${position.value.y + offsetY}px, 0)` }
})

const frameWidth = 286
const frameHeight = 276
const ballWidth = 68
const ballHeight = 68
const positionKey = 'better-self:desktop-pet-position'
let mediaQuery: MediaQueryList | null = null
let dialogueTimer: number | undefined
let dragStart: { pointerX: number; pointerY: number; frameX: number; frameY: number } | null = null
let idleHandle: number | null = null
let cancelIdle: ((handle: number) => void) | null = null
// Guards the reactive watcher below: hydrate() sets store.petPublicId synchronously
// on mount, which would otherwise fire an immediate profile fetch before the idle
// deferral has had a chance to run. Flipped on once the first idle-deferred load starts.
let autoLoadReady = false

function clearIdle() {
  if (idleHandle !== null && cancelIdle) cancelIdle(idleHandle)
  idleHandle = null
  cancelIdle = null
}

function scheduleIdle(run: () => void) {
  if (typeof requestIdleCallback === 'function') {
    idleHandle = requestIdleCallback(() => run())
    cancelIdle = handle => cancelIdleCallback(handle)
  } else {
    idleHandle = window.setTimeout(run, 300)
    cancelIdle = handle => window.clearTimeout(handle)
  }
}

function clampPosition(point: Point): Point {
  return {
    x: Math.max(12, Math.min(point.x, window.innerWidth - frameWidth - 12)),
    y: Math.max(12, Math.min(point.y, window.innerHeight - frameHeight - 12)),
  }
}

function defaultPosition(): Point {
  return clampPosition({ x: window.innerWidth - frameWidth - 24, y: window.innerHeight - frameHeight - 24 })
}

function restorePosition() {
  try {
    const saved = window.localStorage.getItem(positionKey)
    position.value = saved ? clampPosition(JSON.parse(saved) as Point) : defaultPosition()
  } catch {
    position.value = defaultPosition()
  }
}

function savePosition() {
  window.localStorage.setItem(positionKey, JSON.stringify(position.value))
}

async function loadProfile() {
  if (!isDesktop.value || (!store.petPublicId && !props.standalone)) return
  loading.value = true
  error.value = ''
  try {
    const loaded = await api.get<PartnerProfile>('/partners/profile')
    const configuredPet = loaded.pets.find(item => item.publicId === store.petPublicId) ?? (props.standalone ? loaded.selectedPet : null)
    if (!configuredPet) {
      store.clear()
      profile.value = null
      pet.value = null
      return
    }
    if (store.petPublicId !== configuredPet.publicId) store.setPet(configuredPet.publicId)
    profile.value = loaded
    pet.value = configuredPet
    const saved = Number(window.localStorage.getItem(petVariantStorageKey(configuredPet.publicId)) ?? '')
    variantIndex.value = Number.isInteger(saved) && saved >= 0 ? saved : variantIndexForKind(configuredPet.speciesCode, configuredPet.breed)
  } catch {
    error.value = '桌宠暂时睡着了'
  } finally {
    loading.value = false
  }
}

function showDialogue(message: Dialogue) {
  dialogue.value = message
  window.clearTimeout(dialogueTimer)
  dialogueTimer = window.setTimeout(() => { dialogue.value = null }, 8000)
}

async function interact() {
  if (!pet.value || busy.value) return
  busy.value = true
  error.value = ''
  petRenderer.value?.react('greet')
  try {
    const result = await api.post<InteractionResult>(`/partners/pets/${pet.value.publicId}/interact`)
    showDialogue({
      text: randomPetDialogue(),
      meta: result.rewarded ? `今日首次互动 +${result.affectionDelta} 好感` : '今天已经领取过互动奖励',
    })
    await loadProfile()
    notifyDataChanged(['partners', 'profile'])
  } catch {
    error.value = '互动失败，请稍后再试'
  } finally {
    busy.value = false
    menuOpen.value = false
  }
}

async function feed(item: ShopItem) {
  if (!pet.value || busy.value || item.price > (profile.value?.wallet.coinBalance ?? 0)) return
  busy.value = true
  error.value = ''
  try {
    const result = await api.post<{ pet: Pet; item: ShopItem }>('/partners/purchase', {
      petPublicId: pet.value.publicId,
      itemPublicId: item.publicId,
    })
    petRenderer.value?.react('feed')
    showDialogue({ text: `${item.name}真好吃，谢谢你陪我！`, meta: `好感度 +${result.item.affectionGain}` })
    feedOpen.value = false
    await loadProfile()
    notifyDataChanged(['partners', 'profile'])
  } catch (reason: any) {
    error.value = reason?.code === 'INSUFFICIENT_COINS' ? '金币不足，完成任务后再来喂我吧' : '喂食失败，请稍后再试'
  } finally {
    busy.value = false
  }
}

function openMenu() {
  menuOpen.value = true
  feedOpen.value = false
}

function openFeed() {
  feedOpen.value = true
  menuOpen.value = false
}

function openPartners() {
  menuOpen.value = false
  if (window.betterSelfDesktop?.isDesktopApp) {
    window.betterSelfDesktop.showSetup()
    return
  }
  window.location.href = 'better-self://pet'
}

function removeDesktopPet() {
  store.clear()
  menuOpen.value = false
  feedOpen.value = false
  if (props.standalone) window.betterSelfDesktop?.showSetup()
}

function minimizePet() {
  minimized.value = true
  menuOpen.value = false
  feedOpen.value = false
  window.betterSelfDesktop?.setCompact(true)
}

function wakePet() {
  minimized.value = false
  window.betterSelfDesktop?.setCompact(false)
}

function startDrag(event: PointerEvent) {
  if (props.standalone || event.button !== 0) return
  dragStart = { pointerX: event.clientX, pointerY: event.clientY, frameX: position.value.x, frameY: position.value.y }
  window.addEventListener('pointermove', drag)
  window.addEventListener('pointerup', stopDrag, { once: true })
}

function drag(event: PointerEvent) {
  if (!dragStart) return
  position.value = clampPosition({
    x: dragStart.frameX + event.clientX - dragStart.pointerX,
    y: dragStart.frameY + event.clientY - dragStart.pointerY,
  })
}

function stopDrag() {
  if (!dragStart) return
  dragStart = null
  window.removeEventListener('pointermove', drag)
  savePosition()
}

function closeTransient(event: PointerEvent) {
  const target = event.target as HTMLElement
  if (!target.closest('.desktop-pet')) {
    menuOpen.value = false
    feedOpen.value = false
  }
}

function syncDesktop(event?: MediaQueryListEvent) {
  isDesktop.value = event?.matches ?? mediaQuery?.matches ?? false
  if (isDesktop.value) restorePosition()
}

function handleResize() {
  if (isDesktop.value) position.value = clampPosition(position.value)
}

watch(() => store.petPublicId, () => { if (autoLoadReady) loadProfile() })

const stopDataSync = onDataChanged(['partners', 'tasks'], () => loadProfile())

onMounted(() => {
  // Cheap, synchronous setup runs immediately so the outer frame (header bar,
  // drag handle, minimize control) can render right away. The API request that
  // resolves a pet — and therefore the Rive chunk it triggers — waits for idle.
  store.hydrate()
  window.addEventListener('pointerdown', closeTransient)
  window.addEventListener('better-self:partners-updated', loadProfile)
  if (props.standalone) {
    isDesktop.value = true
    scheduleIdle(() => {
      autoLoadReady = true
      loadProfile()
      window.betterSelfDesktop?.showPet()
    })
    return
  }
  if (typeof window.matchMedia !== 'function') return
  mediaQuery = window.matchMedia('(min-width: 901px)')
  syncDesktop()
  mediaQuery.addEventListener('change', syncDesktop)
  window.addEventListener('resize', handleResize)
  scheduleIdle(() => {
    autoLoadReady = true
    loadProfile()
  })
})

onBeforeUnmount(() => {
  clearIdle()
  stopDataSync()
  window.clearTimeout(dialogueTimer)
  window.removeEventListener('pointermove', drag)
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('pointerdown', closeTransient)
  window.removeEventListener('better-self:partners-updated', loadProfile)
  mediaQuery?.removeEventListener('change', syncDesktop)
})
</script>

<template>
  <aside
    v-if="isDesktop && (store.petPublicId || standalone)"
    class="desktop-pet"
    :class="{ standalone, minimized }"
    :style="frameStyle"
    aria-label="桌面伙伴"
    @contextmenu.prevent.stop="openMenu"
  >
    <button v-if="minimized" type="button" class="wake-ball" :aria-label="`唤醒${pet?.name ?? '桌宠'}`" title="唤醒桌宠" @click="wakePet">
      <PawPrint :size="26" />
      <span>{{ pet?.name ? Array.from(pet.name)[0] : '伴' }}</span>
    </button>

    <template v-else>
      <header class="desktop-pet-bar" @pointerdown="startDrag">
        <span><i aria-hidden="true" />{{ pet?.name ?? '桌面伙伴' }}</span>
        <div class="desktop-window-actions">
          <button type="button" aria-label="最小化桌宠" title="最小化为小球" @pointerdown.stop @click.stop="minimizePet"><Minus :size="16" /></button>
          <button type="button" aria-label="桌宠选项" title="桌宠选项" @pointerdown.stop @click.stop="openMenu"><MoreHorizontal :size="17" /></button>
        </div>
      </header>

    <div class="desktop-stage">
      <RivePet
        v-if="pet"
        ref="petRenderer"
        :species-code="pet.speciesCode"
        :name="pet.name"
        :variant-index="variantIndex"
      />
      <p v-else class="desktop-status">{{ loading ? '正在唤醒伙伴…' : error || '伙伴正在休息' }}</p>

      <Transition name="pet-bubble">
        <div v-if="dialogue" class="desktop-dialogue" role="status" aria-live="polite">
          <strong>{{ dialogue.text }}</strong>
          <small>{{ dialogue.meta }}</small>
          <button type="button" aria-label="关闭桌宠对话" @click="dialogue = null"><X :size="14" /></button>
        </div>
      </Transition>
    </div>

    <footer v-if="pet" class="desktop-pet-footer">
      <span>LV.{{ pet.level }} · 好感 {{ pet.affection }}/{{ pet.nextLevelAffection }}</span>
      <span><Coins :size="13" />{{ profile?.wallet.coinBalance ?? 0 }}</span>
    </footer>

    <div v-if="menuOpen" class="desktop-menu" role="menu" aria-label="桌宠选项">
      <button type="button" role="menuitem" @click="openPartners"><House :size="16" />{{ standalone ? '打开桌宠面板' : '打开桌面端' }}</button>
      <button type="button" role="menuitem" @click="openFeed"><Utensils :size="16" />喂食</button>
      <button type="button" role="menuitem" :disabled="busy" @click="interact()"><HandHeart :size="16" />互动</button>
      <button type="button" role="menuitem" class="remove-item" @click="removeDesktopPet"><X :size="16" />取消桌宠</button>
    </div>

      <section v-if="feedOpen" class="feed-tray" aria-label="选择食物">
        <header><strong>给{{ pet?.name }}喂食</strong><button type="button" aria-label="关闭喂食选项" @click="feedOpen = false"><X :size="15" /></button></header>
        <div class="feed-list">
          <button v-for="item in foodItems" :key="item.publicId" type="button" :disabled="busy || item.price > (profile?.wallet.coinBalance ?? 0)" @click="feed(item)">
            <span>{{ item.name }}</span><small><Coins :size="12" />{{ item.price }} · +{{ item.affectionGain }}</small>
          </button>
        </div>
        <p v-if="!foodItems.length">暂时没有适合的食物</p>
      </section>
    </template>
  </aside>
</template>

<style scoped>
.desktop-pet { --pet-scene: #fff7e8; position: fixed; z-index: 70; top: 0; left: 0; width: 286px; height: 276px; display: grid; grid-template-rows: 38px minmax(0, 1fr) 34px; border: 1px solid #dfb995; border-radius: var(--radius-card); background: #fffaf2; color: #49362d; user-select: none; }
.desktop-pet::before { content: ''; position: absolute; z-index: -1; inset: 0; border-radius: inherit; pointer-events: none; }
.desktop-pet-bar { min-width: 0; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 3px 6px 0 11px; border-bottom: 1px solid #ead5bd; cursor: grab; touch-action: none; }
.desktop-pet-bar:active { cursor: grabbing; }
.desktop-pet-bar span { min-width: 0; display: inline-flex; align-items: center; gap: 7px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-weight: 800; }
.desktop-pet-bar i { width: 8px; height: 8px; flex: 0 0 auto; border-radius: 50%; background: #6eaa8c; }
.desktop-pet-bar button, .desktop-dialogue button, .feed-tray header button { width: 30px; height: 30px; min-height: 30px; display: grid; place-items: center; padding: 0; border: 0; background: transparent; color: #78675f; }
.desktop-pet-bar button:hover, .desktop-dialogue button:hover, .feed-tray header button:hover { background: #f4e8dc; }
.desktop-window-actions { display: flex; align-items: center; gap: 1px; }
.desktop-pet.standalone { position: relative; z-index: 1; top: auto; left: auto; transform: none; box-shadow: none; }
.desktop-pet.standalone .desktop-pet-bar { -webkit-app-region: drag; }
.desktop-pet.standalone button { -webkit-app-region: no-drag; }
.desktop-pet.minimized { width: 68px; height: 68px; display: block; overflow: hidden; border-radius: 50%; background: #f2bd73; box-shadow: var(--shadow); }
.wake-ball { width: 100%; height: 100%; min-height: 0; display: grid; place-content: center; justify-items: center; gap: 1px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: #964735; }
.wake-ball:hover { background: rgb(255 250 240 / 30%); }
.wake-ball span { font-size: 10px; font-weight: 900; line-height: 1; }
.desktop-stage { position: relative; min-height: 0; overflow: hidden; margin: 7px 8px 0; border: 1px solid #ead5bd; border-radius: var(--radius-scene) var(--radius-scene) var(--radius) var(--radius); background: var(--pet-scene); }
.desktop-status { height: 100%; display: grid; place-items: center; margin: 0; color: #78675f; font-size: 12px; }
.desktop-dialogue { position: absolute; z-index: 4; top: 9px; left: 10px; width: calc(100% - 20px); display: grid; grid-template-columns: minmax(0, 1fr) 26px; gap: 3px 6px; padding: 9px 7px 9px 11px; border: 1px solid #d9ad88; border-radius: var(--radius-card); background: rgb(255 253 248 / 96%); }
.desktop-dialogue::after { content: ''; position: absolute; left: 34px; bottom: -6px; width: 10px; height: 10px; transform: rotate(45deg); border-right: 1px solid #d9ad88; border-bottom: 1px solid #d9ad88; background: #fffdf8; }
.desktop-dialogue strong { min-width: 0; color: #49362d; font-size: 12px; line-height: 1.5; overflow-wrap: anywhere; }
.desktop-dialogue small { grid-column: 1; color: var(--dim-health); font-size: 10px; }
.desktop-dialogue button { grid-column: 2; grid-row: 1 / span 2; width: 26px; height: 26px; min-height: 26px; }
.desktop-pet-footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 11px; color: #78675f; font-size: 10px; }
.desktop-pet-footer span:last-child { display: inline-flex; align-items: center; gap: 4px; color: #9a6914; font-weight: 800; }
.desktop-menu { position: absolute; z-index: 8; right: 8px; top: 34px; width: 170px; display: grid; padding: 5px; border: 1px solid var(--border); border-radius: var(--radius-card); background: var(--surface); box-shadow: var(--shadow); }
.desktop-menu button { min-height: 36px; display: flex; align-items: center; justify-content: flex-start; gap: 9px; padding: 0 10px; border: 0; background: transparent; color: var(--ink); font-size: 12px; }
.desktop-menu button:hover { background: var(--surface-muted); }
.desktop-menu .remove-item { color: var(--danger); border-top: 1px solid var(--border); }
.feed-tray { position: absolute; z-index: 8; right: 8px; bottom: 8px; width: 230px; max-height: 220px; overflow: auto; padding: 7px; border: 1px solid var(--border); border-radius: var(--radius-card); background: var(--surface); color: var(--ink); box-shadow: var(--shadow); }
.feed-tray header { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 2px 3px 6px 7px; }
.feed-tray header strong { font-size: 12px; }
.feed-list { display: grid; gap: 4px; }
.feed-list button { min-height: 44px; display: grid; align-content: center; gap: 2px; padding: 5px 8px; border: 1px solid var(--border); background: var(--surface-muted); color: var(--ink); text-align: left; }
.feed-list span { font-size: 12px; font-weight: 750; }
.feed-list small { display: inline-flex; align-items: center; gap: 3px; color: var(--muted); font-size: 10px; }
.feed-tray > p { margin: 10px; color: var(--muted); font-size: 11px; }
.pet-bubble-enter-active, .pet-bubble-leave-active { transition: opacity var(--motion-fast) var(--ease), transform var(--motion-fast) var(--ease); }
.pet-bubble-enter-from, .pet-bubble-leave-to { opacity: 0; transform: translateY(4px); }
@media (prefers-reduced-motion: no-preference) { .desktop-pet { animation: desktop-pet-arrive var(--motion-slow) var(--ease) both; } }
@media (max-width: 900px) { .desktop-pet:not(.standalone) { display: none; } }
@keyframes desktop-pet-arrive { from { opacity: 0; } to { opacity: 1; } }
.desktop-pet { z-index: 30; border-color: var(--border); border-radius: var(--radius-panel); background: var(--surface); color: var(--ink); box-shadow: var(--shadow); }
.desktop-pet .desktop-dialogue { background: var(--surface); color: var(--ink); border-color: var(--border); border-radius: var(--radius); }
.desktop-pet .desktop-menu, .desktop-pet .feed-tray { border-radius: var(--radius); }
</style>
