<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { placeForPath } from '../../app/nav'
import { resolveStagePlace, STAGE_PLACES } from './companion-art'
// Pure geometry, no Phaser import (see companion-geometry.ts's header) - TownStage is mounted
// synchronously by every UserLayout, so importing the real './companion-scene' (which pulls in
// the whole Phaser runtime via CompanionStreetScene) here would drag Phaser into the first-paint
// chunk even though CompanionScene.vue itself stays behind defineAsyncComponent below.
import { residentTarget, scenePlace } from './companion-geometry'
import { sceneProjection, useTownClock } from './companion.presentation'
import { useTownWorld } from './companion.store'
import { useTownUi } from './town-ui.store'
import { useWorkspaceModeStore } from '../../shared/ui/workspace-mode.store'
import { motionAllowed } from '../../shared/ui/interaction/motion'

type ViewTransitionDocument = Document & { startViewTransition?: (callback: () => void | Promise<void>) => { ready: Promise<void> } }

// The Phaser chunk (and its town atlases) are not needed for first paint - this component defers
// even importing CompanionScene.vue until the browser is idle (or 300ms pass, whichever first),
// same pattern as DesktopPet.vue elsewhere in the shell.
const AsyncCompanionScene = defineAsyncComponent(() => import('./CompanionScene.vue'))

const route = useRoute()
const router = useRouter()
const mode = useWorkspaceModeStore()
const town = useTownWorld()
const { world, loaded } = storeToRefs(town)
const ui = useTownUi()
const { sceneActors, sceneProjects, sceneConversations, sceneObjects } = sceneProjection(() => world.value)
const { minutes } = useTownClock(() => world.value?.timezone)

const isFullscreen = computed(() => route.path === '/town')
const weather = computed(() => (world.value?.weather === 'rain' ? 'rain' : 'clear'))
const cafeOpen = computed(() => world.value?.cafeStatus !== 'closed' && world.value?.cafeStatus !== 'closing')

const placeId = computed(() => placeForPath(route.path))
const isAvatarPlace = computed(() => placeId.value === 'avatar')
// 'avatar' is a virtual place: /today's strip follows wherever the avatar actually is (the street
// itself is usually empty - everyone is inside a home or the cafe) instead of a fixed spot.
const avatarActor = computed(() => sceneActors.value.find(actor => actor.id === world.value?.avatar.id))
const avatarPlaceLabel = computed(() => {
  const place = world.value && STAGE_PLACES[scenePlace(world.value.avatar.place)]
  return place?.label ?? STAGE_PLACES.street!.label
})
const stagePlace = computed(() => resolveStagePlace(placeId.value))
const cameraTargetPoint = computed(() => {
  if (isAvatarPlace.value && avatarActor.value) return residentTarget(avatarActor.value, avatarActor.value.positionId)
  return stagePlace.value.target
})
const placeLabel = computed(() => {
  if (isAvatarPlace.value) return avatarPlaceLabel.value
  const raw = STAGE_PLACES[placeId.value]
  if (!raw) return stagePlace.value.label
  return raw.status === 'placeholder' ? `${raw.label} · 筹备中` : raw.label
})

// -- Teleport target: '#town-strip-slot' (UserLayout's street strip, growth mode), '#town-minimal-
// slot' (UserLayout's right-hand rail, minimal mode - 左清单右小镇, docs/04) or '#town-stage-slot'
// (CompanionView) while fullscreen. Nothing to teleport into fullscreen before the resident has
// joined the town - CompanionView shows its own arrival form there instead, and never renders the
// slot in that state, so there is no fullscreen target to resolve yet either. ------------------
const sceneRef = ref<{ refit?: () => void } | null>(null)
const target = ref<string | null>(null)
function computeTarget() {
  if (isFullscreen.value) return world.value ? '#town-stage-slot' : null
  return mode.minimal ? '#town-minimal-slot' : '#town-strip-slot'
}
async function applyTarget(next: string | null) {
  target.value = next
  await nextTick()
  sceneRef.value?.refit?.()
}
async function switchTarget() {
  const next = computeTarget()
  if (next === target.value) return
  const owner = document as ViewTransitionDocument
  if (motionAllowed() && typeof owner.startViewTransition === 'function') {
    const transition = owner.startViewTransition(() => applyTarget(next))
    try { await transition.ready } catch { /* A transition replaced by a newer one still applied the change. */ }
  } else {
    await applyTarget(next)
  }
}
watch(() => [route.path, Boolean(world.value), mode.minimal] as const, () => { void switchTarget() })

// -- Docked framing: the strip (wide, short) and the minimal rail (narrower, taller - see
// UserLayout.vue's .town-minimal) have very different aspect ratios. companion-scene.ts's docked
// camera derives the visible world width from the container's own aspect × dockedFrameHeight, so
// reusing the strip's tight 128 for the rail's much less wide box would crop to a sliver a few
// dozen world-px across. The rail gets its own, larger budget instead - still a close, cozy
// "peek in on them" window (roughly a room's width), not the strip's wider establishing shot.
// Below the 760px breakpoint the rail collapses back into a short, wide band (matches .town-strip
// there), so it goes back to the strip's own number for that shape.
const isNarrowViewport = ref(false)
let narrowQuery: MediaQueryList | undefined
function syncNarrowViewport(event?: MediaQueryList | MediaQueryListEvent) { isNarrowViewport.value = event?.matches ?? narrowQuery?.matches ?? false }
const dockedFrameHeight = computed(() => (target.value === '#town-minimal-slot' && !isNarrowViewport.value ? 360 : 128))

// -- Lifecycle: TownStage is the sole owner of the shared world's polling. It mounts once (for the
// whole signed-in session, not per-route) and never disposes the world on unmount - just stops its
// own timer consumer. ------------------------------------------------------------------------
onMounted(() => {
  target.value = computeTarget()
  void town.load().then(() => { if (world.value) void town.load(true) })
  town.start({ intervalMs: isFullscreen.value ? 5000 : 15000 })
  if (typeof window.matchMedia === 'function') {
    narrowQuery = window.matchMedia('(max-width: 760px)')
    syncNarrowViewport(narrowQuery)
    narrowQuery.addEventListener('change', syncNarrowViewport)
  }
})
watch(isFullscreen, full => town.setInterval(full ? 5000 : 15000))
onBeforeUnmount(() => { town.stop(); narrowQuery?.removeEventListener('change', syncNarrowViewport) })

// -- Causal feedback: a 2s strip bubble per newly-appeared world event, name + label only. -------
const eventBubble = ref<{ id: string; text: string } | null>(null)
let bubbleTimer: ReturnType<typeof setTimeout> | undefined
let stopEventWatch: (() => void) | undefined
onMounted(() => {
  stopEventWatch = town.onWorldEvent(event => {
    const actor = world.value?.residents.find(resident => resident.id === event.actorIds[0])
    const name = actor?.name || (event.actorIds[0] === world.value?.avatar.id ? world.value?.avatar.name : undefined)
    eventBubble.value = { id: event.id, text: name ? `${name}：${event.text}` : event.text }
    clearTimeout(bubbleTimer)
    bubbleTimer = setTimeout(() => { eventBubble.value = null }, 2000)
  })
})
onBeforeUnmount(() => { stopEventWatch?.(); clearTimeout(bubbleTimer) })

// -- First paint: a fixed-size --surface-muted placeholder holds the strip's height (CLS 0) until
// the browser is idle, then the real (async) CompanionScene mounts. ------------------------------
const showScene = ref(false)
let idleHandle: number | undefined
let cancelIdle: ((handle: number) => void) | undefined
onMounted(() => {
  const run = () => { showScene.value = true }
  if (typeof requestIdleCallback === 'function') { idleHandle = requestIdleCallback(run, { timeout: 300 }); cancelIdle = handle => cancelIdleCallback(handle) }
  else { idleHandle = window.setTimeout(run, 300); cancelIdle = handle => window.clearTimeout(handle) }
})
onBeforeUnmount(() => { if (idleHandle !== undefined) cancelIdle?.(idleHandle) })

function onShellClick() { if (!isFullscreen.value) void router.push('/town') }
function onSelectResident(id: string) { if (isFullscreen.value) ui.fullscreenHandlers.selectResident?.(id) }
function onSelectConversation(id: string) { if (isFullscreen.value) ui.fullscreenHandlers.selectConversation?.(id) }
function onSelectProject(id: string) { if (isFullscreen.value) ui.fullscreenHandlers.selectProject?.(id) }
</script>
<template>
  <Teleport v-if="target" :to="target" defer>
    <div class="town-stage-shell" :class="{ 'is-fullscreen': isFullscreen }" @click="onShellClick">
      <div class="stage-camera">
        <component
          :is="AsyncCompanionScene" v-if="showScene && world" ref="sceneRef"
          :residents="sceneActors" :weather="weather" :minutes="minutes" :sound-enabled="ui.soundEnabled" :cafe-open="cafeOpen"
          :text-bubbles="isFullscreen ? (ui.textBubbles && !ui.quiet) : ui.textBubbles"
          :overview="isFullscreen ? ui.overview : false"
          :selected-resident-id="isFullscreen ? (ui.selectedResident || undefined) : undefined"
          :selected-place="isFullscreen ? (ui.selectedPlace || undefined) : undefined"
          :projects="sceneProjects" :conversations="sceneConversations" :objects="sceneObjects"
          :suppress-hover="isFullscreen ? ui.hoverSuppressed : true"
          :chrome="isFullscreen"
          :camera-mode="isFullscreen ? 'auto' : 'docked'"
          :camera-target="isFullscreen ? undefined : cameraTargetPoint"
          :docked-frame-height="dockedFrameHeight"
          @select-resident="onSelectResident" @select-conversation="onSelectConversation" @select-project="onSelectProject"
        />
        <div v-else class="stage-placeholder" aria-hidden="true" />
      </div>
      <template v-if="!isFullscreen">
        <span class="stage-label"><span v-if="isAvatarPlace" class="stage-label-dot" aria-hidden="true" />{{ placeLabel }}</span>
        <Transition name="stage-bubble">
          <p v-if="eventBubble" :key="eventBubble.id" class="stage-bubble" role="status">{{ eventBubble.text }}</p>
        </Transition>
        <p v-if="loaded && !world" class="stage-join">
          <RouterLink to="/town" @click.stop>搬进小镇，让它陪你过今天 →</RouterLink>
        </p>
        <RouterLink v-else to="/town" class="stage-enter" aria-label="走进小镇全景" @click.stop>走进去 →</RouterLink>
      </template>
    </div>
  </Teleport>
</template>
<style scoped>
.town-stage-shell { position: relative; width: 100%; height: 100%; overflow: hidden; cursor: pointer; view-transition-name: town-stage; }
.town-stage-shell.is-fullscreen { cursor: default; }
.stage-camera { position: absolute; inset: 0; }
.stage-camera :deep(.companion-scene) { width: 100%; height: 100%; border: 0; border-radius: 0; }
.stage-camera :deep(.companion-scene__canvas) { width: 100%; height: 100%; aspect-ratio: auto; }
.stage-camera :deep(.companion-scene__credit) { bottom: 8px; right: 12px; }
.stage-placeholder { position: absolute; inset: 0; background: var(--surface-muted); }
.stage-label { position: absolute; left: 12px; top: 10px; z-index: 2; display: flex; align-items: center; gap: 5px; font-size: 12px; font-weight: 650; color: var(--ink); background: color-mix(in srgb, var(--surface) 86%, transparent); border: 1px solid var(--border); padding: 3px 10px; border-radius: 999px; pointer-events: none; }
/* "你在这": only on the avatar-follow strip (/today), marking that the label names the avatar's
   own current place rather than a fixed destination like every other page's strip. */
.stage-label-dot { width: 6px; height: 6px; flex: none; border-radius: 50%; background: var(--primary); }
.stage-enter { position: absolute; right: 12px; bottom: 10px; z-index: 2; font-size: 11px; font-weight: 650; color: var(--ink); background: color-mix(in srgb, var(--surface) 86%, transparent); border: 1px solid var(--border); padding: 5px 11px; border-radius: 999px; text-decoration: none; }
.stage-bubble { position: absolute; right: 12px; top: 10px; z-index: 2; max-width: min(70%, 320px); margin: 0; font-size: 12px; line-height: 1.5; color: var(--ink); background: color-mix(in srgb, var(--surface) 92%, transparent); border: 1px solid var(--border); padding: 6px 11px; border-radius: var(--radius-card); box-shadow: var(--shadow); pointer-events: none; }
.stage-bubble-enter-active, .stage-bubble-leave-active { transition: opacity var(--motion-medium) var(--ease); }
.stage-bubble-enter-from, .stage-bubble-leave-to { opacity: 0; }
.stage-join { position: absolute; inset: 0; z-index: 2; display: grid; place-items: center; margin: 0; }
.stage-join a { font-size: 12px; color: var(--muted); text-decoration: none; background: color-mix(in srgb, var(--surface) 88%, transparent); border: 1px solid var(--border); padding: 8px 14px; border-radius: var(--radius-card); }
</style>
